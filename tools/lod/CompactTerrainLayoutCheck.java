import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
public final class CompactTerrainLayoutCheck{
 static String key(int a,int b,int c,int attr){if(b<a&&b<c)return b+","+c+","+a+":"+attr;if(c<a&&c<b)return c+","+a+","+b+":"+attr;return a+","+b+","+c+":"+attr;}
 static void write(Path p,ByteBuffer b)throws Exception{Files.write(p,Arrays.copyOf(b.array(),b.position()));}
 public static void main(String[] args)throws Exception{
  var s=LodComparisonCheck.fixture(1);var raw=ConformingSurface.extract(s,7,7,32,GreedyQuadMesher.build(s,7,7,32).selected());
  var terrain=SurfaceRelaxer.relax(raw,.35,16);float[] positions=terrain.positions().clone();for(int i=0;i<positions.length;i+=3){positions[i]-=7;positions[i+2]-=7;}
  int[] attrs=new int[terrain.triangles()];for(int t=0;t<attrs.length;t++)attrs[t]=(t<<11)|((t%6)<<8)|(t%256);
  var mesh=new SurfaceMesh(positions,terrain.normals(),terrain.indices(),attrs,terrain.locked());var layout=CompactTerrainLayout.build(mesh);
  var expected=new HashMap<String,Integer>();for(int t=0;t<mesh.triangles();t++)expected.merge(key(mesh.indices()[t*3],mesh.indices()[t*3+1],mesh.indices()[t*3+2],attrs[t]),1,Integer::sum);
  int a=-1,b=-1,parity=0,primitive=0,restarts=0;
  for(int index:layout.indices()){
   if(index<0){a=b=-1;parity=0;restarts++;continue;}
   if(a<0){a=index;continue;}if(b<0){b=index;continue;}
   int x=parity%2==0?a:b,y=parity%2==0?b:a;String k=key(x,y,index,CompactTerrainLayout.attribute(layout,primitive++));
   int count=expected.getOrDefault(k,0);if(count==0)throw new AssertionError("Changed winding, triangle or attribute");if(count==1)expected.remove(k);else expected.put(k,count-1);
   a=b;b=index;parity++;
  }
  if(!expected.isEmpty()||primitive!=mesh.triangles()||restarts<1||layout.attributeBits()!=16)throw new AssertionError("Missing coverage/restart/dictionary fixture");
  var root=Path.of("build/lod-proof/gpu-layout/compact");Files.createDirectories(root);
  ByteBuffer v=ByteBuffer.allocate(layout.vertices().length*2).order(ByteOrder.LITTLE_ENDIAN);for(short x:layout.vertices())v.putShort(x);write(root.resolve("vertices.bin"),v);
  ByteBuffer old=ByteBuffer.allocate(mesh.vertices()*8).order(ByteOrder.LITTLE_ENDIAN),exp=ByteBuffer.allocate(mesh.vertices()*24).order(ByteOrder.LITTLE_ENDIAN);
  for(int i=0;i<mesh.vertices();i++){
   old.putLong(MeshGpuLayout.pack(mesh.positions()[i*3],mesh.positions()[i*3+1],mesh.positions()[i*3+2],mesh.normals()[i*3],mesh.normals()[i*3+1],mesh.normals()[i*3+2]));
   for(int c=0;c<3;c++)exp.putFloat(mesh.positions()[i*3+c]);for(int c=0;c<3;c++)exp.putFloat(mesh.normals()[i*3+c]);
  }write(root.resolve("legacy-vertices.bin"),old);write(root.resolve("expected.bin"),exp);
  for(var entry:Map.of("indices.bin",layout.indices(),"legacy-indices.bin",mesh.indices(),"attributes.bin",layout.attributes(),"legacy-attributes.bin",attrs).entrySet()){
   ByteBuffer bytes=ByteBuffer.allocate(entry.getValue().length*4).order(ByteOrder.LITTLE_ENDIAN);for(int value:entry.getValue())bytes.putInt(value);write(root.resolve(entry.getKey()),bytes);
  }
  // Small dictionary follows the same geometry and strip order, exercising 8-bit fetch too.
  var smallMesh=new SurfaceMesh(mesh.positions(),mesh.normals(),mesh.indices(),new int[attrs.length],mesh.locked());var small=CompactTerrainLayout.build(smallMesh);
  ByteBuffer bytes=ByteBuffer.allocate(small.attributes().length*4).order(ByteOrder.LITTLE_ENDIAN);for(int value:small.attributes())bytes.putInt(value);write(root.resolve("small-attributes.bin"),bytes);
  Files.writeString(root.resolve("metadata.json"),"{\"vertices\":"+mesh.vertices()+",\"triangles\":"+mesh.triangles()+",\"indices\":"+layout.indices().length+",\"dictionary\":"+layout.dictionarySize()+",\"bits\":"+layout.attributeBits()+"}");
  System.out.println("Compact terrain: exact oriented triangle/attribute coverage, restart strips, 6-byte vertices; tri="+primitive+", restart="+restarts+", GPU="+layout.gpuBytes());
 }
}
