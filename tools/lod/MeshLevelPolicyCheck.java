import me.cortex.voxy.client.core.rendering.lod.runtime.MeshLevelPolicy;
public final class MeshLevelPolicyCheck {
    public static void main(String[] args){
        double[] distances={0,191.999,192,319.999,320,512,100000};int[] levels={0,0,1,1,2,2,2};
        for(int i=0;i<distances.length;i++)if(MeshLevelPolicy.level(distances[i]*distances[i])!=levels[i])throw new AssertionError("Distance selection");
        for(double invalid:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY}){try{MeshLevelPolicy.level(invalid);throw new AssertionError("Accepted invalid distance");}catch(IllegalArgumentException expected){}}
        if(MeshLevelPolicy.level(255.*255.,192)!=0||MeshLevelPolicy.level(256.*256.,192)!=1||MeshLevelPolicy.level(384.*384.,192)!=2)throw new AssertionError("12 native chunks need a larger exact band");
        double[] error={0,1,2};
        if(MeshLevelPolicy.projectedLevel(error,2000,0,500,-1,2)!=2)throw new AssertionError("Distant page should coarsen");
        if(MeshLevelPolicy.projectedLevel(error,300,0,500,-1,2)!=1)throw new AssertionError("Mid distance");
        if(MeshLevelPolicy.projectedLevel(error,300,0,1000,-1,2)!=0)throw new AssertionError("Zoom/resolution must refine");
        if(MeshLevelPolicy.projectedLevel(error,1,0,500,-1,2)!=0)throw new AssertionError("Eye-plane crossing must refine");
        if(MeshLevelPolicy.projectedLevel(error,550,0,500,0,2)!=1)throw new AssertionError("Hysteresis must delay coarse level");
        if(MeshLevelPolicy.projectedLevel(error,550,0,500,2,2)!=2)throw new AssertionError("Hysteresis must retain valid level");
        if(MeshLevelPolicy.projectedError(1,300,300,500)<=MeshLevelPolicy.projectedError(1,300,0,500))throw new AssertionError("Off-axis projection bound");
        if(MeshLevelPolicy.projectedLevel(new double[]{0,0,0},1,0,500,-1,2)!=2)throw new AssertionError("Exact fallbacks have zero geometric error");
        System.out.println("Three-level distance policy: passed");
    }
}
