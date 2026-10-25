import me.cortex.voxy.client.core.rendering.hybrid.HybridGpuLayout;
import java.util.Random;
public class HybridGpuLayoutCheck {
    public static void main(String[] args){
        var rng=new Random(81294);float[] source=new float[20*8192];
        for(int i=0;i<source.length;i++)source[i]=Float.intBitsToFloat(rng.nextInt());
        float[] packed=HybridGpuLayout.compactPositions(source);int checks=0;
        int[] mapping={0,1,2,3,4,5,6,16,8,9,10,17,12,13,14,18};
        for(int v=0;v<source.length/20;v++)for(int k=0;k<16;k++){
            if(Float.floatToRawIntBits(packed[v*16+k])!=Float.floatToRawIntBits(source[v*20+mapping[k]]))throw new AssertionError("changed vertex bits");checks++;
        }
        if(HybridGpuLayout.positionBytes(source.length)!=packed.length*4L)throw new AssertionError("GPU byte accounting");checks++;
        if(HybridGpuLayout.compactPositions(new float[0]).length!=0)throw new AssertionError("empty");checks++;
        try{HybridGpuLayout.compactPositions(new float[21]);throw new AssertionError("bad stride");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("HybridGpuLayoutCheck passed: "+checks+" checks; 80 -> 64 bytes per shared position, bit-exact");
    }
}
