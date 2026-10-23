import me.cortex.voxy.client.core.rendering.hybrid.HybridSurfaceMesh;
import java.util.Arrays;

/** Standalone checks of the CPU mesher; no game, GL context or test framework required. */
public class HybridSurfaceMeshCheck {
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        int n=33,count=n*n;float[] h=new float[count];int[] layer=new int[count],tint=new int[count],light=new int[count];Arrays.fill(light,15);boolean[] valid=new boolean[count];
        Arrays.fill(tint,0xffffff);Arrays.fill(valid,true);
        for(int z=0;z<n;z++)for(int x=0;x<n;x++)h[z*n+x]=80+x*2+z;
        var mesh=HybridSurfaceMesh.build(new HybridSurfaceMesh.Grid(n,4,h,layer,layer,tint,light,valid));
        require(mesh.vertices().length>0,"ramp generated no triangles");
        require(mesh.vertices().length%69==0,"partial triangle");
        float[] v=mesh.vertices();
        for(int i=0;i<v.length;i+=23){
            for(int k=0;k<23;k++)require(Float.isFinite(v[i+k]),"non-finite vertex");
            if(v[i+4]>0){
                require(Math.abs(v[i+1]-(80+v[i]*.5f+v[i+2]*.25f))<.01f,"planar slope lost");
                require(v[i+4]>.8f&&v[i+3]<0&&v[i+5]<0,"ramp normal wrong");
            }
        }
        // A missing/protected sample must leave the original voxel mesh throughout the safety margin.
        valid[16*n+16]=false;
        var hole=HybridSurfaceMesh.build(new HybridSurfaceMesh.Grid(n,4,h,layer,layer,tint,light,valid));
        require(hole.coverage()[15*32+15]==0,"protected sample replaced");
        require(hole.protectedCells()>mesh.protectedCells(),"protected area still meshed");
        for(int i=0;i<hole.vertices().length;i+=69){
            float x=(hole.vertices()[i]+hole.vertices()[i+23]+hole.vertices()[i+46])/12;
            float z=(hole.vertices()[i+2]+hole.vertices()[i+25]+hole.vertices()[i+48])/12;
            require(hole.coverage()[(int)z*32+(int)x]>0,"triangle over fallback area");
        }
        // Layer interpolation keeps snow on level ground and exposes real rock on steep slopes.
        int[] rock=new int[count];Arrays.fill(rock,1);Arrays.fill(valid,true);
        for(int z=0;z<n;z++)for(int x=0;x<n;x++)h[z*n+x]=80+x*8;
        var cliff=HybridSurfaceMesh.build(new HybridSurfaceMesh.Grid(n,4,h,layer,rock,tint,light,valid));
        for(int i=0;i<cliff.vertices().length;i+=23){
            float sum=0,stone=0;
            for(int k=0;k<6;k++){float w=cliff.vertices()[i+15+k];sum+=w;if(cliff.vertices()[i+9+k]==1)stone+=w;}
            require(Math.abs(sum-1)<.001f,"material weights not normalized");
            require(stone>.99f,"snow stretched down a steep rock slope");
        }
        // A high ridge with one cover must still resolve exposed rock lower down its long faces.
        var strata=HybridSurfaceMesh.build(new HybridSurfaceMesh.Grid(n,4,h,layer,layer,tint,light,valid,
                (x,y,z)->y<150?1:2));
        boolean sampledRock=false,sampledUpper=false;
        for(int i=0;i<strata.vertices().length;i+=23){
            if(strata.vertices()[i+4]<=0)continue; // boundary closures have their own substrate material
            float y=strata.vertices()[i+1],sum=0;
            for(int k=0;k<6;k++){
                float w=strata.vertices()[i+15+k];int material=(int)strata.vertices()[i+9+k];sum+=w;
                if(w>.99f&&y<140){require(material==1,"ridge cover leaked onto low rock face");sampledRock=true;}
                if(w>.99f&&y>160){require(material==2,"upper stratum lost");sampledUpper=true;}
            }
            require(Math.abs(sum-1)<.001f,"refined material weights not normalized");
        }
        require(sampledRock&&sampledUpper,"cliff refinement failed to sample both height strata");
        Arrays.fill(valid,false);
        var empty=HybridSurfaceMesh.build(new HybridSurfaceMesh.Grid(n,4,h,layer,layer,tint,light,valid));
        require(empty.vertices().length==0,"missing cache invented terrain");
        System.out.println("PASS: ramp positions/normals, finite complete triangles, protected margin, missing-cache fallback, slope cover and height-resolved cliff strata");
    }
}
