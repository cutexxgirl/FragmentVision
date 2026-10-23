import me.cortex.voxy.client.core.rendering.hybrid.HybridSurfaceMesh;
import java.util.Arrays;

/** Standalone checks of the CPU mesher; no game, GL context or test framework required. */
public class HybridSurfaceMeshCheck {
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        int n=33,count=n*n;float[] h=new float[count];int[] layer=new int[count],tint=new int[count];boolean[] valid=new boolean[count];
        Arrays.fill(tint,0xffffff);Arrays.fill(valid,true);
        for(int z=0;z<n;z++)for(int x=0;x<n;x++)h[z*n+x]=80+x*2+z;
        var mesh=HybridSurfaceMesh.build(new HybridSurfaceMesh.Grid(n,4,h,layer,tint,valid));
        require(mesh.vertices().length>0,"ramp generated no triangles");
        require(mesh.vertices().length%33==0,"partial triangle");
        float[] v=mesh.vertices();
        for(int i=0;i<v.length;i+=11){
            for(int k=0;k<11;k++)require(Float.isFinite(v[i+k]),"non-finite vertex");
            if(v[i+4]>0){
                require(Math.abs(v[i+1]-(80+v[i]*.5f+v[i+2]*.25f))<.01f,"planar slope lost");
                require(v[i+4]>.8f&&v[i+3]<0&&v[i+5]<0,"ramp normal wrong");
            }
        }
        // A missing/protected sample must leave the original voxel mesh throughout the safety margin.
        valid[16*n+16]=false;
        var hole=HybridSurfaceMesh.build(new HybridSurfaceMesh.Grid(n,4,h,layer,tint,valid));
        require(hole.coverage()[15*32+15]==0,"protected sample replaced");
        require(hole.vertices().length<mesh.vertices().length,"protected area still meshed");
        for(int i=0;i<hole.vertices().length;i+=33){
            float x=(hole.vertices()[i]+hole.vertices()[i+11]+hole.vertices()[i+22])/12;
            float z=(hole.vertices()[i+2]+hole.vertices()[i+13]+hole.vertices()[i+24])/12;
            require(hole.coverage()[(int)z*32+(int)x]>0,"triangle over fallback area");
        }
        Arrays.fill(valid,false);
        var empty=HybridSurfaceMesh.build(new HybridSurfaceMesh.Grid(n,4,h,layer,tint,valid));
        require(empty.vertices().length==0,"missing cache invented terrain");
        System.out.println("PASS: ramp positions/normals, finite complete triangles, protected margin, missing-cache fallback");
    }
}
