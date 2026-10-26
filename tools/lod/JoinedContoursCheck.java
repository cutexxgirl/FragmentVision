import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.nio.file.*;import java.util.*;
public final class JoinedContoursCheck {
 public static void main(String[] args)throws Exception{
  // Cut faces and cross-cut corners, including a coplanar cliff: exactly one owner.
  float[] p={0,0,0,64,0,0,64,0,64,0,0,64,32,0,0,32,8,0,32,8,64,32,0,64};
  float[] n=new float[p.length];for(int i=0;i<n.length;i+=3)n[i+1]=1;
  var mesh=new SurfaceMesh(p,n,new int[]{0,3,2,0,2,1,4,5,6,4,6,7},new int[]{2048,2048,4096,4096},new boolean[8]);
  var pieces=new ArrayList<SurfaceMesh>();for(int z=0;z<64;z+=32)for(int x=0;x<64;x+=32)pieces.add(TerrainPageClipper.clip(mesh,x,z,32));
  double cliffArea=0;for(var piece:pieces)for(int t=0;t<piece.triangles();t++)if(piece.faceData()[t]==4096){int a=piece.indices()[t*3]*3,b=piece.indices()[t*3+1]*3,c=piece.indices()[t*3+2]*3;cliffArea+=Math.abs((piece.positions()[b+1]-piece.positions()[a+1])*(piece.positions()[c+2]-piece.positions()[a+2])-(piece.positions()[b+2]-piece.positions()[a+2])*(piece.positions()[c+1]-piece.positions()[a+1]))*.5;}
  if(cliffArea!=512)throw new AssertionError("Coplanar cliff duplicated or omitted: "+cliffArea);
  if(args.length>0){
   var s=LodInputPolicy.clean(VoxelSnapshot.read(Path.of(args[0])));var ctx=LayeredTileBake.prepare(s);var tiles=JoinedTerrainBake.build(s,ctx,7,39);var quads=new ArrayList<Long>();int accepted=0;long exactGpu=0;
   for(var tile:tiles){for(var shell:tile.layers())if(shell.name().equals("snow"))exactGpu+=shell.exact().gpuBytes()-shell.choices()[2].quads().gpuBytes();var layer=tile.layers().stream().filter(l->l.name().equals("terrain")).findFirst().orElseThrow();exactGpu+=layer.exact().gpuBytes();for(long q:layer.exact().data())quads.add(PackedQuads.encode(PackedQuads.x(q)+tile.x()-7,PackedQuads.y(q),PackedQuads.z(q)+tile.z()-39,PackedQuads.u(q),PackedQuads.v(q),PackedQuads.face(q),PackedQuads.material(q),PackedQuads.light(q)));}
   var ref=ConformingSurface.extractTerrain(ctx.terrainSource(),7,39,64,new PackedQuads(quads.stream().mapToLong(Long::longValue).toArray()));
   for(int level=1;level<3;level++){
    var cut=new ArrayList<SurfaceMesh>();long gpu=0;int triangles=0;
    for(var tile:tiles){var layer=tile.layers().stream().filter(l->l.name().equals("terrain")).findFirst().orElseThrow();var choice=layer.choices()[level];if(!choice.isMesh())break;cut.add(choice.mesh());gpu+=CompactTerrainLayout.build(choice.mesh(),tile.x(),tile.z()).gpuBytes()+layer.surfaceMap().gpuBytes();triangles+=choice.triangles();}
    if(cut.isEmpty())continue;if(cut.size()!=4)throw new AssertionError("Partially accepted group");accepted++;
    if(gpu>=exactGpu||triangles>=quads.size()*2)throw new AssertionError("Group cost regression");
    var actual=LayeredSeamCheck.contour(TerrainPageClipper.join(cut));var expected=LayeredSeamCheck.contour(ref);if(!actual.equals(expected)){var extra=new HashSet<>(actual);extra.removeAll(expected);var missing=new HashSet<>(expected);missing.removeAll(actual);System.out.println("extra="+extra.size()+" missing="+missing.size()+" examples "+extra.stream().limit(12).toList()+" missing "+missing.stream().limit(6).toList());throw new AssertionError("Open join or changed exterior/cave contour");}
    boolean moved=false;for(var m:cut)for(var edge:LayeredSeamCheck.contour(m))for(var v:new LayeredSeamCheck.Point[]{edge.a(),edge.b()})if(v.x()==39||v.z()==71)moved|=v.y()!=Math.rint(v.y());if(!moved)throw new AssertionError("Internal contour was not actually smoothed");
    System.out.println("Real snow patch L"+level+": shared cut contours closed, external/cave contours exact; triangles "+(quads.size()*2)+" -> "+triangles+", GPU+maps "+exactGpu+" -> "+gpu);
   }if(accepted==0)throw new AssertionError("Real patch must accept a joint candidate");
  }
  System.out.println("Joined page cuts: unique cliff ownership and audited lattice: passed");
 }
}
