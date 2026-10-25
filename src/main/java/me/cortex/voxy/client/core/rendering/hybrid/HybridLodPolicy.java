package me.cortex.voxy.client.core.rendering.hybrid;

/** Shared CPU/shader transition distances beyond the protected native region. */
public final class HybridLodPolicy {
    public static final float ROUND_END=48, COARSE_END=144, FAR_END=288;
    // Cluster targets may move toward the camera; require the entire tile to finish its morph.
    public static final float TARGET_MARGIN=8;
    private HybridLodPolicy() {}
    public static int level(float horizontalDistance,float spatialDistance,float near,float pixelScale,
                            float coarseError,float farError) {
        float pixels=pixelScale/Math.max(1,spatialDistance);
        if(horizontalDistance<near+COARSE_END+TARGET_MARGIN||coarseError*pixels>2)return 0;
        if(horizontalDistance<near+FAR_END+TARGET_MARGIN||farError*pixels>2)return 1;
        return 2;
    }
}
