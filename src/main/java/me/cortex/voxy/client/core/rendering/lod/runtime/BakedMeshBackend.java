package me.cortex.voxy.client.core.rendering.lod.runtime;

import com.google.gson.*;
import me.cortex.voxy.client.compat.FragmentAtmosphereCompat;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.shader.*;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.lod.bake.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.lwjgl.opengl.GL45C.*;

/** Opt-in bounded offline-page preview. Replaces ALL old LOD draws while active.
 * Diagnostic full-cube model pages, no edit tracking/streaming/native stitching yet.
 * Indexed meshes share packed positions; materials/light remain per primitive. Exact
 * fallback pages keep 8-byte quads and decode through instancing, without expansion.
 */
public final class BakedMeshBackend implements AutoCloseable {
    private record Page(int vao,int vertex,int index,int attributes,int indexType,int count,boolean quads,int x,int y,int z,float[] bounds,int levelMask,int width) {}
    private final List<Page> pages=new ArrayList<>();
    private record TileKey(int x,int y,int z,int width) {}
    private static final class Tile {
        final float[] bounds=emptyBounds();
        final double[] errors=new double[3];
        int selected=-1,availableMask;
    }
    private final Map<TileKey,Tile> tiles=new LinkedHashMap<>();
    private final Map<Page,Tile> pageTiles=new IdentityHashMap<>();
    private static final class QuadSubmission {
        final long offset;final QuadFaceBuckets.Layout layout;long submitted;
        QuadSubmission(long offset,QuadFaceBuckets.Layout layout){this.offset=offset;this.layout=layout;}
    }
    private final Map<Page,QuadSubmission> quadSubmissions=new LinkedHashMap<>();
    private boolean[] singleSided;
    private int commandBuffer;
    private IntBuffer commandStaging;
    private final RenderProperties properties;
    private boolean automaticLevels;
    private record Uniforms(int mvp,int phase,int origin) {}
    private final Map<Integer,Uniforms> uniforms=new HashMap<>();
    private Shader shader,quadShader;private int table;private boolean active;private long gpuBytes,triangles,drawn,culled;
    public BakedMeshBackend(RenderProperties properties){this.properties=properties;}
    public boolean active(){return active;}
    public String enabled(boolean enabled){if(enabled&&pages.isEmpty())return "No baked pages loaded";active=enabled;return status();}
    public String status(){return "Baked mesh proof "+(active?"ON":"OFF")+": pages="+pages.size()+", GPU="+gpuBytes+" bytes, triangles="+triangles+", submitted="+drawn+", direction-culled="+culled+"; old Voxy geometry draws/traversal="+(active?"BYPASSED":"normal")+"; levels="+(automaticLevels?"projected error 2px + hysteresis":"fixed")+"; loaded package coverage only";}
    public String load(String name,int level)throws Exception {
        if(!name.matches("[a-zA-Z0-9_-]+")||level< -1||level>2)throw new IllegalArgumentException("Invalid package/level");
        if(!FragmentAtmosphereCompat.hasMaterial())throw new IllegalStateException("Fragment Render material required");
        Path root=Minecraft.getInstance().gameDirectory.toPath().resolve("lod-packages").resolve(name);
        JsonObject manifest=JsonParser.parseString(readSmall(root.resolve("manifest.json"),8<<20)).getAsJsonObject();
        if(!manifest.get("format").getAsString().equals("fragment-lod-proof-3")||manifest.get("unknown_voxels").getAsInt()!=0)throw new IllegalArgumentException("Unsupported/incomplete package");
        byte[] palette=Files.readAllBytes(checked(root.resolve("palette.json"),4<<20));
        verify(palette,manifest.get("palette_sha256").getAsString());
        var unique=new LinkedHashMap<String,JsonObject>();var coverage=new HashMap<String,Integer>();
        for(var element:manifest.getAsJsonArray("pages")){var p=element.getAsJsonObject().deepCopy();int l=p.get("level").getAsInt();
            if(l<0||l>2)throw new IllegalArgumentException("Invalid level");if(level>=0&&l!=level)continue;
            String tile=p.getAsJsonArray("tile").toString();int mask=1<<l;
            if((coverage.getOrDefault(tile,0)&mask)!=0)throw new IllegalArgumentException("Duplicate tile level");coverage.merge(tile,mask,(a,b)->a|b);
            String key=tile+"/"+p.get("file").getAsString();var old=unique.get(key);
            if(old==null){p.addProperty("level_mask",mask);unique.put(key,p);}else{if(!old.get("sha256").equals(p.get("sha256")))throw new IllegalArgumentException("Conflicting page hashes");old.addProperty("level_mask",old.get("level_mask").getAsInt()|mask);}
        }
        if(level<0&&coverage.values().stream().anyMatch(m->m!=7))throw new IllegalArgumentException("All three levels required per tile");
        var selected=new ArrayList<>(unique.values());long total=0;for(var p:selected)total+=p.get("bytes").getAsLong();
        if(selected.isEmpty()||selected.size()>4096||total>128L<<20)throw new IllegalArgumentException("Package budget exceeded (4096 pages/128MiB encoded)");
        // Validate every page before disturbing the currently visible package.
        record Cpu(SurfaceMesh mesh,PackedQuads quads,int x,int y,int z,int mask,int width,double error){}
        var decoded=new ArrayList<Cpu>();long vertices=0;
        for(var p:selected){String file=p.get("file").getAsString();if(!file.matches("[a-zA-Z0-9_.-]+"))throw new IllegalArgumentException("Invalid page name");Path path=checked(root.resolve(file),8<<20);byte[] bytes=Files.readAllBytes(path);verify(bytes,p.get("sha256").getAsString());
            SurfaceMesh mesh=null;PackedQuads quads=null;int x,y,z;
            if(p.get("representation").getAsString().equals("mesh")){var d=MeshPage.read(path);mesh=d.mesh();x=d.originX();y=d.originY();z=d.originZ();}
            else {var d=PackedQuads.read(path);quads=d.quads();x=d.originX();y=d.originY();z=d.originZ();}
            var tileCoordinates=p.getAsJsonArray("tile");
            if(tileCoordinates.size()!=2||tileCoordinates.get(0).getAsInt()!=x||tileCoordinates.get(1).getAsInt()!=z)
                throw new IllegalArgumentException("Page origin disagrees with manifest");
            vertices+=mesh!=null?mesh.indices().length:0;if(vertices>1200000)throw new IllegalArgumentException("Decoded proof GPU budget");int pageWidth=p.has("tile_width")?p.get("tile_width").getAsInt():manifest.get("tile_width").getAsInt();
            if(pageWidth<4||pageWidth>64)throw new IllegalArgumentException("Invalid page width");
            double error=0;
            if(mesh!=null){
                var audit=p.getAsJsonObject("candidate_error");
                if(audit==null||!audit.get("within_limit").getAsBoolean())throw new IllegalArgumentException("Mesh requires certified error");
                error=audit.get("upper_bound_blocks").getAsDouble();
                if(!Double.isFinite(error)||error<0)throw new IllegalArgumentException("Invalid certified error");
            }
            decoded.add(new Cpu(mesh,quads,x,y,z,p.get("level_mask").getAsInt(),pageWidth,error));}
        JsonArray entries=JsonParser.parseString(new String(palette,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
        if(entries.size()>8192)throw new IllegalArgumentException("Material budget");
        int width=manifest.get("tile_width").getAsInt();if(width<4||width>64)throw new IllegalArgumentException("Invalid tile width");
        close();automaticLevels=level<0;
        try {
            shader=Shader.make().apply(properties::apply).define("FR_HYBRID_SURFACE")
                .add(ShaderType.VERTEX,"voxy:lod/mesh/terrain.vert").add(ShaderType.FRAGMENT,"voxy:lod/mesh/terrain.frag")
                .replace("// FRAGMENT_MATERIAL_SOURCE",primitiveMaterialSource()).compile();
            quadShader=Shader.make().apply(properties::apply).define("FR_HYBRID_SURFACE").define("FV_PACKED_QUADS")
                .add(ShaderType.VERTEX,"voxy:lod/mesh/terrain.vert").add(ShaderType.FRAGMENT,"voxy:lod/mesh/terrain.frag")
                .replace("// FRAGMENT_MATERIAL_SOURCE",primitiveMaterialSource()).compile();
            for(Shader program:new Shader[]{shader,quadShader})uniforms.put(program.id(),new Uniforms(
                glGetUniformLocation(program.id(),"u_MVP"),glGetUniformLocation(program.id(),"u_CameraPhase"),glGetUniformLocation(program.id(),"u_Origin")));
            singleSided=new boolean[entries.size()];
            var mc=Minecraft.getInstance();
            for(int i=0;i<entries.size();i++){
                var state=state(entries.get(i).getAsJsonObject().get("state").getAsString());
                // Restrict to opaque full blocks. Leaves, fluids, unknown and
                // non-cube models keep their existing two-sided submission.
                singleSided[i]=state.getFluidState().isEmpty()&&state.canOcclude()
                    &&state.isSolidRender(mc.level,net.minecraft.core.BlockPos.ZERO);
            }
            float[] materials=materials(entries);table=glCreateBuffers();glNamedBufferData(table,materials,GL_STATIC_DRAW);gpuBytes=materials.length*4L;
            for(var cpu:decoded){if(cpu.mesh!=null)upload(cpu.mesh,cpu.x,cpu.y,cpu.z,entries.size(),cpu.mask,cpu.width);else uploadQuads(cpu.quads,cpu.x,cpu.y,cpu.z,entries.size(),cpu.mask,cpu.width);if(gpuBytes>192L*1024*1024)throw new IllegalArgumentException("Resident GPU budget exceeded");
                Page page=pages.getLast();Tile tile=tiles.computeIfAbsent(new TileKey(cpu.x,cpu.y,cpu.z,cpu.width),key->new Tile());
                if((tile.availableMask&cpu.mask)!=0)throw new IllegalArgumentException("Conflicting physical tile levels");
                tile.availableMask|=cpu.mask;
                pageTiles.put(page,tile);
                if(page.count>0){extend(tile.bounds,page.bounds[0],page.bounds[1],page.bounds[2]);extend(tile.bounds,page.bounds[3],page.bounds[4],page.bounds[5]);}
                for(int l=0;l<3;l++)if((cpu.mask&(1<<l))!=0)tile.errors[l]=cpu.error;
            }
            if(!quadSubmissions.isEmpty()) {
                commandStaging=MemoryUtil.memAllocInt(quadSubmissions.size()*QuadFaceBuckets.BUCKETS*QuadFaceBuckets.COMMAND_INTS);
                commandBuffer=glCreateBuffers();glNamedBufferData(commandBuffer,commandStaging.capacity()*4L,GL_DYNAMIC_DRAW);
                gpuBytes+=commandStaging.capacity()*4L;
                if(gpuBytes>192L*1024*1024)throw new IllegalArgumentException("Resident GPU budget exceeded");
            }
            singleSided=null;
            if(automaticLevels&&tiles.values().stream().anyMatch(t->t.availableMask!=7))throw new IllegalArgumentException("Physical tile needs all levels");
            active=true;return status();
        }catch(Exception error){close();throw error;}
    }
    private static Path checked(Path path,long limit)throws Exception{if(!Files.isRegularFile(path)||Files.size(path)>limit)throw new java.io.IOException("Missing/oversized page "+path.getFileName());return path;}
    private static String readSmall(Path path,long limit)throws Exception{return Files.readString(checked(path,limit));}
    private static void verify(byte[] data,String hash)throws Exception{if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).equals(hash))throw new java.io.IOException("Page checksum mismatch");}
    private static <T extends Comparable<T>> BlockState property(BlockState state,Property<T> p,String value){return state.setValue(p,p.getValue(value).orElseThrow());}
    private static BlockState state(String text){int bracket=text.indexOf('[');var id=ResourceLocation.parse(bracket<0?text:text.substring(0,bracket));var block=BuiltInRegistries.BLOCK.getOptional(id).orElseThrow();var state=block.defaultBlockState();if(bracket>=0)for(String pair:text.substring(bracket+1,text.length()-1).split(",")){String[] kv=pair.split("=");var p=block.getStateDefinition().getProperty(kv[0]);if(p==null)throw new IllegalArgumentException("Unknown property");state=property(state,p,kv[1]);}return state;}
    private static float[] materials(JsonArray palette){var mc=Minecraft.getInstance();float[] result=new float[palette.size()*6*8];
        for(int i=0;i<palette.size();i++){var entry=palette.get(i).getAsJsonObject();var state=state(entry.get("state").getAsString());var model=mc.getBlockRenderer().getBlockModel(state);
            var biome=mc.level.registryAccess().registryOrThrow(Registries.BIOME).get(ResourceLocation.parse(entry.get("biome").getAsString()));if(biome==null)throw new IllegalArgumentException("Unknown biome");
            String name=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();float material=name.endsWith("leaves")?2:name.equals("grass_block")||name.equals("dirt")?3:0;
            for(int face=0;face<6;face++){Direction direction=new Direction[]{Direction.WEST,Direction.EAST,Direction.DOWN,Direction.UP,Direction.NORTH,Direction.SOUTH}[face];var quads=model.getQuads(state,direction,RandomSource.create(0));var sprite=quads.isEmpty()?model.getParticleIcon():quads.getFirst().getSprite();boolean tinted=!quads.isEmpty()&&quads.getFirst().isTinted();int tint=tinted?ModelFactory.captureSurfaceTint(state,biome):0xffffff;int o=(i*6+face)*8;
                result[o]=sprite.getU0();result[o+1]=sprite.getV0();result[o+2]=sprite.getU1();result[o+3]=sprite.getV1();result[o+4]=((tint>>16)&255)/255f;result[o+5]=((tint>>8)&255)/255f;result[o+6]=(tint&255)/255f;result[o+7]=material;}}
        return result;}
    /** Adapt existing receiver inputs to explicit per-primitive inputs, without double lightmap tint. */
    public static String primitiveMaterialSource(){return FragmentAtmosphereCompat.materialSource().replaceAll("(?m)^in (vec3|float) (fr_RelativePosition|fr_SkyLight|fr_BlockLight|fr_BaseLight|fr_CanopyNormal|fv_SurfaceNormal);", "$1 $2;");}
    private void upload(SurfaceMesh mesh,int x,int y,int z,int paletteSize,int mask,int width){
        int[] attributes=mesh.faceData();for(int attribute:attributes)if((attribute>>>11)>=paletteSize||((attribute>>>8)&7)>5)throw new IllegalArgumentException("Invalid face material");
        long[] positions=new long[mesh.vertices()];for(int i=0;i<positions.length;i++)positions[i]=MeshGpuLayout.pack(mesh.positions()[i*3],mesh.positions()[i*3+1],mesh.positions()[i*3+2],mesh.normals()[i*3],mesh.normals()[i*3+1],mesh.normals()[i*3+2]);
        int vao=glCreateVertexArrays(),vbo=glCreateBuffers(),ebo=glCreateBuffers(),attr=glCreateBuffers();boolean shortIndex=mesh.vertices()<=65536;
        pages.add(new Page(vao,vbo,ebo,attr,shortIndex?GL_UNSIGNED_SHORT:GL_UNSIGNED_INT,mesh.indices().length,false,x,y,z,bounds(mesh),mask,width));
        glNamedBufferData(vbo,positions,GL_STATIC_DRAW);glNamedBufferData(attr,attributes,GL_STATIC_DRAW);
        if(shortIndex){short[] ix=new short[mesh.indices().length];for(int j=0;j<ix.length;j++)ix[j]=(short)mesh.indices()[j];glNamedBufferData(ebo,ix,GL_STATIC_DRAW);}else glNamedBufferData(ebo,mesh.indices(),GL_STATIC_DRAW);
        glVertexArrayVertexBuffer(vao,0,vbo,0,8);glVertexArrayElementBuffer(vao,ebo);glEnableVertexArrayAttrib(vao,0);glVertexArrayAttribBinding(vao,0,0);glVertexArrayAttribIFormat(vao,0,2,GL_UNSIGNED_INT,0);
        gpuBytes+=positions.length*8L+attributes.length*4L+mesh.indices().length*(shortIndex?2L:4L);triangles+=mesh.triangles();
    }
    private void uploadQuads(PackedQuads quads,int x,int y,int z,int paletteSize,int mask,int width){
        for(long q:quads.data())if(PackedQuads.material(q)>=paletteSize)throw new IllegalArgumentException("Invalid quad material");
        var partition=QuadFaceBuckets.partition(quads,singleSided);quads=partition.quads();
        int vao=glCreateVertexArrays(),data=glCreateBuffers(),index=glCreateBuffers();pages.add(new Page(vao,data,index,0,GL_UNSIGNED_SHORT,quads.data().length,true,x,y,z,bounds(quads),mask,width));
        quadSubmissions.put(pages.getLast(),new QuadSubmission(quadSubmissions.size()*(long)QuadFaceBuckets.COMMAND_BYTES,partition.layout()));
        glNamedBufferData(data,quads.data(),GL_STATIC_DRAW);glNamedBufferData(index,new short[]{0,1,2,0,2,3},GL_STATIC_DRAW);glVertexArrayElementBuffer(vao,index);
        gpuBytes+=quads.payloadBytes()+12;triangles+=quads.triangles();
    }
    private static float[] emptyBounds(){return new float[]{Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};}
    private static void extend(float[] b,float x,float y,float z){b[0]=Math.min(b[0],x);b[1]=Math.min(b[1],y);b[2]=Math.min(b[2],z);b[3]=Math.max(b[3],x);b[4]=Math.max(b[4],y);b[5]=Math.max(b[5],z);}
    private static float[] bounds(SurfaceMesh mesh){float[] b=emptyBounds();for(int i=0;i<mesh.positions().length;i+=3)extend(b,mesh.positions()[i],mesh.positions()[i+1],mesh.positions()[i+2]);return b;}
    private static float[] bounds(PackedQuads quads){float[] b=emptyBounds();for(long q:quads.data()){int x=PackedQuads.x(q),y=PackedQuads.y(q),z=PackedQuads.z(q),f=PackedQuads.face(q),u=PackedQuads.u(q),v=PackedQuads.v(q);extend(b,x,y,z);extend(b,x+(f<2?1:u),y+(f<2?v:f<4?1:v),z+(f<2?u:f<4?v:1));}return b;}
    private void selectLevels(Viewport<?> view) {
        double focal=Math.max(Math.abs(view.projection.m00())*view.width,
                              Math.abs(view.projection.m11())*view.height)*.5;
        for(var entry:tiles.entrySet()) {
            TileKey key=entry.getKey();Tile tile=entry.getValue();float[] b=tile.bounds;
            if(!Float.isFinite(b[0])){tile.selected=0;continue;}
            double near=Double.POSITIVE_INFINITY,lateral=0;
            for(int k=0;k<8;k++) {
                double x=key.x-view.cameraX+b[(k&1)==0?0:3];
                double y=key.y-view.cameraY+b[(k&2)==0?1:4];
                double z=key.z-view.cameraZ+b[(k&4)==0?2:5];
                var m=view.modelView;
                double vx=m.m00()*x+m.m10()*y+m.m20()*z+m.m30();
                double vy=m.m01()*x+m.m11()*y+m.m21()*z+m.m31();
                double vz=m.m02()*x+m.m12()*y+m.m22()*z+m.m32();
                near=Math.min(near,-vz);lateral=Math.max(lateral,Math.max(Math.abs(vx),Math.abs(vy)));
            }
            tile.selected=MeshLevelPolicy.projectedLevel(tile.errors,near,lateral,focal,tile.selected,2);
        }
    }
    public void render(Viewport<?> view){if(!active)return;drawn=culled=0;
        if(automaticLevels)selectLevels(view);
        if(commandStaging!=null){
            commandStaging.clear();
            for(var entry:quadSubmissions.entrySet()) {
                Page page=entry.getKey();
                long count=entry.getValue().layout.commands(commandStaging,view.cameraX-page.x,view.cameraY-page.y,view.cameraZ-page.z);
                entry.getValue().submitted=count;
            }
            commandStaging.flip();glNamedBufferSubData(commandBuffer,0,commandStaging);
        }
        int previousIndirect=glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER,commandBuffer);
        glBindTextureUnit(0,Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId());glBindSampler(0,0);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,14,table);
        glEnable(GL_DEPTH_TEST);glDepthFunc(properties.closerEqualDepthCompare());glDepthMask(true);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);
        Shader bound=null;
        try{for(Page page:pages){
            if(automaticLevels&&(page.levelMask&(1<<pageTiles.get(page).selected))==0)continue;
            float x=(float)(page.x-view.cameraX),y=(float)(page.y-view.cameraY),z=(float)(page.z-view.cameraZ);float[] b=page.bounds;
            if(page.count==0||!view.frustum.testAab(x+b[0]-.01f,y+b[1]-.01f,z+b[2]-.01f,x+b[3]+.01f,y+b[4]+.01f,z+b[5]+.01f))continue;
            Shader program=page.quads?quadShader:shader;
            if(program!=bound){
                if(bound!=null){FragmentAtmosphereCompat.releaseMaterial();bound=null;}
                program.bind();
                try(var stack=org.lwjgl.system.MemoryStack.stackPush()){glUniformMatrix4fv(uniforms.get(program.id()).mvp(),false,view.MVP.get(stack.mallocFloat(16)));}
                glUniform3f(uniforms.get(program.id()).phase(),(float)(view.cameraX%1),(float)(view.cameraY%1),(float)(view.cameraZ%1));
                FragmentAtmosphereCompat.bindMaterial(program.id());bound=program;
            }
            glUniform3f(uniforms.get(program.id()).origin(),x,y,z);
            glBindVertexArray(page.vao);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,13,page.quads?page.vertex:page.attributes);
            if(page.quads){glMultiDrawElementsIndirect(GL_TRIANGLES,GL_UNSIGNED_SHORT,quadSubmissions.get(page).offset,QuadFaceBuckets.BUCKETS,0);drawn+=quadSubmissions.get(page).submitted;culled+=page.count*2L-quadSubmissions.get(page).submitted;}else{glDrawElements(GL_TRIANGLES,page.count,page.indexType,0L);drawn+=page.count/3;}
        }}finally{
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER,previousIndirect);
            if(bound!=null)FragmentAtmosphereCompat.releaseMaterial();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,14,0);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,13,0);glBindVertexArray(0);glBindTextureUnit(0,0);glEnable(GL_CULL_FACE);
        }
    }
    @Override public void close(){active=false;for(Page p:pages){glDeleteVertexArrays(p.vao);glDeleteBuffers(p.vertex);glDeleteBuffers(p.index);glDeleteBuffers(p.attributes);}pages.clear();quadSubmissions.clear();singleSided=null;
        if(commandStaging!=null){MemoryUtil.memFree(commandStaging);commandStaging=null;}
        if(commandBuffer!=0){glDeleteBuffers(commandBuffer);commandBuffer=0;}
        pageTiles.clear();tiles.clear();uniforms.clear();if(table!=0)glDeleteBuffers(table);table=0;if(shader!=null)shader.free();shader=null;if(quadShader!=null)quadShader.free();quadShader=null;gpuBytes=triangles=drawn=culled=0;}
}
