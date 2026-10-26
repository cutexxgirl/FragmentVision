import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.util.*;
public final class LodInputPolicyCheck{
 public static void main(String[] args){
  var b=LodComparisonCheck.fixture(0);
  var s=new VoxelSnapshot(b.originX(),b.originY(),b.originZ(),b.sizeX(),b.sizeY(),b.sizeZ(),
   new String[]{"minecraft:air","minecraft:stone","minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]","minecraft:poppy","minecraft:tall_seagrass[half=lower]"},
   new String[]{"minecraft:plains","minecraft:plains","minecraft:plains","minecraft:plains","minecraft:plains"},b.blocks().clone(),b.light(),b.natural().clone());
  int flower=s.index(20,8,20),leaf=s.index(22,8,20),wet=s.index(24,8,20);s.blocks()[flower]=3;s.blocks()[leaf]=2;s.blocks()[wet]=4;
  var clean=LodInputPolicy.clean(s);
  if(s.blocks()[flower]!=3||s.blocks()[wet]!=4)throw new AssertionError("Mutated immutable input");
  if(clean.blocks()[flower]!=0||clean.blocks()[leaf]!=2)throw new AssertionError("Small plants removed, full leaf retained");
  if(!clean.states()[clean.blocks()[wet]].equals("minecraft:water[level=0]"))throw new AssertionError("Aquatic plant must retain water");
  var quads=GreedyQuadMesher.build(clean,7,7,32).selected();boolean exposed=false,retained=false;
  for(long q:quads.data()){
   if(PackedQuads.material(q)==3||PackedQuads.material(q)==4)throw new AssertionError("Plant face survived");
   if(PackedQuads.face(q)==3&&PackedQuads.y(q)==7&&PackedQuads.x(q)<=13&&PackedQuads.x(q)+PackedQuads.u(q)>13&&PackedQuads.z(q)<=13&&PackedQuads.z(q)+PackedQuads.v(q)>13)exposed=true;
   retained|=PackedQuads.material(q)==2;
  }
  if(!exposed||!retained)throw new AssertionError("Ground beneath removed plant must be remeshed; leaf must remain");
  for(String state:new String[]{"minecraft:kelp[age=2]","minecraft:kelp_plant","minecraft:bubble_column[drag=false]"}){
   String[] palette=s.states().clone();palette[4]=state;
   var aquatic=new VoxelSnapshot(s.originX(),s.originY(),s.originZ(),s.sizeX(),s.sizeY(),s.sizeZ(),palette,s.biomes(),s.blocks(),s.light(),s.natural());
   var filtered=LodInputPolicy.clean(aquatic);if(!filtered.states()[filtered.blocks()[wet]].equals("minecraft:water[level=0]"))throw new AssertionError("Lost fluid when removing "+state);
  }
  for(int kind:new int[]{1,3,4,5}){
   var source=LodComparisonCheck.fixture(kind);var exact=ConformingSurface.extract(source,7,7,32,GreedyQuadMesher.build(source,7,7,32).selected());var relaxed=SurfaceRelaxer.relax(exact,.35);
   for(int i=0;i<exact.positions().length;i++){
    if(exact.locked()[i/3]&&(exact.positions()[i]!=relaxed.positions()[i]||exact.normals()[i]!=relaxed.normals()[i]))throw new AssertionError("Protected/material/shared contour moved");
   }
   var layers=LayeredTileBake.build(source,7,7,32,true);
   for(var layer:layers)for(int l=0;l<3;l++)if(layer.choices()[l].isMesh()){
    var choice=layer.choices()[l];if(CompactTerrainLayout.build(choice.mesh(),7,7).gpuBytes()+(layer.surfaceMap()==null?0:layer.surfaceMap().gpuBytes())>=layer.exact().gpuBytes()||choice.triangles()>=layer.exact().triangles()||!layer.errors()[l].withinLimit())throw new AssertionError("Smooth terrain must be strictly cheaper and independently bounded");
   }
  }
  System.out.println("LOD input filtering, revealed ground, retained leaves/water, fixed contours and strict smooth terrain cost: passed");
 }
}
