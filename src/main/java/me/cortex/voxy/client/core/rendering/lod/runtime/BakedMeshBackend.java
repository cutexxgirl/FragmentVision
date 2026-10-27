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
 * Prepared terrain parents and a native-ready transition cache; retained models
 * keep exact quads. World-edit streaming remains separate work.
 * Indexed meshes share packed positions; materials/light remain per primitive. Exact
 * fallback pages keep 8-byte quads and decode through instancing, without expansion.
 */
public final class BakedMeshBackend implements AutoCloseable {
    private record Page(int vao,int vertex,int index,int attributes,int indexType,int count,boolean quads,int x,int y,int z,float[] bounds,int levelMask,int width,boolean compact,int attributeBits,int dictionarySize,int primitiveCount) {}
    private final List<Page> pages=new ArrayList<>();
    private record TileKey(int x,int y,int z,int width,String layer) {}
    private static final class Tile {
        final float[] bounds=emptyBounds();
        final double[] errors=new double[3];
        int selected=-1,availableMask;
    }
    private final Map<TileKey,Tile> tiles=new LinkedHashMap<>();
    private final Map<Page,Tile> pageTiles=new IdentityHashMap<>();
    private final Map<TileKey,Tile> terrainOwners=new HashMap<>();
    private record CapBuffer(int handle,int macroWidth){}
    private final Map<Page,CapBuffer> pageCaps=new IdentityHashMap<>();
    private final Map<String,CapBuffer> capBuffers=new HashMap<>();
    private static final class QuadSubmission {
        final long offset;final QuadFaceBuckets.Layout layout;long submitted;int commandCount;
        QuadSubmission(long offset,QuadFaceBuckets.Layout layout){this.offset=offset;this.layout=layout;}
    }
    private final Map<Page,QuadSubmission> quadSubmissions=new LinkedHashMap<>();
    private boolean[] singleSided;
    private int commandBuffer;
    private IntBuffer commandStaging;
    private final RenderProperties properties;
    private Path loadedRoot;
    private final NativeTerrainJoin nativeJoin=new NativeTerrainJoin();
    private final Map<TileKey,Page> collars=new HashMap<>();
    private record CollarAsset(SurfaceMesh mesh,float[] fine,int paletteSize){}
    private final Map<TileKey,CollarAsset> collarAssets=new LinkedHashMap<>();
    private final Map<Page,Integer> fineBuffers=new IdentityHashMap<>();
    private boolean linkedNative;
    private int drawnCollars;
    private boolean nativeJoinEnabled=true;
    public String nativeJoin(boolean enabled){nativeJoinEnabled=enabled;return "Native geometry join="+enabled;}
    private record SourceColour(int y,String state,String biome){}
    private SourceColour sourceColour(int x,int z,int expectedY,boolean leaves,JsonArray palette,JsonArray rows,Map<String,PackedQuads.Decoded> cache)throws Exception{
        SourceColour best=null;
        for(var row:rows){var p=row.getAsJsonObject();if(p.get("level").getAsInt()!=0||!p.has("layer")||!p.get("layer").getAsString().equals(leaves?"detail":"terrain"))continue;
            var tile=p.getAsJsonArray("tile");int tx=tile.get(0).getAsInt(),tz=tile.get(1).getAsInt(),width=p.get("tile_width").getAsInt();if(x<tx||z<tz||x>=tx+width||z>=tz+width)continue;
            String file=(p.has("native_source_file")?p.get("native_source_file"):p.get("file")).getAsString();var data=cache.get(file);if(data==null){data=PackedQuads.read(checked(loadedRoot.resolve(file),8<<20));cache.put(file,data);}
            for(long q:data.quads().data())if(PackedQuads.face(q)==3){
                int qx=data.originX()+PackedQuads.x(q),qz=data.originZ()+PackedQuads.z(q),qy=data.originY()+PackedQuads.y(q);
                if(x<qx||x>=qx+PackedQuads.u(q)||z<qz||z>=qz+PackedQuads.v(q))continue;
                var material=palette.get(PackedQuads.material(q)).getAsJsonObject();String state=material.get("state").getAsString();
                if(leaves&&!LodInputPolicy.name(state).endsWith("_leaves"))continue;
                if(best==null||(leaves?Math.abs(qy-expectedY)<Math.abs(best.y-expectedY):qy>best.y))best=new SourceColour(qy,state,material.get("biome").getAsString());
            }
        }return best;
    }
    public String auditColours()throws Exception{
        if(loadedRoot==null)throw new IllegalStateException("Load a package first: audit must use its saved biomes");
        var mc=Minecraft.getInstance();var camera=mc.gameRenderer.getMainCamera().getPosition();
        var palette=JsonParser.parseString(readSmall(loadedRoot.resolve("palette.json"),4<<20)).getAsJsonArray();
        var rows=JsonParser.parseString(readSmall(loadedRoot.resolve("manifest.json"),16<<20)).getAsJsonObject().getAsJsonArray("pages");
        var cache=new HashMap<String,PackedQuads.Decoded>();
        var output=new StringBuilder("x\ty\tz\tnative_biome\tsaved_biome\tsaved_y\tstate\tnative_rgb\tlod_rgb\tmax_channel_delta\n");int exact=0,total=0,max=0,heightDelta=0,missing=0,biomeMismatch=0;
        for(int dz=-64;dz<=64;dz+=16)for(int dx=-64;dx<=64;dx+=16){
            int x=(int)Math.floor(camera.x)+dx,z=(int)Math.floor(camera.z)+dz;if(!mc.level.hasChunk(x>>4,z>>4))continue;
            int y=mc.level.getMaxBuildHeight()-1,leafY=Integer.MIN_VALUE;BlockState leaf=null;
            while(y>=mc.level.getMinBuildHeight()){
                var sample=mc.level.getBlockState(new net.minecraft.core.BlockPos(x,y,z));
                if(leaf==null&&sample.is(net.minecraft.tags.BlockTags.LEAVES)){leaf=sample;leafY=y;}
                if(LodInputPolicy.terrain(BuiltInRegistries.BLOCK.getKey(sample.getBlock()).toString()))break;y--;
            }
            if(y<mc.level.getMinBuildHeight())continue;
            var ground=sourceColour(x,z,y,false,palette,rows,cache);
            var states=new ArrayList<>(List.of(net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(),net.minecraft.world.level.block.Blocks.BIRCH_LEAVES.defaultBlockState(),net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState(),net.minecraft.world.level.block.Blocks.SPRUCE_LEAVES.defaultBlockState()));
            if(leaf!=null)states.add(leaf);
            for(int sample=0;sample<states.size();sample++){
                var state=states.get(sample);boolean actualLeaf=sample==4;int atY=actualLeaf?leafY:y;var source=actualLeaf?sourceColour(x,z,atY,true,palette,rows,cache):ground;
                if(source==null){missing++;continue;}heightDelta=Math.max(heightDelta,Math.abs(source.y-atY));
                var pos=new net.minecraft.core.BlockPos(x,atY,z);var nativeBiome=mc.level.getBiome(pos).value();var nativeBiomeKey=mc.level.registryAccess().registryOrThrow(Registries.BIOME).getKey(nativeBiome).toString();
                var savedBiome=mc.level.registryAccess().registryOrThrow(Registries.BIOME).get(ResourceLocation.parse(source.biome));if(savedBiome==null)throw new IllegalStateException("Saved biome missing");
                if(!nativeBiomeKey.equals(source.biome))biomeMismatch++;
                int nativeTint=mc.getBlockColors().getColor(state,mc.level,pos,1)&0xffffff,lodTint=ModelFactory.captureSurfaceTint(state,savedBiome)&0xffffff;
                int delta=0;for(int shift:new int[]{0,8,16})delta=Math.max(delta,Math.abs(((nativeTint>>shift)&255)-((lodTint>>shift)&255)));total++;if(delta==0)exact++;max=Math.max(max,delta);
                output.append(x).append('\t').append(atY).append('\t').append(z).append('\t').append(nativeBiomeKey).append('\t').append(source.biome).append('\t').append(source.y).append('\t').append(BuiltInRegistries.BLOCK.getKey(state.getBlock())).append('\t').append(String.format("%06x\t%06x\t%d\n",nativeTint,lodTint,delta));
            }
        }
        Files.writeString(mc.gameDirectory.toPath().resolve("lod-colour-audit.tsv"),output);
        return "Native/saved LOD tint audit: exact="+exact+"/"+total+", max RGB delta="+max+", biome mismatches="+biomeMismatch+", max source height delta="+heightDelta+", missing="+missing+"; lod-colour-audit.tsv";
    }
    private boolean automaticLevels;
    private int submissionMode=3;
    public String submissionMode(int mode){if(mode<0||mode>3)throw new IllegalArgumentException("Submission mode");submissionMode=mode;return "Mesh submission="+mode+" (0 split, 1 direct buckets, 2 mixed MDI, 3 compact opaque MDI + direct models)";}
    private record Uniforms(int mvp,int phase,int origin,int attributeBits,int attributeOffset,int surfaceMacroWidth,int lodEdge,int explicitFine,int snowPage) {}
    private final Map<Integer,Uniforms> uniforms=new HashMap<>();
    private int drawnMeshPages,drawnQuadPages;private final int[] terrainLevels=new int[3];
    private Shader shader,quadShader,compactShader,collarShader,snowShader;private int table;private boolean active;private long gpuBytes,triangles,drawn,culled;
    public BakedMeshBackend(RenderProperties properties){this.properties=properties;}
    public boolean active(){return active;}
    public String enabled(boolean enabled){if(enabled&&pages.isEmpty())return "No baked pages loaded";active=enabled;return status();}
    public String status(){return "Baked mesh proof "+(active?"ON":"OFF")+": pages="+pages.size()+", GPU="+gpuBytes+" bytes, triangles="+triangles+", submitted="+drawn+", direction-culled="+culled+"; old Voxy geometry draws/traversal="+(active?"BYPASSED":"normal")+"; levels="+(automaticLevels?"projected error 2px + hysteresis":"fixed")+"; terrain levels="+Arrays.toString(terrainLevels)+", drawn mesh/quad pages="+drawnMeshPages+"/"+drawnQuadPages+"; loaded package coverage only; native collars="+drawnCollars+"/"+collars.size()+", CPU endpoints="+collarAssets.size()+", ready columns="+nativeJoin.readyColumns()+"; submission="+submissionMode;}
    public String load(String name,int level)throws Exception {
        if(!name.matches("[a-zA-Z0-9_-]+")||level< -1||level>2)throw new IllegalArgumentException("Invalid package/level");
        if(!FragmentAtmosphereCompat.hasMaterial())throw new IllegalStateException("Fragment Render material required");
        Path root=Minecraft.getInstance().gameDirectory.toPath().resolve("lod-packages").resolve(name);
        JsonObject manifest=JsonParser.parseString(readSmall(root.resolve("manifest.json"),16<<20)).getAsJsonObject();
        if(!manifest.get("format").getAsString().equals("fragment-lod-proof-3")||manifest.get("unknown_voxels").getAsInt()!=0)throw new IllegalArgumentException("Unsupported/incomplete package");
        byte[] palette=Files.readAllBytes(checked(root.resolve("palette.json"),4<<20));
        verify(palette,manifest.get("palette_sha256").getAsString());
        var unique=new LinkedHashMap<String,JsonObject>();var coverage=new HashMap<String,Integer>();
        for(var element:manifest.getAsJsonArray("pages")){var p=element.getAsJsonObject().deepCopy();int l=p.get("level").getAsInt();
            if(l<0||l>2)throw new IllegalArgumentException("Invalid level");if(level>=0&&l!=level)continue;
            String layer=p.has("layer")?p.get("layer").getAsString():"solid";
            if(!layer.matches("[a-zA-Z0-9_-]{1,32}"))throw new IllegalArgumentException("Invalid layer");
            String tile=p.getAsJsonArray("tile").toString()+"/"+layer;int mask=1<<l;
            if((coverage.getOrDefault(tile,0)&mask)!=0)throw new IllegalArgumentException("Duplicate tile level");coverage.merge(tile,mask,(a,b)->a|b);
            double error=pageError(p);JsonArray levelErrors=new JsonArray();for(int i=0;i<3;i++)levelErrors.add(i==l?error:0.);p.add("level_errors",levelErrors);
            String key=tile+"/"+p.get("file").getAsString();var old=unique.get(key);
            if(old==null){p.addProperty("level_mask",mask);unique.put(key,p);}else{if(!old.get("sha256").equals(p.get("sha256")))throw new IllegalArgumentException("Conflicting page hashes");if(!Objects.equals(old.get("lod_group"),p.get("lod_group")))throw new IllegalArgumentException("Conflicting LOD groups");if(!Objects.equals(old.get("surface_map_sha256"),p.get("surface_map_sha256")))throw new IllegalArgumentException("Conflicting surface map");
                old.getAsJsonArray("level_errors").set(l,new JsonPrimitive(error));old.addProperty("level_mask",old.get("level_mask").getAsInt()|mask);}
        }
        if(level<0&&coverage.values().stream().anyMatch(m->m!=7))throw new IllegalArgumentException("All three levels required per tile");
        var selected=new ArrayList<>(unique.values());long total=0;var capAssets=new HashMap<String,String>();
        for(var p:selected){total+=p.get("bytes").getAsLong();if(p.has("surface_map")){
            String file=p.get("surface_map").getAsString(),hash=p.get("surface_map_sha256").getAsString();
            String prior=capAssets.putIfAbsent(file,hash);if(prior==null)total+=p.get("surface_map_bytes").getAsLong();else if(!prior.equals(hash))throw new IllegalArgumentException("Conflicting cap hashes");
        }}
        if(selected.isEmpty()||selected.size()>8192||total>128L<<20)throw new IllegalArgumentException("Package budget exceeded (8192 pages/128MiB encoded)");
        // Validate every page before disturbing the currently visible package.
        record Cpu(SurfaceMesh mesh,PackedQuads quads,int x,int y,int z,int mask,int width,double[] errors,String layer,boolean compact,SurfaceMaterialMap.Data cap,String capFile,TileKey group){}
        JsonArray entries=JsonParser.parseString(new String(palette,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
        if(entries.size()>8192)throw new IllegalArgumentException("Material budget");
        var decoded=new ArrayList<Cpu>();var decodedCaps=new HashMap<String,SurfaceMaterialMap.Data>();long decodedGeometryBytes=0;
        for(var p:selected){String file=p.get("file").getAsString();if(!file.matches("[a-zA-Z0-9_.-]+"))throw new IllegalArgumentException("Invalid page name");Path path=checked(root.resolve(file),8<<20);byte[] bytes=Files.readAllBytes(path);verify(bytes,p.get("sha256").getAsString());
            SurfaceMesh mesh=null;PackedQuads quads=null;int x,y,z;
            if(p.get("representation").getAsString().equals("mesh")){var d=MeshPage.read(path);mesh=d.mesh();x=d.originX();y=d.originY();z=d.originZ();}
            else {var d=PackedQuads.read(path);quads=d.quads();x=d.originX();y=d.originY();z=d.originZ();}
            var tileCoordinates=p.getAsJsonArray("tile");
            if(tileCoordinates.size()!=2||tileCoordinates.get(0).getAsInt()!=x||tileCoordinates.get(1).getAsInt()!=z)
                throw new IllegalArgumentException("Page origin disagrees with manifest");
            decodedGeometryBytes+=mesh!=null?mesh.vertices()*8L+mesh.triangles()*10L:quads.payloadBytes()+12;if(decodedGeometryBytes>192L*1024*1024)throw new IllegalArgumentException("Decoded geometry GPU byte budget");int pageWidth=p.has("tile_width")?p.get("tile_width").getAsInt():manifest.get("tile_width").getAsInt();
            if(pageWidth<4||pageWidth>64)throw new IllegalArgumentException("Invalid page width");
            double[] errors=new double[3];for(int l=0;l<3;l++)errors[l]=p.getAsJsonArray("level_errors").get(l).getAsDouble();
            SurfaceMaterialMap.Data cap=null;String capFile=null;
            if(p.has("surface_map")){
                capFile=p.get("surface_map").getAsString();if(!capFile.matches("[a-zA-Z0-9_.-]+"))throw new IllegalArgumentException("Invalid surface map name");
                cap=decodedCaps.get(capFile);
                if(cap==null){Path capPath=checked(root.resolve(capFile),1<<20);byte[] capBytes=Files.readAllBytes(capPath);verify(capBytes,p.get("surface_map_sha256").getAsString());
                    if(capBytes.length!=p.get("surface_map_bytes").getAsLong())throw new IllegalArgumentException("Surface map size mismatch");
                    cap=SurfaceMaterialMap.read(capPath);cap.validate(entries.size());decodedCaps.put(capFile,cap);}
                if(cap.originX()!=x||cap.originZ()!=z||cap.width()!=pageWidth)throw new IllegalArgumentException("Surface map origin mismatch");
            }
            String layer=p.has("layer")?p.get("layer").getAsString():"solid";
            TileKey group=new TileKey(x,y,z,pageWidth,layer);
            if(p.has("lod_group")){
                var g=p.getAsJsonArray("lod_group");if(g.size()!=3||pageWidth!=32||!layer.equals("terrain"))throw new IllegalArgumentException("Invalid joined terrain group");
                int gx=g.get(0).getAsInt(),gz=g.get(1).getAsInt(),gw=g.get(2).getAsInt();
                long dx=(long)x-gx,dz=(long)z-gz;
                if(gw!=64||dx<0||dz<0||dx>=64||dz>=64||dx%32!=0||dz%32!=0)throw new IllegalArgumentException("Page outside joined terrain group");
                group=new TileKey(gx,y,gz,gw,layer);
            }
            decoded.add(new Cpu(mesh,quads,x,y,z,p.get("level_mask").getAsInt(),pageWidth,errors,p.has("layer")?p.get("layer").getAsString():"solid",mesh!=null&&p.has("mesh_layout")&&p.get("mesh_layout").getAsString().equals("compact-terrain-strip-v1"),cap,capFile,group));}
        var ownership=new HashMap<TileKey,TileKey>();
        for(var cpu:decoded){var physical=new TileKey(cpu.x,cpu.y,cpu.z,cpu.width,cpu.layer);var prior=ownership.putIfAbsent(physical,cpu.group);if(prior!=null&&!prior.equals(cpu.group))throw new IllegalArgumentException("Inconsistent joined ownership");}
        // A partially declared group must never hide a missing member/level.
        for(var cpu:decoded)if(cpu.width==32&&cpu.group.width==64){for(int dz=0;dz<64;dz+=32)for(int dx=0;dx<64;dx+=32){var member=new TileKey(cpu.group.x+dx,cpu.y,cpu.group.z+dz,32,"terrain");if(!cpu.group.equals(ownership.get(member)))throw new IllegalArgumentException("Incomplete joined terrain group");}}
        int width=manifest.get("tile_width").getAsInt();if(width<4||width>64)throw new IllegalArgumentException("Invalid tile width");
        close();automaticLevels=level<0;linkedNative=manifest.has("linked_native")&&manifest.get("linked_native").getAsBoolean();
        try {
            shader=Shader.make().apply(properties::apply).define("FR_HYBRID_SURFACE")
                .add(ShaderType.VERTEX,"voxy:lod/mesh/terrain.vert").add(ShaderType.FRAGMENT,"voxy:lod/mesh/terrain.frag")
                .replace("// FRAGMENT_MATERIAL_SOURCE",primitiveMaterialSource()).compile();
            quadShader=Shader.make().apply(properties::apply).define("FR_HYBRID_SURFACE").define("FV_PACKED_QUADS")
                .add(ShaderType.VERTEX,"voxy:lod/mesh/terrain.vert").add(ShaderType.FRAGMENT,"voxy:lod/mesh/terrain.frag")
                .replace("// FRAGMENT_MATERIAL_SOURCE",primitiveMaterialSource()).compile();
            if(linkedNative)snowShader=Shader.make().apply(properties::apply).define("FR_HYBRID_SURFACE").define("FV_PACKED_QUADS").define("FV_NATIVE_SNOW")
                .add(ShaderType.VERTEX,"voxy:lod/mesh/terrain.vert").add(ShaderType.FRAGMENT,"voxy:lod/mesh/terrain.frag")
                .replace("// FRAGMENT_MATERIAL_SOURCE",primitiveMaterialSource()).compile();
            compactShader=Shader.make().apply(properties::apply).define("FR_HYBRID_SURFACE").define("FV_COMPACT_TERRAIN")
                .add(ShaderType.VERTEX,"voxy:lod/mesh/terrain.vert").add(ShaderType.FRAGMENT,"voxy:lod/mesh/terrain.frag")
                .replace("// FRAGMENT_MATERIAL_SOURCE",primitiveMaterialSource()).compile();
            collarShader=Shader.make().apply(properties::apply).define("FR_HYBRID_SURFACE").define("FV_NATIVE_COLLAR")
                .add(ShaderType.VERTEX,"voxy:lod/mesh/terrain.vert").add(ShaderType.FRAGMENT,"voxy:lod/mesh/terrain.frag")
                .replace("// FRAGMENT_MATERIAL_SOURCE",primitiveMaterialSource()).compile();
            for(Shader program:new Shader[]{shader,quadShader,compactShader,collarShader,snowShader})if(program!=null)uniforms.put(program.id(),new Uniforms(
                glGetUniformLocation(program.id(),"u_MVP"),glGetUniformLocation(program.id(),"u_CameraPhase"),glGetUniformLocation(program.id(),"u_Origin"),glGetUniformLocation(program.id(),"u_AttributeBits"),glGetUniformLocation(program.id(),"u_AttributeOffset"),glGetUniformLocation(program.id(),"u_SurfaceMacroWidth"),glGetUniformLocation(program.id(),"u_LodEdge"),glGetUniformLocation(program.id(),"u_NativeExplicitFine"),glGetUniformLocation(program.id(),"u_NativeSnowPage")));
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
            for(var cpu:decoded){if(cpu.mesh!=null)upload(cpu.mesh,cpu.x,cpu.y,cpu.z,entries.size(),cpu.mask,cpu.width,cpu.compact);else uploadQuads(cpu.quads,cpu.x,cpu.y,cpu.z,entries.size(),cpu.mask,cpu.width);if(gpuBytes>192L*1024*1024)throw new IllegalArgumentException("Resident GPU budget exceeded");
                Page page=pages.getLast();
                if(cpu.layer.equals("terrain")&&cpu.quads!=null&&(cpu.mask&1)!=0)nativeJoin.register(cpu.quads,cpu.x,cpu.y,cpu.z);
                if(cpu.cap!=null){
                    CapBuffer buffer=capBuffers.get(cpu.capFile);
                    if(buffer==null){int handle=glCreateBuffers();buffer=new CapBuffer(handle,cpu.cap.macroWidth());capBuffers.put(cpu.capFile,buffer);glNamedBufferData(handle,cpu.cap.words(),GL_STATIC_DRAW);gpuBytes+=cpu.cap.gpuBytes();}
                    pageCaps.put(page,buffer);if(gpuBytes>192L*1024*1024)throw new IllegalArgumentException("Resident GPU budget exceeded");
                }
                Tile tile=tiles.computeIfAbsent(cpu.group,key->new Tile());
                if(cpu.layer.equals("terrain"))terrainOwners.put(new TileKey(cpu.x,cpu.y,cpu.z,cpu.width,"terrain"),tile);
                tile.availableMask|=cpu.mask;
                pageTiles.put(page,tile);
                if(page.count>0){extend(tile.bounds,page.bounds[0]+cpu.x-cpu.group.x,page.bounds[1],page.bounds[2]+cpu.z-cpu.group.z);extend(tile.bounds,page.bounds[3]+cpu.x-cpu.group.x,page.bounds[4],page.bounds[5]+cpu.z-cpu.group.z);}
                for(int l=0;l<3;l++)if((cpu.mask&(1<<l))!=0)tile.errors[l]=Math.max(tile.errors[l],cpu.errors[l]);
            }
            Path collarIndex=root.resolve("native-collars.json");
            if(automaticLevels&&Files.isRegularFile(collarIndex)){
                var sourceHashes=new HashMap<String,String>();
                for(var row:manifest.getAsJsonArray("pages")){var value=row.getAsJsonObject();if(value.get("level").getAsInt()==0&&value.has("layer")&&value.get("layer").getAsString().equals("terrain"))sourceHashes.put(value.getAsJsonArray("tile").toString(),(value.has("native_source_sha256")?value.get("native_source_sha256"):value.get("sha256")).getAsString());}
                var assets=JsonParser.parseString(readSmall(collarIndex,1<<20)).getAsJsonArray();
                if(assets.size()>2048)throw new IllegalArgumentException("Native collar count budget");long totalCollars=0;
                for(var element:assets){var asset=element.getAsJsonObject();String file=asset.get("file").getAsString();
                    if(!file.matches("collar_[0-9_-]+[.]mesh"))throw new IllegalArgumentException("Native collar filename");
                    Path path=checked(root.resolve(file),4<<20);byte[] bytes=Files.readAllBytes(path);totalCollars+=bytes.length;
                    if(totalCollars>(linkedNative?64L:32L)<<20)throw new IllegalArgumentException("Native collar disk budget");verify(bytes,asset.get("sha256").getAsString());
                    var data=MeshPage.read(path);var key=new TileKey(data.originX(),data.originY(),data.originZ(),32,"terrain");
                    if(!asset.has("source_sha256")||!asset.get("source_sha256").getAsString().equals(sourceHashes.get("["+key.x+","+key.z+"]")))throw new IllegalArgumentException("Native collar source hash");
                    if(!terrainOwners.containsKey(key)||collarAssets.containsKey(key))throw new IllegalArgumentException("Native collar ownership");
                    float[] fine=null;
                    if(asset.has("fine_file")){
                        String fineFile=asset.get("fine_file").getAsString(),sourceFile=asset.get("source_file").getAsString();
                        if(!fineFile.matches("linked_[0-9_-]+[.]fine")||!sourceFile.matches("[a-zA-Z0-9_.-]+"))throw new IllegalArgumentException("Linked endpoint filename");
                        Path finePath=checked(root.resolve(fineFile),1<<20);byte[] fineBytes=Files.readAllBytes(finePath);verify(fineBytes,asset.get("fine_sha256").getAsString());totalCollars+=fineBytes.length;
                        if(totalCollars>64L<<20)throw new IllegalArgumentException("Linked endpoint disk budget");fine=NativeFinePositions.read(finePath,data.mesh().vertices());
                        byte[] sourceBytes=Files.readAllBytes(checked(root.resolve(sourceFile),8<<20));verify(sourceBytes,asset.get("source_sha256").getAsString());var source=PackedQuads.read(root.resolve(sourceFile));nativeJoin.register(source.quads(),source.originX(),source.originY(),source.originZ());
                    }else for(float v:data.mesh().positions())if(Math.abs(v-Math.round(v))>.45f)throw new IllegalArgumentException("Native collar fine endpoint");
                    collarAssets.put(key,new CollarAsset(data.mesh(),fine,entries.size()));
                }
            }
            if(!quadSubmissions.isEmpty()) {
                commandStaging=MemoryUtil.memAllocInt(quadSubmissions.size()*QuadFaceBuckets.BUCKETS*QuadFaceBuckets.COMMAND_INTS);
                commandBuffer=glCreateBuffers();glNamedBufferData(commandBuffer,commandStaging.capacity()*4L,GL_DYNAMIC_DRAW);
                gpuBytes+=commandStaging.capacity()*4L;
                if(gpuBytes>192L*1024*1024)throw new IllegalArgumentException("Resident GPU budget exceeded");
            }
            singleSided=null;
            if(automaticLevels&&tiles.values().stream().anyMatch(t->t.availableMask!=7))throw new IllegalArgumentException("Physical tile needs all levels");
            loadedRoot=root;active=true;return status();
        }catch(Exception error){close();throw error;}
    }
    private static double pageError(JsonObject page){
        double error=page.has("source_shape_error")?page.get("source_shape_error").getAsDouble():0;
        if(!Double.isFinite(error)||error<0||error>1)throw new IllegalArgumentException("Invalid source shape error");
        if(page.get("representation").getAsString().equals("mesh")){
            var audit=page.getAsJsonObject("candidate_error");if(audit==null||!audit.get("within_limit").getAsBoolean())throw new IllegalArgumentException("Mesh requires certified error");
            error=audit.get("upper_bound_blocks").getAsDouble();
            if(!Double.isFinite(error)||error<0||error>(page.has("linked_parent")?new double[]{1.5,2.,3.}[page.get("level").getAsInt()]:TileBake.errorLimit(page.get("level").getAsInt())))throw new IllegalArgumentException("Invalid certified error");
        }
        return error;
    }
    private static Path checked(Path path,long limit)throws Exception{if(!Files.isRegularFile(path)||Files.size(path)>limit)throw new java.io.IOException("Missing/oversized page "+path.getFileName());return path;}
    private static String readSmall(Path path,long limit)throws Exception{return Files.readString(checked(path,limit));}
    private static void verify(byte[] data,String hash)throws Exception{if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).equals(hash))throw new java.io.IOException("Page checksum mismatch");}
    private static <T extends Comparable<T>> BlockState property(BlockState state,Property<T> p,String value){return state.setValue(p,p.getValue(value).orElseThrow());}
    private static BlockState state(String text){int bracket=text.indexOf('[');var id=ResourceLocation.parse(bracket<0?text:text.substring(0,bracket));var block=BuiltInRegistries.BLOCK.getOptional(id).orElseThrow();var state=block.defaultBlockState();if(bracket>=0)for(String pair:text.substring(bracket+1,text.length()-1).split(",")){String[] kv=pair.split("=");var p=block.getStateDefinition().getProperty(kv[0]);if(p==null)throw new IllegalArgumentException("Unknown property");state=property(state,p,kv[1]);}return state;}
    private static float[] materials(JsonArray palette){var mc=Minecraft.getInstance();float[] result=new float[palette.size()*6*8];
        for(int i=0;i<palette.size();i++){var entry=palette.get(i).getAsJsonObject();var state=state(entry.get("state").getAsString());var model=mc.getBlockRenderer().getBlockModel(state);
            var biome=mc.level.registryAccess().registryOrThrow(Registries.BIOME).get(ResourceLocation.parse(entry.get("biome").getAsString()));if(biome==null)throw new IllegalArgumentException("Unknown biome");
            String name=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();float material=name.equals("snow")?6+TerrainShapes.height(entry.get("state").getAsString())/8f:name.equals("water")?5:name.endsWith("leaves")?2:name.equals("grass_block")||name.equals("dirt")?3:0;
            for(int face=0;face<6;face++){Direction direction=new Direction[]{Direction.WEST,Direction.EAST,Direction.DOWN,Direction.UP,Direction.NORTH,Direction.SOUTH}[face];var quads=model.getQuads(state,direction,RandomSource.create(0));var sprite=quads.isEmpty()?model.getParticleIcon():quads.getFirst().getSprite();boolean tinted=!quads.isEmpty()&&quads.getFirst().isTinted();int tint=tinted?ModelFactory.captureSurfaceTint(state,biome):0xffffff;int o=(i*6+face)*8;
                result[o]=sprite.getU0();result[o+1]=sprite.getV0();result[o+2]=sprite.getU1();result[o+3]=sprite.getV1();result[o+4]=((tint>>16)&255)/255f;result[o+5]=((tint>>8)&255)/255f;result[o+6]=(tint&255)/255f;result[o+7]=material;}}
        return result;}
    /** Adapt existing receiver inputs to explicit per-primitive inputs, without double lightmap tint. */
    public static String primitiveMaterialSource(){return FragmentAtmosphereCompat.materialSource().replaceAll("(?m)^in (vec3|float) (fr_RelativePosition|fr_SkyLight|fr_BlockLight|fr_BaseLight|fr_CanopyNormal|fv_SurfaceNormal);", "$1 $2;");}
    private void upload(SurfaceMesh mesh,int x,int y,int z,int paletteSize,int mask,int width,boolean compact){
        if(compact){uploadCompact(mesh,x,y,z,paletteSize,mask,width);return;}
        int[] attributes=mesh.faceData();for(int attribute:attributes)if((attribute>>>11)>=paletteSize||((attribute>>>8)&7)>5)throw new IllegalArgumentException("Invalid face material");
        long[] positions=new long[mesh.vertices()];for(int i=0;i<positions.length;i++)positions[i]=MeshGpuLayout.pack(mesh.positions()[i*3],mesh.positions()[i*3+1],mesh.positions()[i*3+2],mesh.normals()[i*3],mesh.normals()[i*3+1],mesh.normals()[i*3+2]);
        int vao=glCreateVertexArrays(),vbo=glCreateBuffers(),ebo=glCreateBuffers(),attr=glCreateBuffers();boolean shortIndex=mesh.vertices()<=65536;
        pages.add(new Page(vao,vbo,ebo,attr,shortIndex?GL_UNSIGNED_SHORT:GL_UNSIGNED_INT,mesh.indices().length,false,x,y,z,bounds(mesh),mask,width,false,0,0,mesh.triangles()));
        glNamedBufferData(vbo,positions,GL_STATIC_DRAW);glNamedBufferData(attr,attributes,GL_STATIC_DRAW);
        if(shortIndex){short[] ix=new short[mesh.indices().length];for(int j=0;j<ix.length;j++)ix[j]=(short)mesh.indices()[j];glNamedBufferData(ebo,ix,GL_STATIC_DRAW);}else glNamedBufferData(ebo,mesh.indices(),GL_STATIC_DRAW);
        glVertexArrayVertexBuffer(vao,0,vbo,0,8);glVertexArrayElementBuffer(vao,ebo);glEnableVertexArrayAttrib(vao,0);glVertexArrayAttribBinding(vao,0,0);glVertexArrayAttribIFormat(vao,0,2,GL_UNSIGNED_INT,0);
        gpuBytes+=positions.length*8L+attributes.length*4L+mesh.indices().length*(shortIndex?2L:4L);triangles+=mesh.triangles();
    }
    private void uploadCompact(SurfaceMesh mesh,int x,int y,int z,int paletteSize,int mask,int width){
        for(int attribute:mesh.faceData())if((attribute>>>11)>=paletteSize||((attribute>>>8)&7)>5)throw new IllegalArgumentException("Invalid compact material");
        var layout=CompactTerrainLayout.build(mesh);int vao=glCreateVertexArrays(),vbo=glCreateBuffers(),ebo=glCreateBuffers(),attr=glCreateBuffers();
        int type=layout.indexBytes()==2?GL_UNSIGNED_SHORT:GL_UNSIGNED_INT;
        pages.add(new Page(vao,vbo,ebo,attr,type,layout.indices().length,false,x,y,z,bounds(mesh),mask,width,true,layout.attributeBits(),layout.dictionarySize(),mesh.triangles()));
        glNamedBufferData(vbo,layout.vertices(),GL_STATIC_DRAW);glNamedBufferData(attr,layout.attributes(),GL_STATIC_DRAW);
        if(layout.indexBytes()==2){short[] indices=new short[layout.indices().length];for(int i=0;i<indices.length;i++)indices[i]=(short)layout.indices()[i];glNamedBufferData(ebo,indices,GL_STATIC_DRAW);}else glNamedBufferData(ebo,layout.indices(),GL_STATIC_DRAW);
        glVertexArrayVertexBuffer(vao,0,vbo,0,6);glVertexArrayElementBuffer(vao,ebo);glEnableVertexArrayAttrib(vao,0);glVertexArrayAttribBinding(vao,0,0);glVertexArrayAttribIFormat(vao,0,3,GL_UNSIGNED_SHORT,0);
        gpuBytes+=layout.gpuBytes();triangles+=mesh.triangles();
    }
    private void uploadQuads(PackedQuads quads,int x,int y,int z,int paletteSize,int mask,int width){
        for(long q:quads.data())if(PackedQuads.material(q)>=paletteSize)throw new IllegalArgumentException("Invalid quad material");
        var partition=QuadFaceBuckets.partition(quads,singleSided);quads=partition.quads();
        int vao=glCreateVertexArrays(),data=glCreateBuffers(),index=glCreateBuffers();pages.add(new Page(vao,data,index,0,GL_UNSIGNED_SHORT,quads.data().length,true,x,y,z,bounds(quads),mask,width,false,0,0,quads.triangles()));
        quadSubmissions.put(pages.getLast(),new QuadSubmission(quadSubmissions.size()*(long)QuadFaceBuckets.COMMAND_BYTES,partition.layout()));
        glNamedBufferData(data,quads.data(),GL_STATIC_DRAW);glNamedBufferData(index,new short[]{0,1,2,0,2,3},GL_STATIC_DRAW);glVertexArrayElementBuffer(vao,index);
        // Let vertex fetch apply baseInstance, with the same eight bytes per quad.
        // No shader-side SSBO lookup via gl_BaseInstance is needed for an instance.
        glVertexArrayVertexBuffer(vao,0,data,0,8);glEnableVertexArrayAttrib(vao,0);glVertexArrayAttribBinding(vao,0,0);
        glVertexArrayAttribIFormat(vao,0,2,GL_UNSIGNED_INT,0);glVertexArrayBindingDivisor(vao,0,1);
        gpuBytes+=quads.payloadBytes()+12;triangles+=quads.triangles();
    }
    private static float[] emptyBounds(){return new float[]{Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};}
    private static void extend(float[] b,float x,float y,float z){b[0]=Math.min(b[0],x);b[1]=Math.min(b[1],y);b[2]=Math.min(b[2],z);b[3]=Math.max(b[3],x);b[4]=Math.max(b[4],y);b[5]=Math.max(b[5],z);}
    private static float[] bounds(SurfaceMesh mesh){float[] b=emptyBounds();for(int i=0;i<mesh.positions().length;i+=3)extend(b,mesh.positions()[i],mesh.positions()[i+1],mesh.positions()[i+2]);return b;}
    private static float[] bounds(PackedQuads quads){float[] b=emptyBounds();for(long q:quads.data()){int x=PackedQuads.x(q),y=PackedQuads.y(q),z=PackedQuads.z(q),f=PackedQuads.face(q),u=PackedQuads.u(q),v=PackedQuads.v(q);extend(b,x,y,z);extend(b,x+(f<2?1:u),y+(f<2?v:f<4?1:v),z+(f<2?u:f<4?v:1));}return b;}
    private void selectLevels(Viewport<?> view) {
        Arrays.fill(terrainLevels,0);
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
        // Retained near snow covers must sit on exact ground. Once their screen-space
        // thickness is subpixel, the original-column cap material replaces that shell.
        for(var entry:tiles.entrySet())if(!linkedNative&&entry.getKey().layer.equals("snow")&&entry.getValue().selected==0){
            TileKey key=entry.getKey();Tile ground=terrainOwners.get(new TileKey(key.x,key.y,key.z,key.width,"terrain"));if(ground!=null)ground.selected=0;
        }
        for(var entry:tiles.entrySet()){
            TileKey key=entry.getKey();if(nativeJoinEnabled&&!collarAssets.isEmpty()&&(key.layer.equals("terrain")||key.layer.equals("snow"))&&nativeJoin.affects(key.x,key.z,key.width))entry.getValue().selected=0;
        }
        for(var entry:tiles.entrySet())if(entry.getKey().layer.equals("terrain"))terrainLevels[entry.getValue().selected]++;
    }
    /** Dense fine topology is a boundary cache, not another resident world mesh.
     * CPU endpoints were verified when the package was loaded; updates do no disk IO. */
    private void updateCollars(){
        var remove=new ArrayList<TileKey>();
        for(var key:collars.keySet())if(!nativeJoinEnabled||!nativeJoin.affects(key.x,key.z,key.width))remove.add(key);
        for(var key:remove){Page page=collars.remove(key);Integer fine=fineBuffers.remove(page);if(fine!=null){glDeleteBuffers(fine);gpuBytes-=collarAssets.get(key).fine.length/3*8L;}
            glDeleteVertexArrays(page.vao);glDeleteBuffers(page.vertex);glDeleteBuffers(page.index);glDeleteBuffers(page.attributes);
            var mesh=collarAssets.get(key).mesh;gpuBytes-=mesh.vertices()*8L+mesh.triangles()*10L;triangles-=mesh.triangles();
        }
        if(!nativeJoinEnabled)return;
        for(var entry:collarAssets.entrySet()){
            TileKey key=entry.getKey();if(collars.containsKey(key)||!nativeJoin.affects(key.x,key.z,key.width))continue;
            var asset=entry.getValue();long bytes=asset.mesh.vertices()*8L+asset.mesh.triangles()*10L+(asset.fine==null?0:asset.fine.length/3*8L);
            if(gpuBytes+bytes>192L*1024*1024)throw new IllegalStateException("Native boundary cache GPU budget");
            upload(asset.mesh,key.x,key.y,key.z,asset.paletteSize,1,32,false);Page page=pages.removeLast();
            if(asset.fine!=null){
                long[] packed=new long[asset.mesh.vertices()];for(int i=0;i<packed.length;i++){packed[i]=MeshGpuLayout.pack(asset.fine[i*3],asset.fine[i*3+1],asset.fine[i*3+2],0,1,0);extend(page.bounds,asset.fine[i*3],asset.fine[i*3+1],asset.fine[i*3+2]);}
                int buffer=glCreateBuffers();glNamedBufferData(buffer,packed,GL_STATIC_DRAW);glVertexArrayVertexBuffer(page.vao,1,buffer,0,8);glEnableVertexArrayAttrib(page.vao,1);glVertexArrayAttribBinding(page.vao,1,1);glVertexArrayAttribIFormat(page.vao,1,2,GL_UNSIGNED_INT,0);fineBuffers.put(page,buffer);gpuBytes+=packed.length*8L;
            }
            collars.put(key,page);
        }
    }
    public void render(Viewport<?> view){if(!active)return;drawn=culled=0;drawnMeshPages=drawnQuadPages=0;
        drawnCollars=0;if(automaticLevels){nativeJoin.update();updateCollars();selectLevels(view);}
        if(commandStaging!=null){
            commandStaging.clear();
            for(var entry:quadSubmissions.entrySet()) {
                Page page=entry.getKey();
                QuadSubmission submission=entry.getValue();
                if(submissionMode==3){
                    int start=commandStaging.position();long count=0;var layout=submission.layout;
                    for(int bucket=0;bucket<6;bucket++)if(layout.counts()[bucket]>0&&layout.visible(bucket,view.cameraX-page.x,view.cameraY-page.y,view.cameraZ-page.z)){
                        commandStaging.put(6).put(layout.counts()[bucket]).put(0).put(0).put(layout.starts()[bucket]);count+=layout.counts()[bucket]*2L;
                    }
                    submission.commandCount=(commandStaging.position()-start)/5;
                    while(commandStaging.position()<start+35)commandStaging.put(0);
                    submission.submitted=count+layout.counts()[6]*2L;
                }else submission.submitted=submission.layout.commands(commandStaging,view.cameraX-page.x,view.cameraY-page.y,view.cameraZ-page.z);
            }
            commandStaging.flip();glNamedBufferSubData(commandBuffer,0,commandStaging);
        }
        boolean previousRestart=glIsEnabled(GL_PRIMITIVE_RESTART_FIXED_INDEX);glEnable(GL_PRIMITIVE_RESTART_FIXED_INDEX);
        int previousIndirect=glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING),previousCap=glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,15);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER,commandBuffer);
        glBindTextureUnit(0,Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId());glBindSampler(0,0);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,14,table);
        glEnable(GL_DEPTH_TEST);glDepthFunc(properties.closerEqualDepthCompare());glDepthMask(true);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);
        float renderEnd=Math.max(32,view.fogParameters.renderEnd());
        nativeJoin.saveBindings();Shader bound=null;
        try{for(Page original:pages){
            if(automaticLevels&&(original.levelMask&(1<<pageTiles.get(original).selected))==0)continue;
            Page page=original;boolean collar=false;
            if(nativeJoinEnabled&&automaticLevels&&(original.levelMask&1)!=0){
                var key=new TileKey(original.x,original.y,original.z,original.width,"terrain");
                Page candidate=collars.get(key);
                // Only the physical terrain owner can substitute this sidecar, never detail/cave pages.
                if(candidate!=null&&terrainOwners.get(key)==pageTiles.get(original)&&nativeJoin.affects(key.x,key.z,key.width)){page=candidate;collar=true;}
            }
            float x=(float)(page.x-view.cameraX),y=(float)(page.y-view.cameraY),z=(float)(page.z-view.cameraZ);float[] b=page.bounds;float pad=collar?.5f:.01f;
            if(page.count==0||!view.frustum.testAab(x+b[0]-pad,y+b[1]-pad,z+b[2]-pad,x+b[3]+pad,y+b[4]+pad,z+b[5]+pad))continue;
            double dx=Math.max(0,Math.max(x+b[0],-x-b[3])),dz=Math.max(0,Math.max(z+b[2],-z-b[5]));
            if(dx*dx+dz*dz>(double)renderEnd*renderEnd)continue;
            if(page.quads&&quadSubmissions.get(page).submitted==0){culled+=page.count*2L;continue;}
            boolean fadingSnow=linkedNative&&pageTiles.get(original)==tiles.get(new TileKey(original.x,original.y,original.z,original.width,"snow"));
            Shader program=collar?collarShader:page.quads?(fadingSnow?snowShader:quadShader):page.compact?compactShader:shader;
            if(program!=bound){
                if(bound!=null){FragmentAtmosphereCompat.releaseMaterial();bound=null;}
                program.bind();
                try(var stack=org.lwjgl.system.MemoryStack.stackPush()){glUniformMatrix4fv(uniforms.get(program.id()).mvp(),false,view.MVP.get(stack.mallocFloat(16)));}
                glUniform3f(uniforms.get(program.id()).phase(),(float)(view.cameraX%1),(float)(view.cameraY%1),(float)(view.cameraZ%1));
                glUniform2f(uniforms.get(program.id()).lodEdge(),renderEnd*.80f,renderEnd);
                FragmentAtmosphereCompat.bindMaterial(program.id());bound=program;if(collar||(linkedNative&&program==snowShader))nativeJoin.bind(program.id(),view.cameraX,view.cameraZ);
            }
            glUniform3f(uniforms.get(program.id()).origin(),x,y,z);
            CapBuffer cap=collar?(fineBuffers.containsKey(page)?pageCaps.get(original):null):pageCaps.get(page);glUniform1i(uniforms.get(program.id()).surfaceMacroWidth(),cap==null?0:cap.macroWidth);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,15,cap==null?table:cap.handle);
            if(page.compact){glUniform1i(uniforms.get(program.id()).attributeBits(),page.attributeBits);glUniform1i(uniforms.get(program.id()).attributeOffset(),page.dictionarySize);}
            glBindVertexArray(page.vao);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,13,page.quads?page.vertex:page.attributes);
            if(collar)glUniform1i(uniforms.get(program.id()).explicitFine(),fineBuffers.containsKey(page)?1:0);
            if(linkedNative&&page.quads)glUniform1i(uniforms.get(program.id()).snowPage(),fadingSnow?1:0);
            if(fadingSnow){glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);glDepthMask(false);}else{glDisable(GL_BLEND);glDepthMask(true);}
            if(page.compact||page.quads)glEnable(GL_PRIMITIVE_RESTART_FIXED_INDEX);else glDisable(GL_PRIMITIVE_RESTART_FIXED_INDEX);
            if(page.quads){
                drawnQuadPages++;
                QuadSubmission submission=quadSubmissions.get(page);
                // Compact only nonempty opaque commands. Two-sided cutouts/fluid
                // models use their own direct instance range: mixed MDI still
                // loses this range in the real client, despite EGL-only tests.
                // Pure foliage pages retain one draw; mixed pages need at most two.
                var layout=submission.layout;
                if(submissionMode==1){
                    for(int bucket=0;bucket<QuadFaceBuckets.BUCKETS;bucket++)
                        if(layout.counts()[bucket]>0&&layout.visible(bucket,view.cameraX-page.x,view.cameraY-page.y,view.cameraZ-page.z))
                            glDrawElementsInstancedBaseInstance(GL_TRIANGLES,6,GL_UNSIGNED_SHORT,0L,layout.counts()[bucket],layout.starts()[bucket]);
                }else{
                    int commands=submissionMode==3?submission.commandCount:submissionMode==2?7:6;
                    if(commands>0)glMultiDrawElementsIndirect(GL_TRIANGLES,GL_UNSIGNED_SHORT,submission.offset,commands,0);
                    if((submissionMode==0||submissionMode==3)&&layout.counts()[6]>0)glDrawElementsInstancedBaseInstance(GL_TRIANGLES,6,GL_UNSIGNED_SHORT,0L,layout.counts()[6],layout.starts()[6]);
                }
                drawn+=submission.submitted;culled+=page.count*2L-submission.submitted;
            }else{
                if(collar)drawnCollars++;drawnMeshPages++;glDrawElements(page.compact?GL_TRIANGLE_STRIP:GL_TRIANGLES,page.count,page.indexType,0L);drawn+=page.primitiveCount;
            }
        }}finally{
            nativeJoin.restoreBindings();glBindBuffer(GL_DRAW_INDIRECT_BUFFER,previousIndirect);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,15,previousCap);if(previousRestart)glEnable(GL_PRIMITIVE_RESTART_FIXED_INDEX);else glDisable(GL_PRIMITIVE_RESTART_FIXED_INDEX);glDisable(GL_BLEND);glDepthMask(true);
            if(bound!=null)FragmentAtmosphereCompat.releaseMaterial();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,14,0);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,13,0);glBindVertexArray(0);glBindTextureUnit(0,0);glEnable(GL_CULL_FACE);
        }
    }
    @Override public void close(){loadedRoot=null;active=false;nativeJoin.close();for(int buffer:fineBuffers.values())glDeleteBuffers(buffer);fineBuffers.clear();for(Page p:collars.values()){glDeleteVertexArrays(p.vao);glDeleteBuffers(p.vertex);glDeleteBuffers(p.index);glDeleteBuffers(p.attributes);}collars.clear();collarAssets.clear();for(Page p:pages){glDeleteVertexArrays(p.vao);glDeleteBuffers(p.vertex);glDeleteBuffers(p.index);glDeleteBuffers(p.attributes);}pages.clear();quadSubmissions.clear();singleSided=null;
        if(commandStaging!=null){MemoryUtil.memFree(commandStaging);commandStaging=null;}
        if(commandBuffer!=0){glDeleteBuffers(commandBuffer);commandBuffer=0;}
        pageTiles.clear();terrainOwners.clear();pageCaps.clear();for(CapBuffer cap:capBuffers.values())glDeleteBuffers(cap.handle);capBuffers.clear();tiles.clear();uniforms.clear();if(table!=0)glDeleteBuffers(table);table=0;if(shader!=null)shader.free();shader=null;if(quadShader!=null)quadShader.free();quadShader=null;if(compactShader!=null)compactShader.free();compactShader=null;if(collarShader!=null)collarShader.free();collarShader=null;if(snowShader!=null)snowShader.free();snowShader=null;gpuBytes=triangles=drawn=culled=0;drawnMeshPages=drawnQuadPages=0;Arrays.fill(terrainLevels,0);}
}
