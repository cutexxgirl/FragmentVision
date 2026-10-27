import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.nio.file.*;
public final class NativeCollarMeshCheck {
 static double[] normal(float[] p,int a,int b,int c){double x=p[b*3]-p[a*3],y=p[b*3+1]-p[a*3+1],z=p[b*3+2]-p[a*3+2],u=p[c*3]-p[a*3],v=p[c*3+1]-p[a*3+1],w=p[c*3+2]-p[a*3+2];return new double[]{y*w-z*v,z*u-x*w,x*v-y*u};}
 public static void main(String[] args)throws Exception{
  long triangles=0;int pages=0;double maxDisplacement=0;
  try(var files=Files.list(Path.of(args[0]))){for(var path:files.filter(p->p.toString().endsWith(".mesh")).sorted().toList()){
   var mesh=MeshPage.read(path).mesh();float[] fine=mesh.positions().clone();for(int i=0;i<fine.length;i++){fine[i]=Math.round(fine[i]);maxDisplacement=Math.max(maxDisplacement,Math.abs(mesh.positions()[i]-fine[i]));}
   for(int i=0;i<mesh.vertices();i++)if(fine[i*3]==0||fine[i*3]==32||fine[i*3+2]==0||fine[i*3+2]==32)for(int k=0;k<3;k++)if(mesh.positions()[i*3+k]!=fine[i*3+k])throw new AssertionError("Moved page contour: "+path);
   for(int t=0;t<mesh.indices().length;t+=3){int a=mesh.indices()[t],b=mesh.indices()[t+1],c=mesh.indices()[t+2];double[] reference=normal(fine,a,b,c);float[] p=new float[9];
    // Projected signed area is multi-affine in three independent vertex weights.
    // Its minimum on [0,1]^3 lies at these eight corners, so this covers ALL weights.
    for(int mask=0;mask<8;mask++){
     int[] ids={a,b,c};for(int v=0;v<3;v++)for(int k=0;k<3;k++)p[v*3+k]=((mask>>v)&1)==0?fine[ids[v]*3+k]:mesh.positions()[ids[v]*3+k];
     double[] n=normal(p,0,1,2);double dot=0;for(int k=0;k<3;k++)dot+=n[k]*reference[k];if(!(dot>1e-10))throw new AssertionError("Morph inversion: "+path+" triangle="+(t/3)+" mask="+mask);
    }
   }triangles+=mesh.triangles();pages++;
  }}
  if(pages==0)throw new AssertionError("No collar fixtures");System.out.println("Native collars: "+pages+" pages, "+triangles+" triangles, all independent morph weights preserve orientation; exact shared contours; max component displacement="+maxDisplacement);
 }
}
