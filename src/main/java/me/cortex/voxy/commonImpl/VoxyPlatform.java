package me.cortex.voxy.commonImpl;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

public final class VoxyPlatform {
    private VoxyPlatform() {
    }

    public static boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    public static Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static boolean isDedicatedServer() {
        return FMLEnvironment.dist == Dist.DEDICATED_SERVER;
    }

    public static Optional<String> getModVersion(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString());
    }

    public static Optional<String> getModProperty(String modId, String key) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getModProperties().get(key))
                .filter(String.class::isInstance)
                .map(String.class::cast);
    }

    public static Optional<Path> getCodeSourceRoot(Class<?> clazz) {
        try {
            URI uri = clazz.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Optional.of(Path.of(uri));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
