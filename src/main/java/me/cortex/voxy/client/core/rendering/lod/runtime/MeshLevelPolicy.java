package me.cortex.voxy.client.core.rendering.lod.runtime;

/** Positional LOD selection. Runtime uses projected certified error and hysteresis.
 * Legacy distance helpers are retained for reproducible earlier experiments.
 * This does not certify material/normal error or implement temporal morph.
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
    /** Conservative perspective displacement estimate for a view-space bounding box.
     * Includes lateral motion as well as depth motion. No coarse level is selected
     * when its error volume touches the eye plane. Error is positional only.
     */
    public static double projectedError(double error, double nearDepth, double lateral, double focalPixels) {
        if(!Double.isFinite(error)||error<0||!Double.isFinite(nearDepth)
                ||!Double.isFinite(lateral)||lateral<0||!Double.isFinite(focalPixels)||focalPixels<=0)
            throw new IllegalArgumentException("Invalid projection bounds");
        if(error==0)return 0;
        if(nearDepth<=error)return Double.POSITIVE_INFINITY;
        return focalPixels*error*(1+lateral/nearDepth)/(nearDepth-error);
    }
    /** Refine immediately; require 20% headroom before coarsening to avoid flicker. */
    public static int projectedLevel(double[] errors,double nearDepth,double lateral,double focalPixels,
                                     int previous,double pixelBudget) {
        if(errors.length!=3||previous< -1||previous>2||!Double.isFinite(pixelBudget)||pixelBudget<=0)
            throw new IllegalArgumentException("Invalid level policy");
        int chosen=0;
        for(int level=1;level<3;level++) {
            double threshold=previous>=0&&level>previous?pixelBudget*.8:pixelBudget;
            if(projectedError(errors[level],nearDepth,lateral,focalPixels)<=threshold)chosen=level;
        }
        return chosen;
    }
    private MeshLevelPolicy(){}
}
