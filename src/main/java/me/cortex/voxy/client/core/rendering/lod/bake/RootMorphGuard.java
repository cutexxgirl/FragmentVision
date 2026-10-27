package me.cortex.voxy.client.core.rendering.lod.bake;
import java.util.*;
/** Retains original vertex identity through dependent collapses, with a global
 * source-to-current-parent orientation certificate at every accepted endpoint.
 */
final class RootMorphGuard {
 final SurfaceMesh fine;final int[] roots;final float[] target;
 final List<Integer>[] members;final Set<Integer>[] faces;
 @SuppressWarnings("unchecked") RootMorphGuard(SurfaceMesh fine,SurfaceMesh seed){
  if(!Arrays.equals(fine.indices(),seed.indices()))throw new IllegalArgumentException("Morph topology");
  this.fine=fine;target=seed.positions().clone();roots=new int[fine.vertices()];members=(List<Integer>[])new List<?>[roots.length];faces=(Set<Integer>[])new Set<?>[roots.length];
  for(int i=0;i<roots.length;i++){roots[i]=i;members[i]=new ArrayList<>(List.of(i));faces[i]=new HashSet<>();}
  for(int t=0;t<fine.triangles();t++)for(int k=0;k<3;k++)faces[fine.indices()[t*3+k]].add(t);
 }
 boolean allows(int a,int b,double x,double y,double z){
  var affected=new HashSet<>(faces[a]);affected.addAll(faces[b]);double[] proposed={x,y,z};float[] p=fine.positions();
  for(int t:affected){int[] ids={fine.indices()[t*3],fine.indices()[t*3+1],fine.indices()[t*3+2]};int axis=((fine.faceData()[t]>>8)&7)/2,u=(axis+1)%3,v=(axis+2)%3;
   double reference=(p[ids[1]*3+u]-p[ids[0]*3+u])*(p[ids[2]*3+v]-p[ids[0]*3+v])-(p[ids[1]*3+v]-p[ids[0]*3+v])*(p[ids[2]*3+u]-p[ids[0]*3+u]);
   double[][] end=new double[3][2];for(int k=0;k<3;k++){int root=roots[ids[k]];end[k][0]=root==a||root==b?proposed[u]:target[root*3+u];end[k][1]=root==a||root==b?proposed[v]:target[root*3+v];}
   for(int mask=0;mask<8;mask++){double[] xx=new double[3],zz=new double[3];for(int k=0;k<3;k++){boolean move=(mask&(1<<k))!=0;xx[k]=move?end[k][0]:p[ids[k]*3+u];zz[k]=move?end[k][1]:p[ids[k]*3+v];}
    double area=(xx[1]-xx[0])*(zz[2]-zz[0])-(zz[1]-zz[0])*(xx[2]-xx[0]);if(area*reference< -1e-8)return false;
   }
  }return true;
 }
 void collapse(int a,int b,double x,double y,double z){target[a*3]=(float)x;target[a*3+1]=(float)y;target[a*3+2]=(float)z;for(int id:members[b])roots[id]=a;members[a].addAll(members[b]);members[b].clear();faces[a].addAll(faces[b]);faces[b].clear();}
}
