import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.nio.*;import java.nio.file.*;import java.util.*;
public final class PackedQuadGpuCheck {
 public static void main(String[] args)throws Exception{
  var r=new Random(92);int count=600;long[] data=new long[count];
  for(int i=0;i<count;i++)data[i]=PackedQuads.encode(r.nextInt(64),r.nextInt(512),r.nextInt(64),1+r.nextInt(64),1+r.nextInt(64),i%6,1+r.nextInt(65535),r.nextInt(256));
  var m=new PackedQuads(data).mesh(0,0);ByteBuffer q=ByteBuffer.allocate(count*8).order(ByteOrder.LITTLE_ENDIAN),e=ByteBuffer.allocate(count*4*28).order(ByteOrder.LITTLE_ENDIAN);
  for(int i=0;i<count;i++){q.putLong(data[i]);for(int k=0;k<4;k++){for(int a=0;a<3;a++)e.putFloat(m.positions()[i*12+k*3+a]);for(int a=0;a<3;a++)e.putFloat(m.normals()[i*12+k*3+a]);e.putInt(m.faceData()[i*2]);}}
  Path root=Path.of("build/lod-proof/gpu-layout");Files.createDirectories(root);Files.write(root.resolve("quads.bin"),q.array());Files.write(root.resolve("quad-expected.bin"),e.array());System.out.println("600 packed quad GPU fixtures exported");
 }
}
