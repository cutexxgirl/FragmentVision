import me.cortex.voxy.client.core.rendering.hybrid.HybridVoxelMesh;
import java.util.*;

/** Geometry invariants of the volume/morph pilot, independent of Minecraft and GL. */
public class HybridVoxelMeshCheck {
    static void require(boolean x,String s){if(!x)throw new AssertionError(s);}
    public static void main(String[] args){
        int n=25,h=24,area=n*n;byte[] material=new byte[area*h],light=new byte[material.length];
        int[] tint=new int[area];boolean[] valid=new boolean[area];Arrays.fill(valid,true);Arrays.fill(tint,0x789b61);Arrays.fill(light,(byte)15);
        // A stone box with a snow layer and a true tunnel underneath the top surface.
        for(int y=3;y<=15;y++)for(int z=6;z<=17;z++)for(int x=6;x<=17;x++){
            if(y>=6&&y<=9&&x>=10&&x<=12)continue;
            material[y*area+z*n+x]=(byte)(y==15?2:1);
        }
        material[7*area+8*n+8]=0; // A sealed cavity must not consume exterior geometry.
        var g=new HybridVoxelMesh.Grid(n,2,0,h,material,light,tint,valid,0,0);
        var mesh=HybridVoxelMesh.build(g);float[] v=mesh.positions();int[] attributes=mesh.vertices();int stride=HybridVoxelMesh.STRIDE;
        require(v.length>0&&v.length%stride==0&&mesh.indices().length%3==0,"invalid indexed surface");
        var trajectories=new HashSet<String>();boolean ceiling=false,stone=false,snow=false;
        for(int i=0;i<v.length;i+=stride){
            for(int k=0;k<stride;k++)require(Float.isFinite(v[i+k]),"non-finite vertex");
            require(trajectories.add(v[i]+":"+v[i+1]+":"+v[i+2]),"duplicate trajectories for shared position");
            require(!(v[i]>=16&&v[i]<=18&&v[i+1]>=7&&v[i+1]<=8&&v[i+2]>=16&&v[i+2]<=18),"sealed underground cavity emitted");
            float dx=v[i+4]-v[i],dy=v[i+5]-v[i+1],dz=v[i+6]-v[i+2];
            require(dx*dx+dy*dy+dz*dz<=1.563f,"surface relaxation exceeds displacement budget");
            if(v[i+3]>0)for(int j=0;j<3;j++)for(int k=1;k<=3;k++)require(v[i+j]==v[i+j+k*4],"boundary moves during morph");
        }
        for(int i=0;i<attributes.length;i+=4){
            int p=attributes[i]*stride;require(p>=0&&p<v.length,"invalid shared vertex id");
            int layer=attributes[i+1];require(layer==0||layer==1,"invented material");stone|=layer==0;snow|=layer==1;
            if((attributes[i+3]&7)==2&&v[p+1]==10)ceiling=true;
        }
        for(int index:mesh.indices())require(index>=0&&index<attributes.length/4,"invalid triangle index");
        require(ceiling,"volume mesher lost tunnel ceiling");require(stone&&snow,"lost separate materials");
        Arrays.fill(valid,false);require(HybridVoxelMesh.build(g).vertices().length==0,"masked cache created geometry");
        System.out.println("PASS: retained tunnel ceiling, discrete snow/rock, shared morph trajectories, finite triangles, boundary locking, missing-cache fallback");
    }
}
