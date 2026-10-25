package me.cortex.voxy.client.core.rendering.hybrid;

/**
 * GPU-only packing: four vec4 fetches instead of five, without quantization.
 * The CPU mesh retains its original 20-float stride. The GPU stores the normal
 * in the unused w components of the three morph targets; position.w is the lock.
 * Keep this mapping in sync with lod/hybrid/surface.vert.
 */
public final class HybridGpuLayout {
    private static final int SOURCE_STRIDE = 20;
    private static final int GPU_STRIDE = 16;

    private HybridGpuLayout() {}

    public static float[] compactPositions(float[] source) {
        if (source.length % SOURCE_STRIDE != 0) throw new IllegalArgumentException("hybrid vertex stride");
        float[] packed = new float[source.length / SOURCE_STRIDE * GPU_STRIDE];
        for (int i = 0, o = 0; i < source.length; i += SOURCE_STRIDE, o += GPU_STRIDE) {
            System.arraycopy(source, i, packed, o, 4);
            System.arraycopy(source, i + 4, packed, o + 4, 3);
            packed[o + 7] = source[i + 16];
            System.arraycopy(source, i + 8, packed, o + 8, 3);
            packed[o + 11] = source[i + 17];
            System.arraycopy(source, i + 12, packed, o + 12, 3);
            packed[o + 15] = source[i + 18];
        }
        return packed;
    }

    public static long positionBytes(int sourceFloats) {
        if (sourceFloats < 0 || sourceFloats % SOURCE_STRIDE != 0) throw new IllegalArgumentException("hybrid vertex stride");
        return (long) (sourceFloats / SOURCE_STRIDE) * GPU_STRIDE * Float.BYTES;
    }
}
