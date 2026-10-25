import me.cortex.voxy.client.core.rendering.hybrid.HybridLodPolicy;
public class HybridLodPolicyCheck {
    public static void main(String[] args) {
        int checks=0;
        for(float nativeRadius:new float[]{64,96,128,160}) {
            float near=nativeRadius+48;
            if(HybridLodPolicy.level(near+100,400,near,270,0,0)!=0)throw new AssertionError("morph still active");checks++;
            if(HybridLodPolicy.level(near+160,400,near,270,1,1)!=1)throw new AssertionError("middle");checks++;
            // Last whole tile in the 1088-block window must be able to use the third level.
            if(HybridLodPolicy.level(512,512,near,270,1,1)!=2)throw new AssertionError("far unreachable");checks++;
            if(HybridLodPolicy.level(512,512,near,270,8,1)!=0)throw new AssertionError("coarse pixel budget");checks++;
            if(HybridLodPolicy.level(512,512,near,270,1,8)!=1)throw new AssertionError("far pixel budget");checks++;
        }
        if(HybridLodPolicy.level(512,1200,144,270,3,4)!=2)throw new AssertionError("aerial spatial error");checks++;
        System.out.println("HybridLodPolicyCheck passed: "+checks+" checks");
    }
}
