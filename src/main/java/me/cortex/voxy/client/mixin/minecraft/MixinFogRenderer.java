package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.VoxyFogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FogRenderer.class, remap = true)
public class MixinFogRenderer {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void voxy$moveTerrainFogToLodEdge(Camera camera, FogRenderer.FogMode fogMode, float farPlaneDistance, boolean shouldCreateFog, float partialTick, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled() || IGetVoxyRenderSystem.getNullable() == null) {
            return;
        }

        if (fogMode != FogRenderer.FogMode.FOG_TERRAIN || shouldCreateFog) {
            return;
        }

        if (!VoxyFogParameters.isVanillaTerrainFog(
                RenderSystem.getShaderFogStart(),
                RenderSystem.getShaderFogEnd(),
                farPlaneDistance,
                RenderSystem.getShaderFogShape().getIndex())) {
            return;
        }

        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        RenderSystem.setShaderFogShape(FogShape.SPHERE);
    }
}
