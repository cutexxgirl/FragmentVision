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

/** Bounded tile producer. CPU mesh payloads cross a byte-budgeted queue; only GPU tiles persist. */
public final class HybridTerrain implements AutoCloseable {
    private static final int TILE=64, HALO=16, SIZE=1088, STEP=2, CELLS=SIZE/STEP, TEXTURE_SIZE=32;
    private static final long GPU_BUDGET=512L*1024*1024;
    private final WorldEngine world;
    private final RenderProperties properties;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Vision hybrid tiles");t.setDaemon(true);return t;});
    private final AtomicLong revision=new AtomicLong();
    private final ConcurrentLinkedQueue<Long> dirtyTiles=new ConcurrentLinkedQueue<>();
    private final ArrayBlockingQueue<TileResult> ready=new ArrayBlockingQueue<>(8);
    private final Semaphore readyKiB=new Semaphore(16*1024);
    private final Map<Long,GpuTile> tiles=new LinkedHashMap<>();
    private final Map<Long,Stamp> completedTiles=new HashMap<>();
    private final LinkedHashMap<BlockState,Integer> materialLayers=new LinkedHashMap<>();
    private CompletableFuture<BuildStats> pending;
    private volatile Request request;
    private boolean enabled;
    private volatile boolean closed;
    private long nextBuildNanos,gpuBytes,lastDrawTriangles;
    private final int[] lastLevelTiles=new int[3];
    private float pixelScale=360;
    private String status="not built (WIP is off)";
    private Shader shader;
    private int coverage,coverageSum,textures;
    private CoveragePrefix prefix;
    private boolean earlyCull=true;
    private boolean prefixBound;
    private int savedPrefixTexture,savedPrefixSampler;
    private int uploaded,gpuRejected;
    private record State(int layer,boolean skip,float topOffset) {}
    private record Catalog(State[] states,byte[] pixels,float[] material,int[][] tint) {}
    private record Request(int x,int z,int minY,int maxY,int nativeRadius,double eyeX,double eyeZ,Catalog catalog,long revision,Set<Long> reuse) {}
    private record TileResult(Request request,int x,int z,HybridVoxelMesh.Mesh mesh,long millis) {}
    private record Stamp(double eyeX,double eyeZ,int nativeRadius) {}
    private record BuildStats(int completed,int skipped,int reused,long millis,long maxVolumeBytes,long maxMeshBytes,long maxObservedHeap) {}
    private static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    private static final class GpuTile implements AutoCloseable {
        int x,z,vao,vbo,morphBuffer,morphTexture;
        int[] ibo=new int[3],count=new int[3];
        float coarseError,farError,minY,maxY;
        float[] coverage;
        long bytes;
        @Override public void close(){
            glDeleteVertexArrays(vao);glDeleteBuffers(vbo);glDeleteBuffers(morphBuffer);glDeleteTextures(morphTexture);
            for(int id:ibo)glDeleteBuffers(id);
        }
    }
    public HybridTerrain(WorldEngine world,RenderProperties properties){this.world=world;this.properties=properties;}
    public String build(){return build(true);}
    private String build(boolean force){
        if(closed)return "renderer closed";
        if(pending!=null)return "tile build already running";
        if(!FragmentAtmosphereCompat.hasMaterial())return "WIP requires Fragment Render's shared lighting material";
        var mc=Minecraft.getInstance();var pos=mc.gameRenderer.getMainCamera().getPosition();
        try{
            var next=new Request(Math.floorDiv((int)Math.floor(pos.x),TILE)*TILE-SIZE/2+TILE/2,
                    Math.floorDiv((int)Math.floor(pos.z),TILE)*TILE-SIZE/2+TILE/2,
                    mc.level.getMinBuildHeight(),mc.level.getMaxBuildHeight(),mc.options.getEffectiveRenderDistance()*16,
                    pos.x,pos.z,catalog(),revision.get(),force?Set.of():reuseAt(pos.x,pos.z,mc.options.getEffectiveRenderDistance()*16));
            // Resource/catalog and mask are prepared before the worker starts. Existing tiles
            // keep drawing until an individual replacement has successfully uploaded.
            initGpu(next);request=next;uploaded=gpuRejected=0;nextBuildNanos=System.nanoTime()+5_000_000_000L;
            drainReady();
            pending=CompletableFuture.supplyAsync(()->{
                world.acquireRef();try{return buildTiles(next);}finally{world.releaseRef();}
            },worker);
            return status="building 64-block tiles, ready payload budget 16MiB";
        }catch(Exception e){Logger.error("Hybrid tile preparation failed",e);return status="preparation failed; voxel fallback";}
    }
    public String enabled(boolean value){enabled=value;return status();}
    public void earlyCull(boolean value){earlyCull=value;}
    public String status(){return "Hybrid WIP "+(enabled?"ON: ":"OFF: ")+status+", GPU="+(gpuBytes/1048576)+"MiB, last draw="+lastDrawTriangles+" triangles, level tiles="+Arrays.toString(lastLevelTiles)+", early raster cull="+earlyCull;}
    public void changed(WorldSection section,int flags){
        Request r=request;if(r==null||(flags&WorldEngine.UPDATE_TYPE_BLOCK_BIT)==0||section.lvl!=0)return;
        int x=section.x<<5,z=section.z<<5;
        if(Math.max(Math.abs(x+16-r.eyeX),Math.abs(z+16-r.eyeZ))<=r.nativeRadius+32)return;
        if(x<=r.x+SIZE&&x+32>=r.x&&z<=r.z+SIZE&&z+32>=r.z){
            revision.incrementAndGet();
            for(int tz=Math.floorDiv(z-HALO,TILE)*TILE;tz<=z+32+HALO;tz+=TILE)
                for(int tx=Math.floorDiv(x-HALO,TILE)*TILE;tx<=x+32+HALO;tx+=TILE)dirtyTiles.add(key(tx,tz));
        }
    }
    /** Frame boundary: bounded uploads, then publish each successful replacement mask. */
    public void prepare(Viewport<?> view){
        if(closed)return;
        pixelScale=view.height*.5f*Math.abs(view.projection.m11());
        for(Long dirty;(dirty=dirtyTiles.poll())!=null;){
            completedTiles.remove(dirty);GpuTile old=tiles.remove(dirty);
            if(old!=null){gpuBytes-=old.bytes;old.close();if(request!=null)publishCoverage(request,old.x,old.z,new float[TILE/STEP*TILE/STEP]);}
        }
        long uploadBytes=0,start=System.nanoTime();
        for(int i=0;i<8;i++){
            TileResult tile=ready.poll();if(tile==null)break;
            try{
                if(tile.request==request&&tile.request.revision==revision.get()){upload(tile);uploaded++;}
            }catch(Exception e){Logger.error("Hybrid tile upload failed; retaining voxel/previous tile",e);}
            finally{readyKiB.release(payloadKiB(tile));}
            uploadBytes+=tile.mesh.bytes();
            if(uploadBytes>=4L*1024*1024||System.nanoTime()-start>=8_000_000)break;
        }
        if(pending!=null&&pending.isDone()&&ready.isEmpty()){
            try{
                BuildStats stats=pending.join();
                long fine=0,coarse=0,far=0;for(GpuTile t:tiles.values()){fine+=t.count[0]/3;coarse+=t.count[1]/3;far+=t.count[2]/3;}
                status=tiles.size()+" tiles, indices fine/coarse/far="+fine+"/"+coarse+"/"+far+
                        ", "+stats.millis+"ms elapsed (including queue wait), max tile input="+stats.maxVolumeBytes/1024+"KiB, max tile mesh="+
                        stats.maxMeshBytes/1024+"KiB, observed heap="+stats.maxObservedHeap/1048576+"MiB, skipped="+stats.skipped+", GPU fallback="+gpuRejected+", reused="+stats.reused;
                Logger.info("Hybrid tiled terrain ready: "+status());
            }catch(CancellationException ignored){status="build superseded";}
            catch(CompletionException error){
                if(error.getCause() instanceof CancellationException)status="build superseded";
                else {Logger.error("Hybrid tile build failed; retaining completed tiles",error);status="tile build failed; completed tiles retained";}
            }
            catch(Exception e){Logger.error("Hybrid tile build failed; retaining completed tiles",e);status="tile build failed; completed tiles retained";}
            finally{pending=null;}
        }
        Request r=request;
        if(enabled&&r!=null&&pending==null&&System.nanoTime()>nextBuildNanos&&
                (Math.max(Math.abs(view.cameraX-r.eyeX),Math.abs(view.cameraZ-r.eyeZ))>32||
                Minecraft.getInstance().options.getEffectiveRenderDistance()*16!=r.nativeRadius||r.revision!=revision.get()))build(false);
    }
    private BuildStats buildTiles(Request r){
        long start=System.nanoTime(),maxVolume=0,maxMesh=0,maxHeap=0;int completed=0,skipped=0,reused=0;long[] sectionData=new long[WorldSection.SECTION_VOLUME];
        var order=new ArrayList<int[]>();
        for(int z=r.z;z<r.z+SIZE;z+=TILE)for(int x=r.x;x<r.x+SIZE;x+=TILE)order.add(new int[]{x,z});
        order.sort(Comparator.comparingDouble(p->Math.max(Math.abs(p[0]+TILE/2.-r.eyeX),Math.abs(p[1]+TILE/2.-r.eyeZ))));
        for(int[] pos:order){
            checkCurrent(r);
            if(r.reuse.contains(key(pos[0],pos[1]))){reused++;continue;}
            try{
                TileResult tile=sample(r,pos[0],pos[1],sectionData);
                maxVolume=Math.max(maxVolume,(long)(TILE+HALO*2+STEP)/STEP*((TILE+HALO*2+STEP)/STEP)*(r.maxY-r.minY)*2);
                maxMesh=Math.max(maxMesh,tile.mesh.bytes());
                maxHeap=Math.max(maxHeap,java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                int kib=payloadKiB(tile);if(kib>16*1024){skipped++;continue;}
                while(!readyKiB.tryAcquire(kib,100,TimeUnit.MILLISECONDS))checkCurrent(r);
                boolean queued=false;
                try{while(!ready.offer(tile,100,TimeUnit.MILLISECONDS))checkCurrent(r);queued=true;}
                finally{if(!queued)readyKiB.release(kib);}
                completed++;tile=null;
            }catch(HybridVoxelMesh.BudgetExceeded tooComplex){skipped++;Logger.warn("Hybrid tile exceeds CPU budget; voxel fallback at "+pos[0]+","+pos[1]);}
            catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new CancellationException();}
        }
        return new BuildStats(completed,skipped,reused,(System.nanoTime()-start)/1_000_000,maxVolume,maxMesh,maxHeap);
    }
    private Set<Long> reuseAt(double eyeX,double eyeZ,int radius){
        var reuse=new HashSet<Long>();
        for(var entry:completedTiles.entrySet()){
            long key=entry.getKey();Stamp old=entry.getValue();int x=(int)(key>>32),z=(int)key;
            boolean same=old.eyeX==eyeX&&old.eyeZ==eyeZ&&old.nativeRadius==radius;
            boolean outside=outsideNative(x,z,eyeX,eyeZ,radius)&&outsideNative(x,z,old.eyeX,old.eyeZ,old.nativeRadius);
            if(same||outside)reuse.add(key);
        }
        return Set.copyOf(reuse);
    }
    private static boolean outsideNative(int x,int z,double eyeX,double eyeZ,int radius){
        double dx=Math.max(0,Math.max(x-HALO-eyeX,eyeX-(x+TILE+HALO)));
        double dz=Math.max(0,Math.max(z-HALO-eyeZ,eyeZ-(z+TILE+HALO)));
        return Math.max(dx,dz)>radius+48+STEP*2;
    }
    private static int payloadKiB(TileResult tile){return Math.max(1,(int)((tile.mesh.bytes()+1023)/1024));}
    private void drainReady(){for(TileResult tile;(tile=ready.poll())!=null;)readyKiB.release(payloadKiB(tile));}
    private void checkCurrent(Request r){if(closed||request!=r||r.revision!=revision.get()||Thread.currentThread().isInterrupted())throw new CancellationException();}
    private TileResult sample(Request r,int coreX,int coreZ,long[] data){
        long start=System.nanoTime();int x0=coreX-HALO,z0=coreZ-HALO,n=(TILE+HALO*2)/STEP+1,area=n*n,h=r.maxY-r.minY;
        byte[] volume=new byte[area*h],light=new byte[volume.length];
        int[] tint=new int[area];Arrays.fill(tint,0xffffff);boolean[] valid=new boolean[area],done=new boolean[area];
        // Unknown sections are solid/protected, not invented air. Only columns with a
        // known natural top and known immediate neighbours become replacement coverage.
        Arrays.fill(volume,(byte)255);
        for(int tz=Math.floorDiv(z0,32);tz<=Math.floorDiv(z0+(n-1)*STEP,32);tz++)
            for(int tx=Math.floorDiv(x0,32);tx<=Math.floorDiv(x0+(n-1)*STEP,32);tx++)
                for(int ty=(r.maxY-1)>>5;ty>=r.minY>>5;ty--){
                    checkCurrent(r);var section=world.acquireIfExists(0,tx,ty,tz);if(section==null)continue;
                    try{section.copyDataTo(data);}finally{section.release();}
                    for(int z=0;z<32;z+=STEP)for(int x=0;x<32;x+=STEP){
                        int wx=(tx<<5)+x,wz=(tz<<5)+z;if(wx<x0||wz<z0||wx>x0+(n-1)*STEP||wz>z0+(n-1)*STEP)continue;
                        int gx=(wx-x0)/STEP,gz=(wz-z0)/STEP,p=gz*n+gx;
                        for(int y=31;y>=0;y--){
                            int wy=(ty<<5)+y;if(wy<r.minY||wy>=r.maxY)continue;
                            long raw=data[WorldSection.getIndex(x,y,z)];int id=Mapper.getBlockId(raw),index=(wy-r.minY)*area+p;
                            light[index]=(byte)Mapper.getLightId(raw);
                            State state=id>=0&&id<r.catalog.states.length?r.catalog.states[id]:null;
                            volume[index]=state==null?(byte)255:state.layer>=0?(byte)(state.layer+1):state.skip?0:(byte)255;
                            if(!done[p]&&state!=null&&!state.skip){
                                done[p]=true;
                                valid[p]=state.layer>=0&&wy+1<r.maxY&&volume[(wy+1-r.minY)*area+p]==0;
                                if(valid[p]){int biome=Mapper.getBiomeId(raw);int[] colours=r.catalog.tint[state.layer];tint[p]=biome<colours.length?colours[biome]:0xffffff;}
                            }
                        }
                    }
                }
        for(int z=0;z<n;z++)for(int x=0;x<n;x++)
            if(Math.max(Math.abs(x0+x*STEP-r.eyeX),Math.abs(z0+z*STEP-r.eyeZ))<=r.nativeRadius+48)valid[z*n+x]=false;
        var mesh=HybridVoxelMesh.build(new HybridVoxelMesh.Grid(n,STEP,r.minY,h,volume,light,tint,valid,x0,z0),HALO/STEP,(HALO+TILE)/STEP);
        return new TileResult(r,coreX,coreZ,mesh,(System.nanoTime()-start)/1_000_000);
    }
    public void bindMask(int program,Viewport<?> view){
        Request r=request;boolean use=enabled&&r!=null&&!tiles.isEmpty()&&!closed;
        glUniform1i(glGetUniformLocation(program,"fv_HybridEnabled"),use?1:0);
        glUniform1i(glGetUniformLocation(program,"fv_HybridEarlyCull"),use&&earlyCull?1:0);
        // Set the integer sampler even when disabled: aliasing a float sampler on unit 0 invalidates the draw.
        glUniform1i(glGetUniformLocation(program,"fv_HybridCoverageSum"),13);
        glUniform1i(glGetUniformLocation(program,"fv_HybridCoverage"),6);if(!use)return;
        if(prefix.dirty())glTextureSubImage2D(coverageSum,0,0,0,CELLS+1,CELLS+1,GL_RED_INTEGER,GL_UNSIGNED_INT,prefix.rebuild());
        if(!prefixBound){
            int active=glGetInteger(GL_ACTIVE_TEXTURE);glActiveTexture(GL_TEXTURE0+13);
            savedPrefixTexture=glGetInteger(GL_TEXTURE_BINDING_2D);glActiveTexture(active);
            savedPrefixSampler=glGetIntegeri(GL_SAMPLER_BINDING,13);prefixBound=true;
        }
        glBindTextureUnit(13,coverageSum);glBindSampler(13,0);
        glBindTextureUnit(6,coverage);glBindSampler(6,0);
        glUniform4f(glGetUniformLocation(program,"fv_HybridGrid"),(float)(r.x-view.cameraX),(float)(r.z-view.cameraZ),STEP,CELLS);
        glUniform1f(glGetUniformLocation(program,"fv_HybridNear"),Minecraft.getInstance().options.getEffectiveRenderDistance()*16f+48);
    }
    public void releaseMask(){
        if(prefixBound){glBindTextureUnit(13,savedPrefixTexture);glBindSampler(13,savedPrefixSampler);prefixBound=false;}
    }
    public void render(Viewport<?> view){
        lastDrawTriangles=0;Arrays.fill(lastLevelTiles,0);if(!enabled||request==null||tiles.isEmpty())return;
        shader.bind();bindMask(shader.id(),view);
        glUniform1f(glGetUniformLocation(shader.id(),"fv_SurfacePixelScale"),pixelScale);
        glUniform3f(glGetUniformLocation(shader.id(),"fv_SurfaceBands"),HybridLodPolicy.ROUND_END,HybridLodPolicy.COARSE_END,HybridLodPolicy.FAR_END);
        glBindTextureUnit(0,textures);glBindSampler(0,0);glBindTextureUnit(2,view.depthBoundingBuffer.getDepthTex().id);
        glUniform1i(glGetUniformLocation(shader.id(),"fv_SurfaceTextures"),0);
        try(var stack=MemoryStack.stackPush()){glUniformMatrix4fv(glGetUniformLocation(shader.id(),"fv_SurfaceMVP"),false,view.MVP.get(stack.mallocFloat(16)));}
        glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glEnable(GL_DEPTH_TEST);glDepthFunc(properties.closerEqualDepthCompare());glDepthMask(true);
        FragmentAtmosphereCompat.bindMaterial(shader.id());
        try{
            float near=Minecraft.getInstance().options.getEffectiveRenderDistance()*16f+48;
            for(GpuTile t:tiles.values()){
                float x=(float)(t.x-view.cameraX),z=(float)(t.z-view.cameraZ);
                if(!view.frustum.testAab(x-4,t.minY-(float)view.cameraY-4,z-4,x+TILE+4,t.maxY-(float)view.cameraY+4,z+TILE+4))continue;
                float dx=Math.max(0,Math.max(x,-(x+TILE))),dz=Math.max(0,Math.max(z,-(z+TILE)));
                float minDistance=Math.max(dx,dz);
                float dy=Math.max(0,Math.max(t.minY-(float)view.cameraY,(float)view.cameraY-t.maxY));
                float spatialDistance=Math.max(1,(float)Math.sqrt(dx*dx+dy*dy+dz*dz)-HybridLodPolicy.TARGET_MARGIN);
                int level=HybridLodPolicy.level(minDistance,spatialDistance,near,pixelScale,t.coarseError,t.farError);
                lastLevelTiles[level]++;
                glUniform1i(glGetUniformLocation(shader.id(),"fv_SurfaceLevel"),level);
                glUniform3f(glGetUniformLocation(shader.id(),"fv_SurfaceOrigin"),x-HALO,(float)-view.cameraY,z-HALO);
                glBindVertexArray(t.vao);glVertexArrayElementBuffer(t.vao,t.ibo[level]);glBindTextureUnit(12,t.morphTexture);glBindSampler(12,0);
                glDrawElements(GL_TRIANGLES,t.count[level],GL_UNSIGNED_INT,0L);lastDrawTriangles+=t.count[level]/3;
            }
        }finally{FragmentAtmosphereCompat.releaseMaterial();releaseMask();glBindVertexArray(0);glBindTextureUnit(12,0);glEnable(GL_CULL_FACE);glBindTextureUnit(0,0);glBindTextureUnit(6,0);}
    }
    private Catalog catalog() throws Exception {
        var mc=Minecraft.getInstance();var entries=world.getMapper().getStateEntries();
        var biomes=world.getMapper().getBiomeEntries();var registry=mc.level.registryAccess().registryOrThrow(Registries.BIOME);
        var layers=new LinkedHashMap<BlockState,Integer>(materialLayers);var states=new State[entries.length];
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
        materialLayers.clear();materialLayers.putAll(layers);
        return new Catalog(states,pixels,material,tint);
    }
    private void initGpu(Request r){
        if(shader==null)shader=Shader.make().apply(properties::apply).define("FR_HYBRID_SURFACE")
                .add(ShaderType.VERTEX,"voxy:lod/hybrid/surface.vert").add(ShaderType.FRAGMENT,"voxy:lod/hybrid/surface.frag")
                .replace("// FRAGMENT_MATERIAL_SOURCE",FragmentAtmosphereCompat.materialSource()).compile();
        for(var it=tiles.values().iterator();it.hasNext();){GpuTile t=it.next();if(t.x<r.x||t.z<r.z||t.x>=r.x+SIZE||t.z>=r.z+SIZE){gpuBytes-=t.bytes;t.close();it.remove();}}
        completedTiles.keySet().removeIf(key->{int x=(int)(key>>32),z=(int)(long)key;return x<r.x||z<r.z||x>=r.x+SIZE||z>=r.z+SIZE;});
        if(coverage!=0)glDeleteTextures(coverage);
        if(coverageSum!=0)glDeleteTextures(coverageSum);
        prefix=new CoveragePrefix(CELLS);
        coverageSum=glCreateTextures(GL_TEXTURE_2D);glTextureStorage2D(coverageSum,1,GL_R32UI,CELLS+1,CELLS+1);
        glTextureParameteri(coverageSum,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTextureParameteri(coverageSum,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        coverage=glCreateTextures(GL_TEXTURE_2D);glTextureStorage2D(coverage,1,GL_R32F,CELLS,CELLS);
        glTextureSubImage2D(coverage,0,0,0,CELLS,CELLS,GL_RED,GL_FLOAT,new float[CELLS*CELLS]);
        glTextureParameteri(coverage,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTextureParameteri(coverage,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        for(GpuTile t:tiles.values())publishCoverage(r,t.x,t.z,t.coverage);
        if(textures!=0)glDeleteTextures(textures);
        textures=glCreateTextures(GL_TEXTURE_2D_ARRAY);int count=r.catalog.material.length;
        glTextureStorage3D(textures,6,GL_RGBA8,TEXTURE_SIZE,TEXTURE_SIZE,count);
        ByteBuffer bytes=MemoryUtil.memAlloc(r.catalog.pixels.length);
        try{bytes.put(r.catalog.pixels).flip();glTextureSubImage3D(textures,0,0,0,0,TEXTURE_SIZE,TEXTURE_SIZE,count,GL_RGBA,GL_UNSIGNED_BYTE,bytes);}finally{MemoryUtil.memFree(bytes);}
        glGenerateTextureMipmap(textures);glTextureParameteri(textures,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR);
        glTextureParameteri(textures,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTextureParameteri(textures,GL_TEXTURE_WRAP_S,GL_REPEAT);glTextureParameteri(textures,GL_TEXTURE_WRAP_T,GL_REPEAT);
    }
    private void publishCoverage(Request r,int x,int z,float[] values){
        prefix.put((x-r.x)/STEP,(z-r.z)/STEP,TILE/STEP,TILE/STEP,values);
        glTextureSubImage2D(coverage,0,(x-r.x)/STEP,(z-r.z)/STEP,TILE/STEP,TILE/STEP,GL_RED,GL_FLOAT,values);
    }
    private void upload(TileResult result){
        HybridVoxelMesh.Mesh m=result.mesh;long key=key(result.x,result.z);GpuTile previous=tiles.get(key);
        long bytes=m.bytes()-m.coverage().length*4L-m.positions().length*4L+HybridGpuLayout.positionBytes(m.positions().length);
        if(gpuBytes-(previous==null?0:previous.bytes)+bytes>GPU_BUDGET){gpuRejected++;if(gpuRejected==1)Logger.warn("Hybrid GPU budget reached; keeping fallback tiles");return;}
        GpuTile t=new GpuTile();t.x=result.x;t.z=result.z;t.bytes=bytes;t.coverage=m.coverage();t.coarseError=m.coarseError();t.farError=m.farError();
        t.minY=Float.POSITIVE_INFINITY;t.maxY=Float.NEGATIVE_INFINITY;
        for(int i=0;i<m.positions().length;i+=HybridVoxelMesh.STRIDE){t.minY=Math.min(t.minY,m.positions()[i+1]);t.maxY=Math.max(t.maxY,m.positions()[i+1]);}
        if(m.indices().length>0){
            try{
                var gpuPositions=HybridGpuLayout.compactPositions(m.positions());
                if(gpuPositions.length/4>glGetInteger(GL_MAX_TEXTURE_BUFFER_SIZE))throw new IllegalStateException("tile exceeds texture buffer limit");
                t.vao=glCreateVertexArrays();t.vbo=glCreateBuffers();t.morphBuffer=glCreateBuffers();t.morphTexture=glCreateTextures(GL_TEXTURE_BUFFER);
                glVertexArrayVertexBuffer(t.vao,0,t.vbo,0,16);glEnableVertexArrayAttrib(t.vao,0);glVertexArrayAttribIFormat(t.vao,0,4,GL_UNSIGNED_INT,0);glVertexArrayAttribBinding(t.vao,0,0);
                glNamedBufferData(t.vbo,m.vertices(),GL_STATIC_DRAW);glNamedBufferData(t.morphBuffer,gpuPositions,GL_STATIC_DRAW);glTextureBuffer(t.morphTexture,GL_RGBA32F,t.morphBuffer);
                int[][] indices={m.indices(),m.coarseIndices(),m.farIndices()};
                for(int i=0;i<3;i++){t.ibo[i]=glCreateBuffers();t.count[i]=indices[i].length;glNamedBufferData(t.ibo[i],indices[i],GL_STATIC_DRAW);}
            }catch(Exception error){t.close();throw error;}
            tiles.put(key,t);gpuBytes+=t.bytes;
        }else{tiles.remove(key);Arrays.fill(t.coverage,0);}
        // No replacement mask is visible before buffers exist. Failed/oversized tiles keep fallback.
        publishCoverage(result.request,t.x,t.z,t.coverage);
        completedTiles.put(key,new Stamp(result.request.eyeX,result.request.eyeZ,result.request.nativeRadius));
        if(previous!=null){gpuBytes-=previous.bytes;previous.close();}
    }
    @Override public void close(){
        closed=true;enabled=false;worker.shutdownNow();drainReady();
        try{if(!worker.awaitTermination(5,TimeUnit.SECONDS))Logger.warn("Hybrid worker stopping; engine ref retained until completion");}catch(InterruptedException e){Thread.currentThread().interrupt();}
        drainReady();for(GpuTile t:tiles.values())t.close();tiles.clear();completedTiles.clear();gpuBytes=0;
        if(shader!=null)shader.free();if(coverage!=0)glDeleteTextures(coverage);if(coverageSum!=0)glDeleteTextures(coverageSum);if(textures!=0)glDeleteTextures(textures);
    }
}
