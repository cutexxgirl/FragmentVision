package me.cortex.voxy.client.core.rendering.hybrid;

import com.mojang.blaze3d.platform.NativeImage;
import me.cortex.voxy.client.compat.FragmentAtmosphereCompat;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.opengl.GL45C.*;

/** Opt-in, bounded volumetric-terrain experiment. Off until explicitly built and enabled. */
public final class HybridTerrain implements AutoCloseable {
    private static final int SIZE=1088, STEP=2, TEXTURE_SIZE=32;
    private final WorldEngine world;
    private final RenderProperties properties;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Vision hybrid surface WIP");t.setDaemon(true);return t;});
    private final AtomicLong revision=new AtomicLong();
    private CompletableFuture<Result> pending;
    private volatile Request request;
    private boolean enabled,closed;
    private long nextBuildNanos;
    private String status="not built (WIP is off)";
    private Shader shader;
    private int vao,vbo,ibo,morphBuffer,morphTexture,coverage,textures,vertices;
    private Result active;
    private record State(int layer, boolean skip, float topOffset) {}
    private record Catalog(State[] states, byte[] pixels, float[] material, int[][] tint) {}
    private record Request(int x,int z,int minY,int maxY,int nativeRadius,double eyeX,double eyeZ,Catalog catalog,long revision) {}
    private record Result(Request request,HybridVoxelMesh.Mesh mesh,long millis,int sampled,int missing) {}
    public HybridTerrain(WorldEngine world,RenderProperties properties){this.world=world;this.properties=properties;}

    public String build() {
        if(closed)return "renderer closed";
        if(pending!=null)return "surface build already running";
        nextBuildNanos=System.nanoTime()+5_000_000_000L;
        if(!FragmentAtmosphereCompat.hasMaterial())return "WIP requires Fragment Render's shared lighting material";
        var mc=Minecraft.getInstance();var pos=mc.gameRenderer.getMainCamera().getPosition();
        try {
            Catalog catalog=catalog();
            var next=new Request((Math.floorDiv((int)Math.floor(pos.x),32)<<5)-SIZE/2,
                    (Math.floorDiv((int)Math.floor(pos.z),32)<<5)-SIZE/2,
                    mc.level.getMinBuildHeight(),mc.level.getMaxBuildHeight(),mc.options.getEffectiveRenderDistance()*16,pos.x,pos.z,catalog,revision.get());
            request=next;nextBuildNanos=System.nanoTime()+5_000_000_000L;
            pending=CompletableFuture.supplyAsync(()->{
                // Acquire inside the task: a queued task removed by shutdownNow must not leak a reference.
                world.acquireRef();
                try{return sample(next);}finally{world.releaseRef();}
            },worker);
            status="building volumetric terrain with continuous 2/4/8 morph targets";
            return status;
        } catch(Exception e){Logger.error("Hybrid surface preparation failed; keeping voxel terrain",e);return status="build failed: "+e.getMessage();}
    }
    public String enabled(boolean value){enabled=value;return status();}
    public String status(){return "Hybrid WIP "+(enabled?"ON: ":"OFF: ")+status;}

    public void changed(WorldSection section,int flags) {
        Request r=request;
        if(r==null || (flags&WorldEngine.UPDATE_TYPE_BLOCK_BIT)==0 || section.lvl!=0)return;
        int x=section.x<<5,z=section.z<<5;
        // Native chunks (including lighting-only ingestion) are outside the replacement area.
        // The pilot is invalidated if the camera subsequently moves away from this build centre.
        if(Math.max(Math.abs(x+16-r.eyeX),Math.abs(z+16-r.eyeZ))<=r.nativeRadius+32)return;
        if(x<=r.x+SIZE&&x+32>=r.x&&z<=r.z+SIZE&&z+32>=r.z)revision.incrementAndGet();
    }
    /** Called once at the start of a frame so masks cannot change between temporal/opaque draws. */
    public void prepare() {
        if(closed)return;
        if(active!=null){
            var eye=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            Request r=active.request;
            if(Minecraft.getInstance().options.getEffectiveRenderDistance()*16!=r.nativeRadius){
                active=null;status="render distance changed; rebuilding volume";
            }else if(enabled&&pending==null&&System.nanoTime()>nextBuildNanos
                    &&Math.max(Math.abs(eye.x-r.eyeX),Math.abs(eye.z-r.eyeZ))>16){
                build(); // Keep the previous snapshot until its replacement is ready.
            }
        }
        if(active!=null&&active.request.revision!=revision.get()) {
            active=null;status="cache changed; rebuilding volume";
        }
        if(enabled&&active==null&&pending==null&&request!=null&&System.nanoTime()>nextBuildNanos)build();
        if(pending==null||!pending.isDone())return;
        try {
            Result result=pending.join();
            if(result.request.revision!=revision.get()){status="cache changed during build; keeping voxel terrain, rebuild required";return;}
            if(result.mesh.vertices().length==0){active=null;enabled=false;status="no eligible cached terrain; voxel fallback";return;}
            upload(result);
            // GPU owns the mesh now. Retaining full CPU streams would double the next rebuild's peak heap.
            active=new Result(result.request,new HybridVoxelMesh.Mesh(new float[0],new int[0],new int[0],
                    new float[0],result.mesh.cells(),result.mesh.protectedCells(),result.mesh.sharedVertices()),
                    result.millis,result.sampled,result.missing);
            status=result.mesh.indices().length/3+" triangles, "+result.sampled+
                    " surface samples, "+result.mesh.sharedVertices()+" shared vertices, "+result.missing+" missing/protected, "+result.millis+"ms CPU build";
            Logger.info("Hybrid terrain WIP ready: "+status);
        }catch(Exception e){active=null;enabled=false;Logger.error("Hybrid terrain build/upload failed; keeping voxel terrain",e);status="build failed; voxel fallback";}
        finally{pending=null;}
    }
    public void bindMask(int program,Viewport<?> view) {
        boolean use=enabled&&active!=null&&!closed;
        glUniform1i(glGetUniformLocation(program,"fv_HybridEnabled"),use?1:0);
        glUniform1i(glGetUniformLocation(program,"fv_HybridCoverage"),6);
        if(!use)return;
        glBindTextureUnit(6,coverage);glBindSampler(6,0);
        glUniform4f(glGetUniformLocation(program,"fv_HybridGrid"),(float)(active.request.x-view.cameraX),
                (float)(active.request.z-view.cameraZ),STEP,active.mesh.cells());
        glUniform1f(glGetUniformLocation(program,"fv_HybridNear"),Minecraft.getInstance().options.getEffectiveRenderDistance()*16f+48);
    }
    public void render(Viewport<?> view) {
        if(!enabled||active==null||vertices==0)return;
        shader.bind();bindMask(shader.id(),view);
        glUniform1f(glGetUniformLocation(shader.id(),"fv_SurfacePixelScale"),view.height*.5f*Math.abs(view.projection.m11()));
        glBindTextureUnit(0,textures);glBindSampler(0,0);
        glBindTextureUnit(2,view.depthBoundingBuffer.getDepthTex().id);
        glUniform1i(glGetUniformLocation(shader.id(),"fv_SurfaceTextures"),0);
        glUniform3f(glGetUniformLocation(shader.id(),"fv_SurfaceOrigin"),(float)(active.request.x-view.cameraX),
                (float)-view.cameraY,(float)(active.request.z-view.cameraZ));
        try(var stack=MemoryStack.stackPush()){
            glUniformMatrix4fv(glGetUniformLocation(shader.id(),"fv_SurfaceMVP"),false,view.MVP.get(stack.mallocFloat(16)));
        }
        glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glEnable(GL_DEPTH_TEST);glDepthFunc(properties.closerEqualDepthCompare());glDepthMask(true);
        glBindVertexArray(vao);glBindTextureUnit(12,morphTexture);glBindSampler(12,0);
        FragmentAtmosphereCompat.bindMaterial(shader.id());
        try{glDrawElements(GL_TRIANGLES,vertices,GL_UNSIGNED_INT,0L);}
        finally{FragmentAtmosphereCompat.releaseMaterial();glBindVertexArray(0);glBindTextureUnit(12,0);glEnable(GL_CULL_FACE);glBindTextureUnit(0,0);glBindTextureUnit(6,0);}
    }
    private Catalog catalog() throws Exception {
        var mc=Minecraft.getInstance();var entries=world.getMapper().getStateEntries();
        var biomes=world.getMapper().getBiomeEntries();var registry=mc.level.registryAccess().registryOrThrow(Registries.BIOME);
        var layers=new LinkedHashMap<BlockState,Integer>();var states=new State[entries.length];
        for(var entry:entries){
            BlockState s=entry.state;
            if(HybridTerrainMaterials.natural(s)) {
                int layer=layers.computeIfAbsent(s.getBlock().defaultBlockState(),unused->layers.size());
                float offset=s.is(Blocks.SNOW)?s.getValue(SnowLayerBlock.LAYERS)/8f:1;
                states[entry.id]=new State(layer,false,offset);
            }else states[entry.id]=new State(-1,s.isAir()||s.getBlock() instanceof BushBlock||s.is(BlockTags.LEAVES)||s.is(BlockTags.LOGS),0);
        }
        byte[] pixels=new byte[Math.max(1,layers.size())*TEXTURE_SIZE*TEXTURE_SIZE*4];
        float[] material=new float[layers.size()];int[][] tint=new int[layers.size()][biomes.length];
        for(var entry:layers.entrySet()){
            var state=entry.getKey();int layer=entry.getValue();
            var model=mc.getBlockRenderer().getBlockModel(state);
            var quads=model.getQuads(state,Direction.UP,RandomSource.create(0));
            var sprite=quads.isEmpty()?model.getParticleIcon():quads.getFirst().getSprite();
            boolean tinted=!quads.isEmpty()&&quads.getFirst().isTinted();
            var name=sprite.contents().name();
            var imagePath=ResourceLocation.fromNamespaceAndPath(name.getNamespace(),"textures/"+name.getPath()+".png");
            try(var in=mc.getResourceManager().getResource(imagePath).orElseThrow().open();var image=NativeImage.read(in)){
                int width=image.getWidth(),height=Math.min(image.getHeight(),sprite.contents().height());
                for(int y=0;y<TEXTURE_SIZE;y++)for(int x=0;x<TEXTURE_SIZE;x++){
                    int rgba=image.getPixelRGBA(x*width/TEXTURE_SIZE,y*height/TEXTURE_SIZE);
                    int p=((layer*TEXTURE_SIZE+y)*TEXTURE_SIZE+x)*4;
                    pixels[p]=(byte)rgba;pixels[p+1]=(byte)(rgba>>8);pixels[p+2]=(byte)(rgba>>16);pixels[p+3]=(byte)(tinted?255:0);
                }
            }
            material[layer]=state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.DIRT)||state.is(Blocks.MOSS_BLOCK)?3:0;
            for(var biome:biomes){
                var b=registry.get(ResourceLocation.parse(biome.biome));
                tint[layer][biome.id]=b!=null?ModelFactory.captureSurfaceTint(Blocks.GRASS_BLOCK.defaultBlockState(),b):0xffffff;
            }
        }
        return new Catalog(states,pixels,material,tint);
    }
    private Result sample(Request r) {
        long start=System.nanoTime();int n=SIZE/STEP+1,total=n*n,sampled=0;
        byte[] volume=new byte[total*(r.maxY-r.minY)],volumeLight=new byte[total*(r.maxY-r.minY)];
        float[] heights=new float[total];int[] layers=new int[total],baseLayers=new int[total],tints=new int[total];int[] topY=new int[total],light=new int[total],aboveLight=new int[total];Arrays.fill(aboveLight,15);boolean[] substrate=new boolean[total];boolean[] valid=new boolean[total],done=new boolean[total];
        for(int tz=r.z>>5;tz<=(r.z+SIZE)>>5;tz++)for(int tx=r.x>>5;tx<=(r.x+SIZE)>>5;tx++){
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            for(int ty=(r.maxY-1)>>5;ty>=r.minY>>5;ty--){
                var section=world.acquireIfExists(0,tx,ty,tz);if(section==null)continue;
                long[] data;
                try{data=section.copyData();}finally{section.release();}
                for(int z=0;z<32;z+=STEP)for(int x=0;x<32;x+=STEP){
                    int gx=((tx<<5)+x-r.x)/STEP,gz=((tz<<5)+z-r.z)/STEP;
                    if(gx<0||gz<0||gx>=n||gz>=n)continue;
                    int p=gz*n+gx;
                    // Material columns retained only during CPU meshing; no 3D texture or runtime raymarch.
                    for(int y=0;y<32;y++){
                        int wy=(ty<<5)+y;if(wy<r.minY||wy>=r.maxY)continue;
                        long raw=data[WorldSection.getIndex(x,y,z)];
                        int id=Mapper.getBlockId(raw);volumeLight[(wy-r.minY)*total+p]=(byte)Mapper.getLightId(raw);
                        if(id>0&&id<r.catalog.states.length){int layer=r.catalog.states[id].layer;
                            if(layer>=0)volume[(wy-r.minY)*total+p]=(byte)(layer+1);
                            else if(!r.catalog.states[id].skip)volume[(wy-r.minY)*total+p]=(byte)255;
                        }
                    }
                    if(done[p]&&(!valid[p]||substrate[p]))continue;
                    for(int y=31;y>=0;y--){
                        long voxel=data[WorldSection.getIndex(x,y,z)];int block=Mapper.getBlockId(voxel);
                        if(block==0){aboveLight[p]=Mapper.getLightId(voxel);continue;}
                        if(block>=r.catalog.states.length){done[p]=true;break;}
                        State state=r.catalog.states[block];if(state.skip){aboveLight[p]=Mapper.getLightId(voxel);continue;}
                        if(done[p]){
                            if(state.layer<0){substrate[p]=true;break;}
                            // Preserve the material below the covering. Never paint snow down rock walls.
                            if(((ty<<5)+y)<=topY[p]-3){baseLayers[p]=state.layer;substrate[p]=true;break;}
                            continue;
                        }
                        done[p]=true;
                        if(state.layer>=0){
                            heights[p]=(ty<<5)+y+state.topOffset;layers[p]=state.layer;
                            int biome=Mapper.getBiomeId(voxel);
                            int[] tint=r.catalog.tint[state.layer];tints[p]=biome<tint.length?tint[biome]:0xffffff;
                            light[p]=aboveLight[p];valid[p]=true;sampled++;topY[p]=(ty<<5)+y;baseLayers[p]=state.layer;
                            continue;
                        }
                        break;
                    }
                }
            }
        }
        // Carve out a native-chunk safety region before meshing; its boundary vertices stay fixed.
        for(int z=0;z<n;z++)for(int x=0;x<n;x++)
            if(Math.max(Math.abs(r.x+x*STEP-r.eyeX),Math.abs(r.z+z*STEP-r.eyeZ))<=r.nativeRadius+48)valid[z*n+x]=false;
        var mesh=HybridVoxelMesh.build(new HybridVoxelMesh.Grid(n,STEP,r.minY,r.maxY-r.minY,volume,volumeLight,tints,valid,r.x,r.z));
        return new Result(r,mesh,(System.nanoTime()-start)/1_000_000,sampled,total-sampled);
    }
    private void upload(Result result) {
        if(shader==null)shader=Shader.make().apply(properties::apply).define("FR_HYBRID_SURFACE")
                .add(ShaderType.VERTEX,"voxy:lod/hybrid/surface.vert")
                .add(ShaderType.FRAGMENT,"voxy:lod/hybrid/surface.frag")
                .replace("// FRAGMENT_MATERIAL_SOURCE",FragmentAtmosphereCompat.materialSource()).compile();
        if(vao==0){
            vao=glCreateVertexArrays();vbo=glCreateBuffers();ibo=glCreateBuffers();morphBuffer=glCreateBuffers();morphTexture=glCreateTextures(GL_TEXTURE_BUFFER);glVertexArrayElementBuffer(vao,ibo);
            glVertexArrayVertexBuffer(vao,0,vbo,0,16);
            glEnableVertexArrayAttrib(vao,0);glVertexArrayAttribIFormat(vao,0,4,GL_UNSIGNED_INT,0);glVertexArrayAttribBinding(vao,0,0);
        }
        glNamedBufferData(vbo,result.mesh.vertices(),GL_STATIC_DRAW);vertices=result.mesh.indices().length;
        if(result.mesh.positions().length/4>glGetInteger(GL_MAX_TEXTURE_BUFFER_SIZE))throw new IllegalStateException("Hybrid snapshot exceeds texture buffer capacity; needs tiled meshes");
        glNamedBufferData(morphBuffer,result.mesh.positions(),GL_STATIC_DRAW);
        glTextureBuffer(morphTexture,GL_RGBA32F,morphBuffer);
        glNamedBufferData(ibo,result.mesh.indices(),GL_STATIC_DRAW);
        if(coverage!=0)glDeleteTextures(coverage);
        coverage=glCreateTextures(GL_TEXTURE_2D);glTextureStorage2D(coverage,1,GL_R32F,result.mesh.cells(),result.mesh.cells());
        glTextureSubImage2D(coverage,0,0,0,result.mesh.cells(),result.mesh.cells(),GL_RED,GL_FLOAT,result.mesh.coverage());
        glTextureParameteri(coverage,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTextureParameteri(coverage,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        if(textures!=0)glDeleteTextures(textures);
        textures=glCreateTextures(GL_TEXTURE_2D_ARRAY);int count=result.request.catalog.material.length;
        glTextureStorage3D(textures,6,GL_RGBA8,TEXTURE_SIZE,TEXTURE_SIZE,count);
        ByteBuffer bytes=MemoryUtil.memAlloc(result.request.catalog.pixels.length);
        try{bytes.put(result.request.catalog.pixels).flip();glTextureSubImage3D(textures,0,0,0,0,TEXTURE_SIZE,TEXTURE_SIZE,count,GL_RGBA,GL_UNSIGNED_BYTE,bytes);}
        finally{MemoryUtil.memFree(bytes);}
        glGenerateTextureMipmap(textures);
        glTextureParameteri(textures,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR);glTextureParameteri(textures,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTextureParameteri(textures,GL_TEXTURE_WRAP_S,GL_REPEAT);glTextureParameteri(textures,GL_TEXTURE_WRAP_T,GL_REPEAT);
    }
    @Override public void close(){
        closed=true;enabled=false;active=null;worker.shutdownNow();
        try{if(!worker.awaitTermination(5,TimeUnit.SECONDS))Logger.warn("Hybrid worker still stopping; engine reference is held until completion");}catch(InterruptedException e){Thread.currentThread().interrupt();}
        if(shader!=null)shader.free();if(vbo!=0)glDeleteBuffers(vbo);if(vao!=0)glDeleteVertexArrays(vao);if(ibo!=0)glDeleteBuffers(ibo);if(morphBuffer!=0)glDeleteBuffers(morphBuffer);if(morphTexture!=0)glDeleteTextures(morphTexture);
        if(coverage!=0)glDeleteTextures(coverage);if(textures!=0)glDeleteTextures(textures);
    }
}
