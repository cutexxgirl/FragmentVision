package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.DebugUtils;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;


public class VoxyCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        var imports = Commands.literal("import")
                .then(Commands.literal("world")
                        .then(Commands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importWorldSuggester)
                                .executes(VoxyCommands::importWorld)))
                .then(Commands.literal("bobby")
                        .then(Commands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importBobbySuggester)
                                .executes(VoxyCommands::importBobby)))
                .then(Commands.literal("raw")
                        .then(Commands.argument("path", StringArgumentType.string())
                                .executes(VoxyCommands::importRaw)))
                .then(Commands.literal("zip")
                        .then(Commands.argument("zipPath", StringArgumentType.string())
                                .executes(VoxyCommands::importZip)
                                .then(Commands.argument("innerPath", StringArgumentType.string())
                                        .executes(VoxyCommands::importZip))))
                .then(Commands.literal("current")
                        .executes(VoxyCommands::importCurrentWorldIn))
                .then(Commands.literal("cancel")
                        .executes(VoxyCommands::cancelImport));

        if (DHImporter.HasRequiredLibraries) {
            imports = imports
                    .then(Commands.literal("distant_horizons")
                    .then(Commands.argument("sqlDbPath", StringArgumentType.string())
                            .executes(VoxyCommands::importDistantHorizons)));
        }

        var debug = Commands.literal("debug")
                .then(Commands.literal("verifyTLNChildMask")
                        .executes(ctx->verifyTLNs(ctx, false))
                        .then(Commands.argument("attemptRepair", BoolArgumentType.bool())
                                .executes(ctx->verifyTLNs(ctx, BoolArgumentType.getBool(ctx, "attemptRepair"))))
                );

        return Commands.literal("voxy")//.requires((ctx)-> VoxyCommon.getInstance() != null)
                .then(Commands.literal("hybrid")
                        .executes(c -> hybrid(c, "status"))
                        .then(Commands.literal("build").executes(c -> hybrid(c, "build")))
                        .then(Commands.literal("on").executes(c -> hybrid(c, "on")))
                        .then(Commands.literal("off").executes(c -> hybrid(c, "off")))
                        .then(Commands.literal("earlyCull").then(Commands.argument("enabled", BoolArgumentType.bool()).executes(c -> hybrid(c, BoolArgumentType.getBool(c, "enabled") ? "early-on" : "early-off")))))
                .then(Commands.literal("mesh").executes(c->mesh(c,"status",null))
                        .then(Commands.literal("colours").executes(c->mesh(c,"colours",null)))
                        .then(Commands.literal("join").then(Commands.argument("enabled",BoolArgumentType.bool()).executes(c->mesh(c,BoolArgumentType.getBool(c,"enabled")?"join-on":"join-off",null))))
                        .then(Commands.literal("submission").then(Commands.argument("mode",com.mojang.brigadier.arguments.IntegerArgumentType.integer(0,3)).executes(c->mesh(c,"submission",null,com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c,"mode")))))
                        .then(Commands.literal("off").executes(c->mesh(c,"off",null)))
                        .then(Commands.literal("on").executes(c->mesh(c,"on",null)))
                        .then(Commands.literal("load").then(Commands.argument("package",com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(c->mesh(c,"load",com.mojang.brigadier.arguments.StringArgumentType.getString(c,"package")))
                                .then(Commands.argument("level",com.mojang.brigadier.arguments.IntegerArgumentType.integer(-1,2)).executes(c->mesh(c,"load",com.mojang.brigadier.arguments.StringArgumentType.getString(c,"package"),com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c,"level")))))))
                .then(Commands.literal("reload")
                        .executes(VoxyCommands::reloadInstance))
                .then(imports)
                .then(debug);
    }

    private static int mesh(CommandContext<CommandSourceStack> ctx,String action,String name) {return mesh(ctx,action,name,-1);}
    private static int mesh(CommandContext<CommandSourceStack> ctx,String action,String name,int level) {
        var renderer=IGetVoxyRenderSystem.getNullable();var backend=renderer==null?null:renderer.getBakedMeshBackend();
        try{String message=backend==null?"Mesh backend unavailable":switch(action){case "load"->backend.load(name,level);case "on"->backend.enabled(true);case "off"->backend.enabled(false);case "colours"->backend.auditColours();case "submission"->backend.submissionMode(level);case "join-on"->backend.nativeJoin(true);case "join-off"->backend.nativeJoin(false);default->backend.status();};ctx.getSource().sendSuccess(()->Component.literal(message),false);return 1;}
        catch(Exception error){ctx.getSource().sendFailure(Component.literal("Mesh proof load failed: "+error));return 0;}
    }

    private static int hybrid(CommandContext<CommandSourceStack> ctx, String action) {
        var renderer = IGetVoxyRenderSystem.getNullable();
        var surface = renderer == null ? null : renderer.getHybridTerrain();
        if(renderer!=null && renderer.getBakedMeshBackend()!=null && renderer.getBakedMeshBackend().active() && (action.equals("on")||action.equals("build"))){ctx.getSource().sendFailure(Component.literal("Baked mesh backend active; use /voxy mesh off before enabling the legacy hybrid"));return 0;}
        String message = surface == null ? "Hybrid WIP unavailable in this renderer" : switch(action) {
            case "build" -> surface.build();
            case "on" -> surface.enabled(true);
            case "off" -> surface.enabled(false);
            case "early-on" -> {surface.earlyCull(true);yield surface.status();}
            case "early-off" -> {surface.earlyCull(false);yield surface.status();}
            default -> surface.status();
        };
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int reloadInstance(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr!=null) {
            ((IGetVoxyRenderSystem)wr).voxy$shutdownRenderer();
        }

        VoxyCommon.shutdownInstance();
        System.gc();
        VoxyCommon.createInstance();

        var r = Minecraft.getInstance().levelRenderer;
        if (r != null) r.allChanged();
        return 0;
    }

    private static int verifyTLNs(CommandContext<CommandSourceStack> ctx, boolean attemptRepair) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (Minecraft.getInstance().level == null) {
            throw new IllegalStateException("How you even do this");
        }
        DebugUtils.verifyAllTopLevelNodes(WorldIdentifier.ofEngine(Minecraft.getInstance().level), attemptRepair);
        return 0;
    }


    private static int importDistantHorizons(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var dbFile = new File(ctx.getArgument("sqlDbPath", String.class));
        if (!dbFile.exists()) {
            return 1;
        }
        if (dbFile.isDirectory()) {
            dbFile = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
            if (!dbFile.exists()) {
                return 1;
            }
        }

        File dbFile_ = dbFile;
        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null)return 1;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->
                new DHImporter(dbFile_, engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter))?0:1;
    }

    private static boolean fileBasedImporter(File directory) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            return false;
        }

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null) return false;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->{
            var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
            importer.importRegionDirectoryAsync(directory);
            return importer;
        });
    }

    private static int importRaw(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        return fileBasedImporter(new File(ctx.getArgument("path", String.class)))?0:1;
    }

    private static int importBobby(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var file = new File(".bobby").toPath().resolve(ctx.getArgument("world_name", String.class)).toFile();
        return fileBasedImporter(file)?0:1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), sb);
    }
    private static CompletableFuture<Suggestions> importBobbySuggester(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby"), sb);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
        var str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0,str.length()-1);
        }
        var remaining = str;
        if (str.contains("/")) {
            int idx = str.lastIndexOf('/');
            remaining = str.substring(idx+1);
            try {
                dir = dir.resolve(str.substring(0, idx));
            } catch (Exception e) {
                return Suggestions.empty();
            }
            str = str.substring(0, idx+1);
        } else {
            str = "";
        }

        try {
            var worlds = Files.list(dir).toList();
            for (var world : worlds) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                var wn = world.getFileName().toString();
                if (wn.equals(remaining)) {
                    continue;
                }
                if (SharedSuggestionProvider.matchesSubStr(remaining, wn) || SharedSuggestionProvider.matchesSubStr(remaining, '"'+wn)) {
                    wn = str+wn + "/";
                    sb.suggest(StringArgumentType.escapeIfRequired(wn));
                }
            }
        } catch (IOException e) {}

        return sb.buildFuture();
    }


    private static int importCurrentWorldIn(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var localServer = Minecraft.getInstance().getSingleplayerServer();
        if (localServer == null) {
            ctx.getSource().sendFailure(Component.translatable("You must be in single player to use this command"));
            return 1;
        }
        var regionPath = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), localServer.getWorldPath(LevelResource.ROOT)).resolve("region");
        if ((!regionPath.toFile().exists())||!regionPath.toFile().isDirectory()) {
            ctx.getSource().sendFailure(Component.translatable("Cannot find region folder for current dimension"));
            return 1;
        }
        return fileBasedImporter(regionPath.toFile())?0:1;
    }

    private static int importWorld(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var name = ctx.getArgument("world_name", String.class);
        var file = new File("saves").toPath().resolve(name);
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith("/")) {
            name = name.substring(0, name.length()-1);
        }
        if (file.resolve("level.dat").toFile().exists()) {
            var dimFile = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), file)
                    .resolve("region")
                    .toFile();
            if (!dimFile.isDirectory()) return 1;
            return fileBasedImporter(dimFile)?0:1;
            //We are in a world directory, so import the current dimension we are in
            /*
            for (var dim : new String[]{"overworld", "the_nether", "the_end"}) {//This is so annoying that you cant loop through all the dimensions
                var id = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace(dim));
                var dimPath = DimensionType.getStorageFolder(id, file);
                dimPath = dimPath.resolve("region");
                var dimFile = dimPath.toFile();
                if (dimFile.isDirectory()) {//exists and is a directory
                    if (!fileBasedImporter(dimFile)) {
                        Logger.error("Failed to import dimension: " + id);
                    }
                }
            }*/
        } else {
            if (!(name.endsWith("region"))) {
                file = file.resolve("region");
            }
            return fileBasedImporter(file.toFile()) ? 0 : 1;
        }
    }

    private static int importZip(CommandContext<CommandSourceStack> ctx) {
        var zip =  new File(ctx.getArgument("zipPath", String.class));
        var innerDir = "region/";
        try {
            innerDir = ctx.getArgument("innerPath", String.class);
        } catch (Exception e) {}

        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        String finalInnerDir = innerDir;

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine != null) {
            return instance.getImportManager().makeAndRunIfNone(engine, () -> {
                var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
                importer.importZippedRegionDirectoryAsync(zip, finalInnerDir);
                return importer;
            }) ? 0 : 1;
        }
        return 1;
    }

    private static int cancelImport(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (world != null) {
            return instance.getImportManager().cancelImport(world)?0:1;
        }
        return 1;
    }
}
