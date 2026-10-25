package me.cortex.voxy.client.core.rendering.lod.debug;

import me.cortex.voxy.client.core.rendering.lod.bake.*;
import me.cortex.voxy.client.core.rendering.lod.runtime.LodWorkQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.nio.file.*;
import java.util.*;

/** Opt-in developer integration check. Does not enable a new terrain draw backend.
 * Creates its own disposable world; no existing save is opened/modified.
 */
public final class LodClientSmoke {
    private final LodWorkQueue<String,String> workers=new LodWorkQueue<>(2,4,64L<<20);
    private boolean opened,submitted,done;private int ticks,column;
    private int ox,oy,oz;
    private final int[] blocks=new int[46*96*46];
    private final byte[] light=new byte[blocks.length],natural=new byte[blocks.length];
    private final Map<String,Integer> palette=new LinkedHashMap<>();
    private final List<String> states=new ArrayList<>(),biomes=new ArrayList<>();
    private LodClientSmoke(){palette.put("air",0);states.add("minecraft:air");biomes.add("minecraft:plains");}
    public static void install(){if(Boolean.getBoolean("fragment.lod.smoke")){var probe=new LodClientSmoke();NeoForge.EVENT_BUS.addListener(probe::tick);}}
    private void tick(ClientTickEvent.Post event) {
        if(done)return;var mc=Minecraft.getInstance();
        try {
            if(!opened&&mc.screen instanceof TitleScreen){
                opened=true;mc.options.renderDistance().set(6);mc.options.simulationDistance().set(5);
                String name="lod-smoke-"+System.currentTimeMillis();
                mc.createWorldOpenFlows().createFreshLevel(name,new LevelSettings(name,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(20261024L,false,false),WorldPresets::createNormalWorldDimensions,mc.screen);
                return;
            }
            if(mc.level==null||mc.player==null)return;
            if(++ticks<120)return;
            if(!submitted) {
                if(column==0){ox=(mc.player.blockPosition().getX()>>5)*32-7;oz=(mc.player.blockPosition().getZ()>>5)*32-7;oy=Math.max(mc.level.getMinBuildHeight(),mc.player.blockPosition().getY()-48);}
                for(int n=0;n<12&&column<46*46;n++,column++) {
                    int x=column%46,z=column/46;
                    if(!mc.level.hasChunk((ox+x)>>4,(oz+z)>>4))return;
                    for(int y=0;y<96;y++) {
                        var pos=new BlockPos(ox+x,oy+y,oz+z);var state=mc.level.getBlockState(pos);int i=(y*46+z)*46+x;
                        if(!state.isAir()) {
                            String block=BuiltInRegistries.BLOCK.getKey(state.getBlock())+state.getValues().toString();
                            String biome=mc.level.getBiome(pos).unwrapKey().map(k->k.location().toString()).orElse("unknown");
                            String key=block+"|"+biome;
                            int id=palette.computeIfAbsent(key,k->{states.add(block);biomes.add(biome);return states.size()-1;});blocks[i]=id;
                            // Author permission applies only to this generated disposable test world.
                            natural[i]=(byte)(state.isSolidRender(mc.level,pos)&&!state.hasBlockEntity()?1:0);
                        }
                        light[i]=(byte)((mc.level.getBrightness(LightLayer.SKY,pos)<<4)|mc.level.getBrightness(LightLayer.BLOCK,pos));
                    }
                }
                if(column<46*46)return;
                var snapshot=new VoxelSnapshot(ox,oy,oz,46,96,46,states.toArray(String[]::new),biomes.toArray(String[]::new),blocks,light,natural);
                submitted=workers.submit("terrain",1,0,32L<<20,()->{
                    var exact=GreedyQuadMesher.build(snapshot,7,7,32);
                    var source=ConformingSurface.extract(snapshot,7,7,32,exact.selected());
                    var history=QuadricSimplifier.progressive(source,.1,8.);
                    int[] triangles=Arrays.stream(history.threePrefixes()).map(p->history.level(p).triangles()).toArray();
                    long[] payload=Arrays.stream(history.threePrefixes()).mapToLong(p->history.level(p).packedBytes()).toArray();
                    boolean[] fits=new boolean[3];for(int l=0;l<3;l++)fits[l]=triangles[l]<=exact.selected().triangles()&&payload[l]<=exact.selected().payloadBytes();
                    return "{\"scope\":\"in-client CPU history/async integration on captured generated terrain; diagnostic cube input; no new terrain renderer\",\"origin\":["+ox+","+oy+","+oz+"],\"steps\":"+history.steps().size()+",\"source_triangles\":"+source.triangles()+",\"linked_level_triangles\":"+Arrays.toString(triangles)+",\"exact_triangles\":"+exact.selected().triangles()+",\"exact_payload_bytes\":"+exact.selected().payloadBytes()+",\"candidate_payload_bytes\":"+Arrays.toString(payload)+",\"candidate_fits_own_geometry_budget\":"+Arrays.toString(fits)+",\"worker\":\""+Thread.currentThread().getName()+"\"}";
                });
            }
            var result=workers.poll((key,revision)->revision==1);
            if(result!=null) {
                if(result.failure()!=null)throw new IllegalStateException("LOD smoke worker failed",result.failure());
                Path file=mc.gameDirectory.toPath().resolve("lod-smoke-result.json");Files.writeString(file,result.value());
                System.out.println("FRAGMENT_LOD_SMOKE_PASSED "+result.value());
                Screenshot.grab(mc.gameDirectory,"lod-smoke.png",mc.getMainRenderTarget(),message->System.out.println(message.getString()));
                done=true;workers.close();
            }
        } catch(Exception error) {
            done=true;workers.close();error.printStackTrace();
            try{Files.writeString(mc.gameDirectory.toPath().resolve("lod-smoke-failure.txt"),error.toString());}catch(Exception ignored){}
        }
    }
}
