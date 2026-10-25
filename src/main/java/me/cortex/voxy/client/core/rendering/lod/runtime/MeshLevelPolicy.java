package me.cortex.voxy.client.core.rendering.lod.runtime;

/** All levels use the same locked unit contour, so neighbouring selections share geometry.
 * Distances are to the tile footprint, not its origin; vertical camera motion does not change LOD.
 * Fixed thresholds for this WIP; screen-space error and temporal morph remain separate work.
 */
public final class MeshLevelPolicy {
    public static int level(double distanceSquared){
        if(!Double.isFinite(distanceSquared)||distanceSquared<0)throw new IllegalArgumentException("Invalid tile distance");
        return level(distanceSquared,128);
    }
    public static int level(double distanceSquared,int nativeDistanceBlocks){
        if(!Double.isFinite(distanceSquared)||distanceSquared<0||nativeDistanceBlocks<0||nativeDistanceBlocks>4096)throw new IllegalArgumentException("Invalid LOD distance");
        double near=Math.max(192,nativeDistanceBlocks+64),far=Math.max(320,near+128);
        return distanceSquared<near*near?0:distanceSquared<far*far?1:2;
    }
    private MeshLevelPolicy(){}
}
