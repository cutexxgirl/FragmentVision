import me.cortex.voxy.client.core.rendering.hybrid.HybridVoxelMesh;
import java.util.*;

/** Tile seams, actual topology reduction and bounded worst-case fallback. Run with -Xmx96m. */
public final class HybridTileMeshCheck {
    static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    static HybridVoxelMesh.Grid ground(int worldX){
        int n=49,h=384,area=n*n;byte[] m=new byte[area*h],l=new byte[m.length];
        boolean[] valid=new boolean[area];Arrays.fill(valid,true);int[] tint=new int[area];Arrays.fill(tint,0x82976a);Arrays.fill(l,(byte)15);
        for(int z=0;z<n;z++)for(int x=0;x<n;x++){
            int wx=worldX+x*2,wz=-16+z*2,height=110+(int)(Math.sin(wx*.045)*7+Math.sin(wz*.06)*4);
            for(int y=0;y<height;y++)m[y*area+z*n+x]=(byte)(y==height-1?2:1);
        }
        return new HybridVoxelMesh.Grid(n,2,-64,h,m,l,tint,valid,worldX,-16);
    }
    static Map<String,float[]> edge(HybridVoxelMesh.Mesh mesh,int worldX){
        var result=new HashMap<String,float[]>();float[] p=mesh.positions();
        for(int i=0;i<p.length;i+=HybridVoxelMesh.STRIDE){
            if(p[i]+worldX!=64)continue;
            String key=(p[i]+worldX)+":"+p[i+1]+":"+(p[i+2]-16);
            float[] v=new float[12];
            for(int target=1;target<=3;target++){
                int o=i+target*4,j=(target-1)*3;v[j]=p[o]+worldX;v[j+1]=p[o+1];v[j+2]=p[o+2]-16;
            }
            result.put(key,v);
        }
        return result;
    }
    public static void main(String[] args){
        var left=HybridVoxelMesh.build(ground(-16),8,40);var right=HybridVoxelMesh.build(ground(48),8,40);
        var a=edge(left,-16);var b=edge(right,48);require(!a.isEmpty(),"no seam geometry");var common=new HashSet<>(a.keySet());common.retainAll(b.keySet());
        // A vertical face exactly on the split belongs to only one tile. Its
        // interior corners need no duplicate; corners shared by both must agree.
        require(common.size()>=Math.min(a.size(),b.size())*.8,"insufficient shared seam corners");
        for(String k:common)for(int i=0;i<9;i++)require(Math.abs(a.get(k)[i]-b.get(k)[i])<.001f,"seam morph mismatch "+k);
        require(left.coarseIndices().length<left.indices().length,"coarse topology did not remove triangles");
        require(left.farIndices().length<left.coarseIndices().length,"far topology did not further reduce triangles");
        var retained=new HashSet<Integer>();for(int i=0;i<left.coarseIndices().length;i+=3)retained.add(left.coarseIndices()[i]);
        float[] stream=left.positions();
        for(int i=0;i<left.indices().length;i+=3)if(!retained.contains(left.indices()[i]))for(float blend:new float[]{0,.25f,.5f,.75f,1}){
            float[][] triangle=new float[3][3];
            for(int k=0;k<3;k++){
                int p=left.vertices()[left.indices()[i+k]*4]*HybridVoxelMesh.STRIDE;
                for(int axis=0;axis<3;axis++)triangle[k][axis]=stream[p+8+axis]*(1-blend)+stream[p+12+axis]*blend;
            }
            float ux=triangle[1][0]-triangle[0][0],uy=triangle[1][1]-triangle[0][1],uz=triangle[1][2]-triangle[0][2];
            float vx=triangle[2][0]-triangle[0][0],vy=triangle[2][1]-triangle[0][1],vz=triangle[2][2]-triangle[0][2];
            float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
            require(nx*nx+ny*ny+nz*nz<1e-8f,"removed triangle reappears during next LOD morph");
        }
        for(int iteration=0;iteration<12;iteration++){
            var mesh=HybridVoxelMesh.build(ground(iteration*64-16),8,40);
            require(mesh.bytes()<16*1024*1024,"ordinary tile exceeds expected payload");
        }
        var worst=ground(-16);Arrays.fill(worst.material(),(byte)0);int n=worst.points(),area=n*n;
        for(int y=1;y<370;y++)for(int z=2;z<n-2;z+=2)for(int x=2;x<n-2;x+=2)worst.material()[y*area+z*n+x]=1;
        boolean bounded=false;try{HybridVoxelMesh.build(worst,8,40);}catch(HybridVoxelMesh.BudgetExceeded expected){bounded=true;}
        require(bounded,"pathological tile did not stop at the meshing budget");
        System.out.println("PASS: adjacent tile trajectories match; fine/coarse/far triangles="+left.indices().length/3+"/"+left.coarseIndices().length/3+"/"+left.farIndices().length/3+"; repeated 384-high tiles and pathological fallback under 96MiB heap");
    }
}
