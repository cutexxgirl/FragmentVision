package me.cortex.voxy.client.core.rendering;

public record VoxyFogParameters(
        float environmentalStart,
        float environmentalEnd,
        float red,
        float green,
        float blue,
        float alpha
) {
    public static VoxyFogParameters disabled(float renderDistance) {
        return new VoxyFogParameters(renderDistance, renderDistance, 0.0f, 0.0f, 0.0f, 0.0f);
    }
}
