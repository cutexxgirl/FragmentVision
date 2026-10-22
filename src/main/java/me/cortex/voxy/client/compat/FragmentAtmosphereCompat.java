package me.cortex.voxy.client.compat;

import me.cortex.voxy.common.Logger;
import java.lang.reflect.Method;

/** Optional, versioned Render bridge. Vision remains usable without Fragment Render. */
public final class FragmentAtmosphereCompat {
    private record Api(Method source, Method active, Method bind, Method release) {}
    private static final Api API = discover();
    private FragmentAtmosphereCompat() {}
    private static Api discover() {
        try {
            Class<?> type = Class.forName("com.fragmentobnova.fragmentrender.api.AtmosphereBridge");
            if ((int)type.getMethod("version").invoke(null) != 1) {
                Logger.warn("Fragment atmosphere protocol unsupported; using Vision fog");
                return null;
            }
            Logger.info("Fragment Render atmosphere protocol v1 linked to Vision LOD composite");
            return new Api(type.getMethod("shaderSource"), type.getMethod("active"),
                    type.getMethod("bind", int.class), type.getMethod("release"));
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException | LinkageError error) {
            Logger.error("Cannot link Fragment atmosphere; using Vision fog", error);
            return null;
        }
    }
    public static String shaderSource() {
        if (API == null) return "";
        try { return "#define FRAGMENT_ATMOSPHERE\n" + (String)API.source.invoke(null); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot load Fragment atmosphere shader", error); }
    }
    public static void bind(int program) {
        if (API == null) return;
        try { API.bind.invoke(null, program); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot bind Fragment atmosphere", error); }
    }
    public static void release() {
        if (API == null) return;
        try { API.release.invoke(null); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot release Fragment atmosphere", error); }
    }
}
