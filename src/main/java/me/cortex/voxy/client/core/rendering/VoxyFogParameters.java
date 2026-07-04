package me.cortex.voxy.client.core.rendering;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;

public record VoxyFogParameters(
        float environmentalStart,
        float environmentalEnd,
        float renderStart,
        float renderEnd,
        float red,
        float green,
        float blue,
        float alpha,
        int shape
) {
    private static final float TERRAIN_FOG_EPSILON = 2.0f;
    private static final float TOP_LEVEL_SECTION_SIZE_IN_BLOCKS = 16.0f * 32.0f;

    public static VoxyFogParameters disabled(float renderDistance) {
        return new VoxyFogParameters(renderDistance, renderDistance, renderDistance, renderDistance, 0.0f, 0.0f, 0.0f, 0.0f, FogShape.SPHERE.getIndex());
    }

    public static VoxyFogParameters fromRenderSystem(float renderDistance) {
        float start = RenderSystem.getShaderFogStart();
        float end = RenderSystem.getShaderFogEnd();

        int shape = RenderSystem.getShaderFogShape().getIndex();
        float[] colour = RenderSystem.getShaderFogColor();
        float alpha = colour.length > 3 ? colour[3] : 1.0f;

        float renderEnd = getLodRenderDistance(renderDistance);
        float renderStart = renderEnd - getTerrainFogWidth(renderEnd);

        if (!Float.isFinite(start) || !Float.isFinite(end) || end <= start || start >= Float.MAX_VALUE / 2.0f) {
            return new VoxyFogParameters(renderDistance, renderDistance, renderStart, renderEnd, colour[0], colour[1], colour[2], alpha, shape);
        }

        if (isVanillaTerrainFog(start, end, renderDistance, shape)) {
            return new VoxyFogParameters(renderDistance, renderDistance, renderStart, renderEnd, colour[0], colour[1], colour[2], alpha, shape);
        }

        return new VoxyFogParameters(start, end, renderStart, renderEnd, colour[0], colour[1], colour[2], alpha, shape);
    }

    public static boolean isVanillaTerrainFog(float start, float end, float renderDistance, int shape) {
        if (shape != FogShape.CYLINDER.getIndex() || renderDistance <= 0.0f) {
            return false;
        }

        // Minecraft 1.21.1 only exposes one shader fog range. Sodium 1.21.11 gives Voxy
        // environmental fog separately, so filter the ordinary render-distance terrain fog here.
        float farPlaneDistance = Math.max(renderDistance, 32.0f);
        float fogWidth = getTerrainFogWidth(farPlaneDistance);
        float expectedStart = farPlaneDistance - fogWidth;

        return Math.abs(end - farPlaneDistance) <= TERRAIN_FOG_EPSILON
                && Math.abs(start - expectedStart) <= TERRAIN_FOG_EPSILON;
    }

    private static float getTerrainFogWidth(float farPlaneDistance) {
        return Math.clamp(farPlaneDistance / 10.0f, 4.0f, 64.0f);
    }

    private static float getLodRenderDistance(float fallbackRenderDistance) {
        return Math.max(fallbackRenderDistance, VoxyConfig.CONFIG.sectionRenderDistance * TOP_LEVEL_SECTION_SIZE_IN_BLOCKS);
    }
}
