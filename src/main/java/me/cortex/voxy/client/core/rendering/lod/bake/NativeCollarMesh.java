package me.cortex.voxy.client.core.rendering.lod.bake;

/** Same-topology near bevel. Integer fine endpoint is recoverable by rounding.
 * Each triangle is tested at all eight independent source/target vertex choices.
 * Projected signed area is multi-affine: positive at these corners means positive
 * for ALL morph weights in [0,1]^3. Failed stars backtrack before publication.
 */
public final class NativeCollarMesh {
 public static SurfaceMesh target(SurfaceMesh fine){
  var relaxed=SurfaceRelaxer.relax(fine,.35,256);float[] p=relaxed.positions().clone();
  for(int attempt=0;attempt<12;attempt++){
   boolean[] bad=new boolean[fine.vertices()];boolean found=false;
   for(int t=0;t<fine.triangles();t++)if(!safe(fine,p,t)){
    found=true;for(int k=0;k<3;k++)bad[fine.indices()[t*3+k]]=true;
   }
   if(!found){
    for(int i=0;i<p.length;i++)if(Math.round(p[i])!=fine.positions()[i])throw new IllegalArgumentException("Noninteger fine endpoint");
    return new SurfaceMesh(p,relaxed.normals(),fine.indices(),fine.faceData(),fine.locked());
   }
   for(int v=0;v<bad.length;v++)if(bad[v])for(int k=0;k<3;k++){int i=v*3+k;p[i]=Math.round((p[i]+fine.positions()[i])*.5*256)/256f;}
  }
  // Atomic exact fallback if no safe bevel is found within the offline budget.
  return fine;
 }
 private static boolean safe(SurfaceMesh fine,float[] target,int triangle){
  int a=fine.indices()[triangle*3],b=fine.indices()[triangle*3+1],c=fine.indices()[triangle*3+2];
  int axis=((fine.faceData()[triangle]>>8)&7)/2,i=(axis+1)%3,j=(axis+2)%3;float[] p=fine.positions();
  double reference=(p[b*3+i]-p[a*3+i])*(p[c*3+j]-p[a*3+j])-(p[b*3+j]-p[a*3+j])*(p[c*3+i]-p[a*3+i]);
  for(int mask=0;mask<8;mask++){
   float[] pa=(mask&1)==0?p:target,pb=(mask&2)==0?p:target,pc=(mask&4)==0?p:target;
   double area=(pb[b*3+i]-pa[a*3+i])*(pc[c*3+j]-pa[a*3+j])-(pb[b*3+j]-pa[a*3+j])*(pc[c*3+i]-pa[a*3+i]);
   if(area*reference<=1e-10)return false;
  }return true;
 }
 private NativeCollarMesh(){}
}
