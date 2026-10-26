package me.cortex.voxy.client.core.rendering.lod.bake;
import java.util.*;

/** Cut a jointly simplified patch into bounded GPU pages, on the audited 1/16 grid.
 * Canonical endpoint order makes both sides of every cut publish identical points. */
public final class TerrainPageClipper {
 private record V(float x,float y,float z,float nx,float ny,float nz,boolean locked){float at(int axis){return axis==0?x:z;}}
 private record P(float x,float y,float z)implements Comparable<P>{public int compareTo(P b){int c=Float.compare(x,b.x);if(c!=0)return c;c=Float.compare(y,b.y);return c!=0?c:Float.compare(z,b.z);}}
 private record Edge(P a,P b){}
 public static Map<?,Integer> boundary(SurfaceMesh mesh){
  var edges=new HashMap<Edge,Integer>();for(int t=0;t<mesh.indices().length;t+=3)for(int k=0;k<3;k++){int i=mesh.indices()[t+k]*3,j=mesh.indices()[t+(k+1)%3]*3;var a=new P(mesh.positions()[i],mesh.positions()[i+1],mesh.positions()[i+2]);var b=new P(mesh.positions()[j],mesh.positions()[j+1],mesh.positions()[j+2]);if(a.compareTo(b)>0){var q=a;a=b;b=q;}edges.merge(new Edge(a,b),1,Integer::sum);}edges.values().removeIf(count->count==2);return edges;
 }
 public static SurfaceMesh clip(SurfaceMesh mesh,int x,int z,int width){
  float minX=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,minZ=Float.POSITIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;for(int i=0;i<mesh.positions().length;i+=3){minX=Math.min(minX,mesh.positions()[i]);maxX=Math.max(maxX,mesh.positions()[i]);minZ=Math.min(minZ,mesh.positions()[i+2]);maxZ=Math.max(maxZ,mesh.positions()[i+2]);}
  var points=new ArrayList<V>();var ids=new HashMap<P,Integer>();var indices=new ArrayList<Integer>();var attrs=new ArrayList<Integer>();
  for(int t=0;t<mesh.triangles();t++){
   var polygon=new ArrayList<V>();for(int k=0;k<3;k++){int v=mesh.indices()[t*3+k],i=v*3;polygon.add(new V(mesh.positions()[i],mesh.positions()[i+1],mesh.positions()[i+2],mesh.normals()[i],mesh.normals()[i+1],mesh.normals()[i+2],mesh.locked()[v]));}
   // A face lying exactly on a cut belongs to its solid side, not both pages.
   V a0=polygon.get(0),b0=polygon.get(1),c0=polygon.get(2);
   double nx=(b0.y-a0.y)*(c0.z-a0.z)-(b0.z-a0.z)*(c0.y-a0.y),nz=(b0.x-a0.x)*(c0.y-a0.y)-(b0.y-a0.y)*(c0.x-a0.x);
   if(a0.x==b0.x&&b0.x==c0.x&&a0.x>minX&&a0.x<maxX&&((a0.x==x&&nx>0)||(a0.x==x+width&&nx<0)))continue;
   if(a0.z==b0.z&&b0.z==c0.z&&a0.z>minZ&&a0.z<maxZ&&((a0.z==z&&nz>0)||(a0.z==z+width&&nz<0)))continue;
   for(int axis:new int[]{0,2}){polygon=cut(polygon,axis,axis==0?x:z,true);polygon=cut(polygon,axis,(axis==0?x:z)+width,false);}
   for(int k=1;k+1<polygon.size();k++){
    V a=polygon.get(0),b=polygon.get(k),c=polygon.get(k+1);double ax=b.x-a.x,ay=b.y-a.y,az=b.z-a.z,bx=c.x-a.x,by=c.y-a.y,bz=c.z-a.z;
    if(Math.pow(ay*bz-az*by,2)+Math.pow(az*bx-ax*bz,2)+Math.pow(ax*by-ay*bx,2)<1e-12)continue;
    double dot=(ay*bz-az*by)*nx+(az*bx-ax*bz)*((b0.z-a0.z)*(c0.x-a0.x)-(b0.x-a0.x)*(c0.z-a0.z))+(ax*by-ay*bx)*nz;
    if(dot<=0)throw new IllegalStateException("clip quantization foldover");
    for(V v:new V[]{a,b,c}){var key=new P(v.x,v.y,v.z);int id=ids.computeIfAbsent(key,p->{points.add(v);return points.size()-1;});indices.add(id);}attrs.add(mesh.faceData()[t]);
   }
  }
  float[] p=new float[points.size()*3],n=new float[p.length];boolean[] locked=new boolean[points.size()];
  for(int i=0;i<points.size();i++){V v=points.get(i);p[i*3]=v.x;p[i*3+1]=v.y;p[i*3+2]=v.z;n[i*3]=v.nx;n[i*3+1]=v.ny;n[i*3+2]=v.nz;locked[i]=v.locked;}
  return new SurfaceMesh(p,n,indices.stream().mapToInt(Integer::intValue).toArray(),attrs.stream().mapToInt(Integer::intValue).toArray(),locked);
 }
 private static ArrayList<V> cut(List<V> polygon,int axis,float plane,boolean greater){
  var out=new ArrayList<V>();if(polygon.isEmpty())return out;
  V a=polygon.getLast();boolean ain=greater?a.at(axis)>=plane:a.at(axis)<=plane;
  for(V b:polygon){boolean bin=greater?b.at(axis)>=plane:b.at(axis)<=plane;if(ain!=bin)out.add(intersection(a,b,axis,plane));if(bin)out.add(b);a=b;ain=bin;}return out;
 }
 private static V intersection(V a,V b,int axis,float plane){
  if(a.at(axis)>b.at(axis)){V tmp=a;a=b;b=tmp;}
  double t=(plane-a.at(axis))/(b.at(axis)-a.at(axis));
  float x=quant(a.x+(b.x-a.x)*t),y=quant(a.y+(b.y-a.y)*t),z=quant(a.z+(b.z-a.z)*t);
  double nx=a.nx+(b.nx-a.nx)*t,ny=a.ny+(b.ny-a.ny)*t,nz=a.nz+(b.nz-a.nz)*t,len=Math.sqrt(nx*nx+ny*ny+nz*nz);
  return new V(axis==0?plane:x,y,axis==2?plane:z,len>1e-12?(float)(nx/len):0,len>1e-12?(float)(ny/len):1,len>1e-12?(float)(nz/len):0,a.locked&&b.locked);
 }
 public static SurfaceMesh join(List<SurfaceMesh> meshes){
  int vc=0,tc=0;for(var m:meshes){vc+=m.vertices();tc+=m.triangles();}float[] p=new float[vc*3],n=new float[p.length];boolean[] l=new boolean[vc];int[] ix=new int[tc*3],a=new int[tc];int v=0,t=0;
  for(var m:meshes){System.arraycopy(m.positions(),0,p,v*3,m.positions().length);System.arraycopy(m.normals(),0,n,v*3,m.normals().length);System.arraycopy(m.locked(),0,l,v,m.vertices());for(int i=0;i<m.indices().length;i++)ix[t*3+i]=v+m.indices()[i];System.arraycopy(m.faceData(),0,a,t,m.triangles());v+=m.vertices();t+=m.triangles();}
  return new SurfaceMesh(p,n,ix,a,l);
 }
 private static float quant(double v){return Math.round(v*16)/16f;}
 private TerrainPageClipper(){}
}
