import me.cortex.voxy.client.core.rendering.lod.bake.*;import java.nio.file.*;import java.util.*;
/** Independent published endpoint/corner audit, including collapsed fine faces. */
public class CheckLinkedParentsMain {
 static String key(SurfaceMesh m,int t){String[] v=new String[3];for(int k=0;k<3;k++){int i=m.indices()[t*3+k]*3;v[k]=m.positions()[i]+","+m.positions()[i+1]+","+m.positions()[i+2];}int first=0;for(int k=1;k<3;k++)if(v[k].compareTo(v[first])<0)first=k;return m.faceData()[t]+":"+v[first]+":"+v[(first+1)%3]+":"+v[(first+2)%3];}
 static boolean zero(SurfaceMesh m,int t){float[] p=m.positions();int a=m.indices()[t*3]*3,b=m.indices()[t*3+1]*3,c=m.indices()[t*3+2]*3;double[] ab=new double[3],ac=new double[3];for(int k=0;k<3;k++){ab[k]=p[b+k]-p[a+k];ac[k]=p[c+k]-p[a+k];}return Math.abs(ab[1]*ac[2]-ab[2]*ac[1])+Math.abs(ab[2]*ac[0]-ab[0]*ac[2])+Math.abs(ab[0]*ac[1]-ab[1]*ac[0])<1e-10;}
 public static void main(String[] args)throws Exception{int tiles=0,faces=0;var dir=Path.of(args[0]);List<Path> files;try(var f=Files.list(dir)){files=f.filter(p->p.getFileName().toString().startsWith("collar_")&&p.toString().endsWith(".mesh")).sorted().toList();}
  for(var file:files){var d=MeshPage.read(file);String id=d.originX()+"_"+d.originZ();if(!Files.exists(dir.resolve("linked_"+id+".tsv")))continue;var m=d.mesh();var fine=NativeFinePositions.read(dir.resolve("linked_"+id+".fine"),m.vertices());
   for(int i=0;i<m.vertices();i++)if(fine[i*3]==0||fine[i*3]==32||fine[i*3+2]==0||fine[i*3+2]==32)for(int k=0;k<3;k++)if(fine[i*3+k]!=m.positions()[i*3+k])throw new IllegalStateException("Moving contour "+id);
   Map<String,Integer> expanded=new TreeMap<>(),parent=new TreeMap<>();for(int t=0;t<m.triangles();t++){
    if(!zero(m,t))expanded.merge(key(m,t),1,Integer::sum);
    int[] ids={m.indices()[t*3],m.indices()[t*3+1],m.indices()[t*3+2]};int axis=((m.faceData()[t]>>8)&7)/2,u=(axis+1)%3,v=(axis+2)%3;double reference=(fine[ids[1]*3+u]-fine[ids[0]*3+u])*(fine[ids[2]*3+v]-fine[ids[0]*3+v])-(fine[ids[1]*3+v]-fine[ids[0]*3+v])*(fine[ids[2]*3+u]-fine[ids[0]*3+u]);
    for(int mask=0;mask<8;mask++){double[] xx=new double[3],zz=new double[3];for(int k=0;k<3;k++){float[] p=(mask&(1<<k))==0?fine:m.positions();xx[k]=p[ids[k]*3+u];zz[k]=p[ids[k]*3+v];}double area=(xx[1]-xx[0])*(zz[2]-zz[0])-(zz[1]-zz[0])*(xx[2]-xx[0]);if(area*reference< -1e-8)throw new IllegalStateException("Inverting spatial morph "+id+" triangle "+t+" mask "+mask);}
    faces++;
   }
   var compact=MeshPage.read(dir.resolve("parent_"+id+"_0.mesh")).mesh();for(int t=0;t<compact.triangles();t++)if(!zero(compact,t))parent.merge(key(compact,t),1,Integer::sum);if(!parent.equals(expanded))throw new IllegalStateException("Parent endpoint mismatch "+id);tiles++;
  }System.out.println("Linked endpoint audit passed: tiles="+tiles+", faces="+faces+", eight independent morph corners, fixed contours, exact parent face multiset");
 }
}
