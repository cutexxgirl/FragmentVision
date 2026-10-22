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
    private record MaterialApi(Method source, Method bind, Method release, Method colorProvider) {}
    private static final MaterialApi MATERIAL = discoverMaterial();
    private static MaterialApi discoverMaterial() {
        if (API == null) return null;
        try {
            Class<?> type = Class.forName("com.fragmentobnova.fragmentrender.api.AtmosphereBridge");
            return new MaterialApi(type.getMethod("materialShaderSource"), type.getMethod("bindMaterial", int.class), type.getMethod("releaseMaterial"),
                    type.getMethod("blockColorProvider", net.minecraft.world.level.block.state.BlockState.class));
        } catch (ReflectiveOperationException absent) { return null; }
    }
    public static net.minecraft.client.color.block.BlockColor colorProvider(net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.client.color.block.BlockColor fallback) {
        if (MATERIAL == null) return fallback;
        try {
            var provider = (net.minecraft.client.color.block.BlockColor)MATERIAL.colorProvider.invoke(null, state);
            return provider == null ? fallback : provider;
        } catch (ReflectiveOperationException absent) { return fallback; }
    }
    public static String materialSource() {
        if (MATERIAL == null) return "";
        try { return (String)MATERIAL.source.invoke(null); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot load Fragment LOD material", error); }
    }
    public static void bindMaterial(int program) {
        if (MATERIAL == null) return;
        try { MATERIAL.bind.invoke(null, program); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot bind Fragment LOD material", error); }
    }
    public static void releaseMaterial() {
        if (MATERIAL == null) return;
        try { MATERIAL.release.invoke(null); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot release Fragment LOD material", error); }
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
