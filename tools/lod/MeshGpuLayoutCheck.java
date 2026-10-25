import me.cortex.voxy.client.core.rendering.lod.bake.MeshGpuLayout;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
public final class MeshGpuLayoutCheck {
 public static void main(String[] args)throws Exception {
  int[][] normals={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};var random=new Random(47);ByteBuffer b=ByteBuffer.allocate(6000*8).order(ByteOrder.LITTLE_ENDIAN);ByteBuffer expected=ByteBuffer.allocate(6000*24).order(ByteOrder.LITTLE_ENDIAN);
  for(int i=0;i<6000;i++){float x=random.nextInt(32768)/256f-1,y=random.nextInt(131072)/256f-1,z=random.nextInt(32768)/256f-1;int[] n=normals[i%6];long packed=MeshGpuLayout.pack(x,y,z,n[0],n[1],n[2]);if(!Arrays.equals(MeshGpuLayout.position(packed),new float[]{x,y,z}))throw new AssertionError("GPU position loss");b.putLong(packed);expected.putFloat(x).putFloat(y).putFloat(z);for(int v:n)expected.putFloat(v);}
  for(float bad:new float[]{Float.NaN,-2,128,.001f})try{MeshGpuLayout.pack(bad,0,0,0,1,0);throw new AssertionError("accepted unsupported position");}catch(IllegalArgumentException ok){}
  Path root=Path.of("build/lod-proof/gpu-layout");Files.createDirectories(root);Files.write(root.resolve("vertices.bin"),b.array());Files.write(root.resolve("expected.bin"),expected.array());System.out.println("MeshGpuLayoutCheck passed: 6000 exact 8-byte vertices and invalid coordinate refusals");
 }
}
