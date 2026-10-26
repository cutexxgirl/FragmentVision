import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.nio.*;import java.nio.file.*;import java.util.*;

/** Source-column materials must survive an unlocked geometry seam, including thin snow. */
public final class SurfaceMaterialMapCheck {
 public static void main(String[] args)throws Exception{
  int size=46,sy=12,cells=size*size*sy;int[] blocks=new int[cells];byte[] natural=new byte[cells],light=new byte[cells];Arrays.fill(light,(byte)240);
  String[] states={"minecraft:air","minecraft:stone","minecraft:grass_block[snowy=false]","minecraft:snow[layers=1]","minecraft:snow[layers=8]","minecraft:oak_leaves[distance=1,persistent=false]"};
  String[] biomes=new String[states.length];Arrays.fill(biomes,"minecraft:plains");
  var s=new VoxelSnapshot(100,-4,200,size,sy,size,states,biomes,blocks,light,natural);
  for(int z=0;z<size;z++)for(int x=0;x<size;x++)for(int y=0;y<=4;y++){int at=s.index(x,y,z);blocks[at]=y==4&&x>=23?2:1;natural[at]=1;}
  for(int z=20;z<26;z++)for(int x=20;x<26;x++)blocks[s.index(x,5,z)]=(x+z)%2==0?3:4;
  // A leaf never replaces the terrain cap. An authored snow stack stays retained.
  blocks[s.index(28,6,28)]=5;blocks[s.index(10,7,10)]=3;
  var mask=LodInputPolicy.projectableSnow(s);var context=LodInputPolicy.terrainContext(s,mask);
  if(context.block(20,5,20)!=0||context.block(28,6,28)!=0||context.block(10,7,10)!=3)throw new AssertionError("Natural cap/retained unsupported snow policy");
  boolean[] terrain=new boolean[states.length];for(int i=0;i<states.length;i++)terrain[i]=LodInputPolicy.terrain(states[i]);
  var cap=SurfaceMaterialMap.build(s,7,7,32,terrain,mask);cap.validate(states.length);
  if(cap.coverError()!=1)throw new AssertionError("Full snow layer thickness bound");
  for(int z=-1;z<35;z++)for(int x=-1;x<35;x++){
   int xx=7+x,zz=7+z,expected=xx>=23?2:1;if(xx>=20&&xx<26&&zz>=20&&zz<26)expected=(xx+zz)%2==0?3:4;
   if(cap.material(x,z)!=expected)throw new AssertionError("Column/halo ID lost, x="+x+", z="+z);
  }
  Path root=Path.of("build/lod-proof/gpu-layout/material-map");Files.createDirectories(root);Path file=root.resolve("map.fvcap");Files.deleteIfExists(file);SurfaceMaterialMap.write(file,cap);
  var restored=SurfaceMaterialMap.read(file);if(!Arrays.equals(cap.words(),restored.words())||Files.size(file)!=32+cap.gpuBytes())throw new AssertionError("Map roundtrip");
  ByteBuffer words=ByteBuffer.allocate(cap.words().length*4).order(ByteOrder.LITTLE_ENDIAN);for(int word:cap.words())words.putInt(word);Files.write(root.resolve("words.bin"),words.array());
  ByteBuffer expected=ByteBuffer.allocate(36*36*4).order(ByteOrder.LITTLE_ENDIAN);for(int z=-1;z<35;z++)for(int x=-1;x<35;x++)expected.putInt(cap.material(x,z));Files.write(root.resolve("expected.bin"),expected.array());
  for(int count:new int[]{17,257}){
   String[] variedStates=new String[count+1],variedBiomes=new String[count+1];boolean[] ground=new boolean[count+1];variedStates[0]="minecraft:air";variedBiomes[0]="minecraft:plains";
   for(int i=1;i<=count;i++){variedStates[i]="minecraft:stone";variedBiomes[i]="fixture:biome_"+i;ground[i]=true;}
   int[] variedBlocks=new int[cells];for(int z=0;z<size;z++)for(int x=0;x<size;x++)variedBlocks[s.index(x,4,z)]=1+(z*size+x)%count;
   var varied=new VoxelSnapshot(100,-4,200,size,sy,size,variedStates,variedBiomes,variedBlocks,light,new byte[cells]);
   var map=SurfaceMaterialMap.build(varied,7,7,32,ground,new byte[cells]);map.validate(count+1);
   if(map.words()[0]!=(count==17?8:16))throw new AssertionError("Variable dictionary bit width");
   ByteBuffer w=ByteBuffer.allocate(map.words().length*4).order(ByteOrder.LITTLE_ENDIAN);for(int word:map.words())w.putInt(word);Files.write(root.resolve("words-"+count+".bin"),w.array());
   ByteBuffer e=ByteBuffer.allocate(36*36*4).order(ByteOrder.LITTLE_ENDIAN);for(int z=-1;z<35;z++)for(int x=-1;x<35;x++){int id=1+((z+7)*size+x+7)%count;if(map.material(x,z)!=id)throw new AssertionError("Wide dictionary source ID");e.putInt(id);}Files.write(root.resolve("expected-"+count+".bin"),e.array());
  }
  // Actual intrinsic layers, not full cubes; covered lower neighbour exposes only the excess side.
  var snowQuads=GreedyQuadMesher.build(s,7,7,32,id->TerrainShapes.snow(states[id]),(x,y,z,f)->mask[s.index(x,y,z)]!=0,true).selected();
  if(snowQuads.data().length==0||Arrays.stream(snowQuads.data()).anyMatch(q->!PackedQuads.shaped(q)))throw new AssertionError("Native snow ABI marker");
  Path shapes=root.resolve("shapes.fvquad");Files.deleteIfExists(shapes);snowQuads.write(shapes,0,0,0);if(!new String(Files.readAllBytes(shapes),0,8).equals("FVQUAD02")||!Arrays.equals(snowQuads.data(),PackedQuads.read(shapes).quads().data()))throw new AssertionError("Shape ABI roundtrip");
  for(int kind=1;kind<=8;kind++)if(TerrainShapes.height("minecraft:snow[layers="+kind+"]")!=kind)throw new AssertionError("Snow height");
  var layers=LayeredTileBake.build(s,7,7,32,false);var cover=layers.stream().filter(l->l.name().equals("snow")).findFirst().orElseThrow();
  if(cover.choices()[0].triangles()==0||cover.choices()[1].triangles()!=0||cover.sourceErrors()[1]!=1||layers.stream().filter(l->l.name().equals("terrain")).findFirst().orElseThrow().surfaceMap()==null)throw new AssertionError("Near shell/far cap layers");
  System.out.println("Source cap IDs/halos, lossless map ABI, retained foliage, native snow shapes and cover error: passed (GPU "+cap.gpuBytes()+" B)");
 }
}
