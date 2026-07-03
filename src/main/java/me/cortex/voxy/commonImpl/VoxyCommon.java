package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.client.VoxyClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(VoxyCommon.MOD_ID)
public class VoxyCommon {
    public static final String MOD_ID = "voxy";
    public static String MOD_VERSION = "<UNKNOWN>";
    public static boolean IS_DEDICATED_SERVER = false;
    public static boolean IS_IN_MINECRAFT = false;

    public VoxyCommon(IEventBus modBus, ModContainer mod, Dist dist) {
        IS_IN_MINECRAFT = true;
        var version = mod.getModInfo().getVersion().toString();
        var commit = VoxyPlatform.getModProperty(MOD_ID, "commit").orElse("<UnknownCommit>");
        MOD_VERSION = version + "-" + commit.substring(0, Math.min(7, commit.length()));
        IS_DEDICATED_SERVER = VoxyPlatform.isDedicatedServer();
        Serialization.init();

        if (dist.isClient()) {
            VoxyClient.onInitializeClient();
        }
    }

    //This is hardcoded like this because people do not understand what they are doing
    public static boolean isVerificationFlagOn(String name) {
        return isVerificationFlagOn(name, false);
    }

    public static boolean isVerificationFlagOn(String name, boolean defaultOn) {
        return System.getProperty("voxy."+name, defaultOn?"true":"false").equals("true");
    }

    public static void breakpoint() {
        int breakpoint = 0;
    }

    public interface IInstanceFactory {VoxyInstance create();}
    private static VoxyInstance INSTANCE;
    private static IInstanceFactory FACTORY = null;

    public static void setInstanceFactory(IInstanceFactory factory) {
        if (FACTORY != null) {
            throw new IllegalStateException("Cannot set instance factory more than once");
        }
        FACTORY = factory;
    }

    public static VoxyInstance getInstance() {
        return INSTANCE;
    }

    public static void shutdownInstance() {
        if (INSTANCE != null) {
            var instance = INSTANCE;
            INSTANCE = null;//Make it null before shutdown
            instance.shutdown();
        }
    }

    public static void createInstance() {
        if (FACTORY == null) {
            //Logger.info("Voxy factory");
            return;
        }
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        INSTANCE = FACTORY.create();
    }

    //Is voxy available in any capacity
    public static boolean isAvailable() {
        return FACTORY != null;
    }

    public static final boolean IS_MINE_IN_ABYSS = false;
}
