import me.cortex.voxy.client.core.rendering.hybrid.HybridVoxelMesh;
import me.cortex.voxy.client.core.util.ScanMesher2D;
import java.util.*;
/** Synthetic solid terrain only. Uses shipping ScanMesher2D, not the complete Voxy pipeline. */
public final class HybridGeometryBenchmark {
 static volatile long sink;
 static final ArrayList<Float> output=new ArrayList<>(); static boolean exporting;
 static void point(float x,float y,float z){output.add(x);output.add(y);output.add(z);}
 static void save(String name) throws Exception {var b=java.nio.ByteBuffer.allocate(output.size()*4).order(java.nio.ByteOrder.LITTLE_ENDIAN);for(float f:output)b.putFloat(f);java.nio.file.Files.write(java.nio.file.Path.of("/workspace/scratch/mesh-bench/"+name+".bin"),b.array());output.clear();}
 static final int N=49,H=128,A=N*N;
 static HybridVoxelMesh.Grid fixture(int kind){
  byte[] m=new byte[A*H],l=new byte[m.length]; Arrays.fill(l,(byte)15);
  int[] t=new int[A];Arrays.fill(t,0x82976a);boolean[] v=new boolean[A];Arrays.fill(v,true);
  for(int z=0;z<N;z++)for(int x=0;x<N;x++){
   int h=switch(kind){case 0->48;case 1->48+(int)(8*Math.sin(x*.18)+6*Math.cos(z*.16));default->48+(int)(12*Math.sin(x*.65)*Math.cos(z*.57));};
   for(int y=0;y<h;y++)m[y*A+z*N+x]=(byte)(y==h-1?2:1);
  }
  return new HybridVoxelMesh.Grid(N,2,0,H,m,l,t,v,-16,-16);
 }
 static class Counter extends ScanMesher2D {int axis,side,d,base; long quads,area; protected void emitQuad(int x,int z,int w,int h,long data){quads++;area+=(long)w*h; if(exporting){int[] aa={x-w+1,x+1,x+1,x-w+1},bb={z-h+1,z-h+1,z+1,z+1};for(int i:new int[]{0,1,2,0,2,3}){float a=(8+aa[i])*2,b=1+base+bb[i],q=axis==1?1+d:d+8; q+=side>0?1:0;if(axis==0)point(q*2,b,a);else if(axis==1)point(a,q,(8+base+bb[i])*2);else point(a,b,q*2);}}}}
 static long[] voxel(HybridVoxelMesh.Grid g){
  Counter c=new Counter();byte[] m=g.material();long faces=0;
  // Each axis slice is split into the same 32x32 scan domains used by Voxy.
  // Equal material/light/tint can merge; no boundary underside or outside-tile faces.
  for(int axis=0;axis<3;axis++)for(int side=-1;side<=1;side+=2){
   int depth=axis==1?H-2:32, rows=axis==1?32:H-2;
   for(int d=0;d<depth;d++)for(int base=0;base<rows;base+=32){
    c.axis=axis;c.side=side;c.d=d;c.base=base;
    for(int b=0;b<32;b++)for(int a=0;a<32;a++){
     int x=axis==0?8+d:8+a, y=axis==1?1+d:1+base+b, z=axis==2?8+d:axis==1?8+base+b:8+a;
     if(axis==0)z=8+a;
     long data=0;
     if(base+b<rows){int p=y*A+z*N+x, delta=axis==0?side:axis==1?side*A:side*N;
      if(m[p]!=0&&m[p+delta]==0){data=m[p]&255;faces++;}}
     c.putNext(data);
    }c.finish();
   }
  }
  if(c.area!=faces)throw new AssertionError("scan coverage mismatch");
  return new long[]{c.quads,faces};
 }
 static double median(double[] v){Arrays.sort(v);return v[v.length/2];}
 public static void main(String[] args) throws Exception {
  System.out.println("{\"scope\":\"synthetic 64x64 blocks, 2x1x2 samples, solid terrain, uniform light/tint, 2 materials; shipping ScanMesher2D vs shipping hybrid builder; CPU stages differ, no game FPS\",\"fixtures\":[");
  for(int k=0;k<3;k++){
   var g=fixture(k);for(int i=0;i<8;i++){sink+=voxel(g)[0];sink+=HybridVoxelMesh.build(g,8,40).bytes();}
   double[] tv=new double[21],th=new double[21];long[] v=null;HybridVoxelMesh.Mesh h=null;
   for(int i=0;i<21;i++){long s=System.nanoTime();v=voxel(g);tv[i]=(System.nanoTime()-s)/1e6;s=System.nanoTime();h=HybridVoxelMesh.build(g,8,40);th[i]=(System.nanoTime()-s)/1e6;sink+=h.bytes()+v[0];}
   exporting=true;voxel(g);exporting=false;save(k+"-voxel");
   int[][] levels={h.indices(),h.coarseIndices(),h.farIndices()};
   for(int level=0;level<3;level++){int offset=level==0?4:level==1?8:12;for(int id:levels[level]){int p=h.vertices()[id*4]*HybridVoxelMesh.STRIDE+offset;point(h.positions()[p],h.positions()[p+1],h.positions()[p+2]);}save(k+"-hybrid-"+level);}
   if(v[1]*2!=h.indices().length/3)throw new AssertionError("different source faces: "+v[1]+" vs "+h.indices().length/6);
   System.out.printf(Locale.ROOT,"%s{\"name\":\"%s\",\"voxel_triangles\":%d,\"unmerged_triangles\":%d,\"hybrid_fine_triangles\":%d,\"hybrid_coarse_triangles\":%d,\"hybrid_far_triangles\":%d,\"voxel_quad_payload_bytes\":%d,\"hybrid_mesh_payload_bytes\":%d,\"scan_and_extract_median_ms\":%.3f,\"hybrid_build_median_ms\":%.3f}",k==0?"":",\n",new String[]{"flat","hills","rugged"}[k],v[0]*2,v[1]*2,h.indices().length/3,h.coarseIndices().length/3,h.farIndices().length/3,v[0]*8,h.bytes(),median(tv),median(th));
  }System.out.println("\n]}");
 }
}
