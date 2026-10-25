package me.cortex.voxy.client.core.rendering.lod.debug;

import com.google.gson.*;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.popcraft.chunky.ChunkyProvider;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.lwjgl.opengl.GL33C.*;

/** Opt-in, isolated singleplayer test driver. No hooks change normal play.
 * Chunky completes and saves before disk import. Controls are consumed on the client thread.
 * Timer queries are bounded and polled without waiting; absent GPU samples stay absent.
 */
public final class LodAerialProbe {
    private static final boolean ACTIVE=Boolean.getBoolean("fragment.lod.aerial");
    private static LodAerialProbe instance;
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path root=Minecraft.getInstance().gameDirectory.toPath().resolve("lod-aerial");
    private String stage="opening",mode="native",world;
    private boolean opened,started,imported;
    private volatile boolean ready;
    private volatile boolean generated;
    private volatile long generatedChunks;
    private CompletableFuture<?> saved;
    private WorldImporter importer;
    private long lastPoll,lastFrame;
    private int frames,warmup;
    private String measurement;
    private final List<Map<String,Object>> samples=new ArrayList<>();
    private final ArrayDeque<Stamp> pending=new ArrayDeque<>();
    private double lodCpu;
    public static final class Stamp {
        final int start=glGenQueries(),end=glGenQueries();
        final long cpu=System.nanoTime();
        Map<String,Object> sample;
        boolean attached;
    }
    public static void install(){
        if(!ACTIVE)return;
        instance=new LodAerialProbe();VoxyConfig.CONFIG.enableRendering=false;
        NeoForge.EVENT_BUS.addListener(instance::tick);
        NeoForge.EVENT_BUS.addListener(instance::frame);
        NeoForge.EVENT_BUS.addListener(instance::entity);
    }
    public static boolean skipLodDraw(){return ACTIVE&&instance!=null&&!instance.mode.equals("voxel")&&!instance.mode.equals("hybrid");}
    public static Stamp beginLod(){
        if(!ACTIVE||instance==null||instance.measurement==null||instance.frames<=0||instance.warmup>0||instance.pending.size()>=16)return null;
        Stamp s=new Stamp();glQueryCounter(s.start,GL_TIMESTAMP);return s;
    }
    public static void endLod(Stamp s){
        if(s==null)return;
        glQueryCounter(s.end,GL_TIMESTAMP);
        instance.lodCpu=(System.nanoTime()-s.cpu)/1e6;
        instance.pending.addLast(s);
    }
    private void entity(EntityJoinLevelEvent e){
        if(opened && e.getEntity() instanceof Mob)e.setCanceled(true);
    }
    private void note(String s)throws Exception {
        stage=s;System.out.println("FRAGMENT_AERIAL "+s);
        Files.createDirectories(root);Files.writeString(root.resolve("status.json"),JSON.toJson(Map.of("stage",stage,"world",world==null?"":world,"mode",mode,"chunks",generatedChunks)));
    }
    private void command(String text){
        var server=Minecraft.getInstance().getSingleplayerServer();
        server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),text));
    }
    private void tick(ClientTickEvent.Post e){
        var mc=Minecraft.getInstance();
        try {
            if(!opened&&mc.screen instanceof TitleScreen){
                opened=true;Files.createDirectories(root);
                world=System.getProperty("fragment.lod.world","lod-aerial-"+System.currentTimeMillis());
                if(!world.matches("lod-aerial-[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("Only probe-owned lod-aerial-* worlds may be opened");
                mc.options.renderDistance().set(6);mc.options.simulationDistance().set(5);
                mc.options.enableVsync().set(false);mc.options.framerateLimit().set(10);mc.options.fov().set(90);
                mc.options.pauseOnLostFocus=false;mc.options.hideGui=true;
                if(Files.isDirectory(mc.gameDirectory.toPath().resolve("saves").resolve(world))){
                    mc.createWorldOpenFlows().openWorld(world,()->{});
                }else{
                    var rules=new GameRules();rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false,null);
                    mc.createWorldOpenFlows().createFreshLevel(world,new LevelSettings(world,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,rules,WorldDataConfiguration.DEFAULT),new WorldOptions(1234L,false,false),WorldPresets::createNormalWorldDimensions,mc.screen);
                }
                note("opening");return;
            }
            if(mc.level==null||mc.player==null||mc.getSingleplayerServer()==null)return;
            if(!started){
                started=true;
                command("gamerule doMobSpawning false");command("gamerule doDaylightCycle false");
                command("gamerule doWeatherCycle false");command("gamerule randomTickSpeed 0");
                command("time set 4500");command("weather clear");command("kill @e[type=!minecraft:player]");
                command("gamemode spectator @a");command("tp @a 512.5 230 700.5 0 25");
                var api=ChunkyProvider.get().getApi();
                api.onGenerationProgress(p->{generatedChunks=p.chunks();});
                api.onGenerationComplete(p->{if(p.world().equals("minecraft:overworld"))generated=true;});
                if(!api.startTask("minecraft:overworld","square",512,700,768,768,"concentric"))throw new IllegalStateException("Chunky rejected task");
                note("pregenerating");return;
            }
            if(generated&&saved==null){
                var server=mc.getSingleplayerServer();
                saved=CompletableFuture.runAsync(()->server.saveEverything(false,true,true),server);
                note("saving-pregeneration");return;
            }
            if(saved!=null&&saved.isDone()&&!imported){
                saved.join();imported=true;
                var v=VoxyCommon.getInstance();var engine=WorldIdentifier.ofEngine(mc.level);
                importer=new WorldImporter(engine,mc.level,v.getServiceManager(),v.savingServiceRateLimiter);
                importer.importRegionDirectoryAsync(mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).resolve("region").toFile());
                importer.runImport((n,total)->{},n->{generatedChunks=n;ready=true;});
                note("importing");return;
            }
            if(ready&&!stage.equals("ready")){
                if(importer!=null){importer.shutdown();importer=null;}
                VoxyConfig.CONFIG.enableRendering=true;
                ((IGetVoxyRenderSystem)mc.levelRenderer).voxy$createRenderer();
                mc.options.framerateLimit().set(260);
                note("ready");
            }
            if(!ready)return;
            long now=System.nanoTime();if(now-lastPoll<250_000_000L)return;lastPoll=now;
            Path control=root.resolve("control.json");if(!Files.exists(control))return;
            JsonObject c=JsonParser.parseString(Files.readString(control)).getAsJsonObject();Files.delete(control);
            if(measurement!=null&&(c.has("mode")||c.has("earlyCull")||c.has("build")||c.has("command")||c.has("measure"))){
                Files.writeString(root.resolve("failure.txt"),"Wait for the active measurement before changing the scene");
                return;
            }
            if(c.has("command"))command(c.get("command").getAsString());
            var renderer=IGetVoxyRenderSystem.getNullable();
            if(c.has("build"))System.out.println("FRAGMENT_AERIAL "+renderer.getHybridTerrain().build());
            if(c.has("earlyCull"))renderer.getHybridTerrain().earlyCull(c.get("earlyCull").getAsBoolean());
            if(c.has("mode")){
                String next=c.get("mode").getAsString();if(!Set.of("native","voxel","hybrid").contains(next))throw new IllegalArgumentException("mode");
                mode=next;renderer.getHybridTerrain().enabled(mode.equals("hybrid"));note("ready");
            }
            if(c.has("screenshot"))Screenshot.grab(mc.gameDirectory,safe(c.get("screenshot").getAsString())+".png",mc.getMainRenderTarget(),m->{});
            if(c.has("measure")){
                if(measurement!=null)throw new IllegalStateException("measurement still active");
                measurement=safe(c.get("measure").getAsString());samples.clear();frames=c.has("frames")?c.get("frames").getAsInt():120;
                warmup=c.has("warmup")?c.get("warmup").getAsInt():30;lastFrame=0;
            }
            Files.writeString(root.resolve("hybrid-status.txt"),renderer.getHybridTerrain().status());
            if(c.has("stop")){note("stopped");mc.stop();}
        }catch(Exception error){
            error.printStackTrace();try{Files.writeString(root.resolve("failure.txt"),error.toString());}catch(Exception ignored){}
            ready=false;
        }
    }
    private static String safe(String s){if(!s.matches("[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("unsafe output name");return s;}
    private void frame(RenderFrameEvent.Post e){
        if(measurement==null)return;
        long now=System.nanoTime();double elapsed=lastFrame==0?0:(now-lastFrame)/1e6;lastFrame=now;
        Map<String,Object> row=null;
        if(warmup>0){warmup--;}
        else if(frames>0){
            row=new LinkedHashMap<>();row.put("frame_ms",elapsed);row.put("lod_submit_cpu_ms",lodCpu);row.put("lod_gpu_ms",null);
            samples.add(row);frames--;
        }
        lodCpu=0;
        for(Stamp s:pending)if(!s.attached){s.sample=row;s.attached=true;}
        while(!pending.isEmpty()&&glGetQueryObjecti(pending.peek().end,GL_QUERY_RESULT_AVAILABLE)==GL_TRUE){
            Stamp s=pending.remove();double ms=(glGetQueryObjectui64(s.end,GL_QUERY_RESULT)-glGetQueryObjectui64(s.start,GL_QUERY_RESULT))/1e6;
            if(s.sample!=null)s.sample.put("lod_gpu_ms",ms);glDeleteQueries(s.start);glDeleteQueries(s.end);
        }
        if(warmup==0&&frames==0&&pending.isEmpty()){
            try{
                var mc=Minecraft.getInstance();var r=IGetVoxyRenderSystem.getNullable();
                Map<String,Object> report=new LinkedHashMap<>();report.put("world",world);report.put("mode",mode);
                report.put("gpu",glGetString(GL_RENDERER));report.put("resolution",List.of(mc.getWindow().getWidth(),mc.getWindow().getHeight()));
                report.put("camera",List.of(mc.player.getX(),mc.player.getY(),mc.player.getZ(),mc.player.getYRot(),mc.player.getXRot()));
                report.put("hybrid",r.getHybridTerrain().status());report.put("samples",samples);
                Files.writeString(root.resolve(measurement+".json"),JSON.toJson(report));
                Screenshot.grab(mc.gameDirectory,measurement+".png",mc.getMainRenderTarget(),m->{});
                System.out.println("FRAGMENT_AERIAL_MEASURED "+measurement+" "+samples.size());measurement=null;
            }catch(Exception ex){throw new IllegalStateException(ex);}
        }
    }
}
