import me.cortex.voxy.client.core.rendering.hybrid.CoveragePrefix;
import java.util.Random;
public class CoveragePrefixCheck {
    public static void main(String[] args) {
        var rng=new Random(491337);int checks=0,n=17;var p=new CoveragePrefix(n);float[] values=new float[n*n];
        for(int round=0;round<20;round++){
            for(int i=0;i<values.length;i++)values[i]=rng.nextInt(100)<98?1:0;
            if(round==0)java.util.Arrays.fill(values,1);
            p.put(0,0,n,n,values);
            for(int x0=-1;x0<n+1;x0++)for(int z0=-1;z0<n+1;z0++)
                for(int x1=x0;x1<n+1;x1++)for(int z1=z0;z1<n+1;z1++){
                    boolean expected=x0>=0&&z0>=0&&x1<n&&z1<n;
                    if(expected)for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++)expected&=values[z*n+x]>.5;
                    if(p.covered(x0,z0,x1,z1)!=expected)throw new AssertionError("prefix rectangle");checks++;
                }
            p.put(4,3,1,1,new float[]{0});
            if(p.covered(0,0,n-1,n-1))throw new AssertionError("interior hole");checks++;
            p.put(0,0,n,n,new float[n*n]);
            if(p.covered(4,3,4,3))throw new AssertionError("invalidation");checks++;
        }
        System.out.println("CoveragePrefixCheck passed: "+checks+" checks");
    }
}
