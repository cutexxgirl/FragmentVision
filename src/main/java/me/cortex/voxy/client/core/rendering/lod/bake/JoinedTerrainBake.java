package me.cortex.voxy.client.core.rendering.lod.bake;
import java.util.*;

/** Four pages share one simplification and one runtime LOD decision. Outer/cave
 * contours stay exact; interior page cuts are made AFTER simplification. */
public final class JoinedTerrainBake {
 public record Tile(int x,int z,List<LayeredTileBake.Layer> layers){}
 public static List<Tile> build(VoxelSnapshot s,LayeredTileBake.Context ctx,int x,int z){
  var tiles=new ArrayList<Tile>();var grounds=new ArrayList<LayeredTileBake.Layer>();var caps=new ArrayList<SurfaceMaterialMap.Data>();var quads=new ArrayList<Long>();long exactGpu=0,snowFarGpu=0;int snowTriangles=0;double cover=0;
  for(int dz=0;dz<64;dz+=32)for(int dx=0;dx<64;dx+=32){
   var layers=LayeredTileBake.build(s,ctx,x+dx,z+dz,32,false);tiles.add(new Tile(x+dx,z+dz,layers));var ground=layers.stream().filter(l->l.name().equals("terrain")).findFirst().orElse(null);
   if(ground==null)return exact(s,ctx,x,z);grounds.add(ground);exactGpu+=ground.exact().gpuBytes();
   for(var layer:layers)if(layer.name().equals("snow")){exactGpu+=layer.exact().gpuBytes();snowTriangles+=layer.exact().triangles();snowFarGpu+=layer.choices()[2].quads().gpuBytes();}
   var cap=SurfaceMaterialMap.build(s,x+dx,z+dz,32,ctx.terrain(),ctx.snow());caps.add(cap);cover=Math.max(cover,cap.coverError());
   for(long q:ground.exact().data())quads.add(PackedQuads.encode(PackedQuads.x(q)+dx,PackedQuads.y(q),PackedQuads.z(q)+dz,PackedQuads.u(q),PackedQuads.v(q),PackedQuads.face(q),PackedQuads.material(q),PackedQuads.light(q)));
  }
  var combined=new PackedQuads(quads.stream().mapToLong(Long::longValue).toArray());var accepted=new SurfaceMesh[3][];var errors=new SurfaceError.Result[3];
  try{
   var ref=ConformingSurface.extractTerrain(ctx.terrainSource(),x,z,64,combined);
   var contour=TerrainPageClipper.boundary(ref);
   record Candidate(SurfaceMesh[] pages,SurfaceMesh joined,long gpu,int triangles){}
   var candidates=new ArrayList<Candidate>();
   for(double radius:new double[]{.2,.35,.45}){
    var seed=SurfaceRelaxer.relax(ref,radius,16);
    for(var mesh:QuadricSimplifier.sampleCompactTerrain(seed,.1,new double[]{.35,.5,.65,.8,1,1.5,2,4,6,8})){
     var pages=new SurfaceMesh[4];long gpu=snowFarGpu;int triangles=0;
     try{for(int i=0;i<4;i++){Tile tile=tiles.get(i);pages[i]=TerrainPageClipper.clip(mesh,tile.x,tile.z,32);gpu+=CompactTerrainLayout.build(pages[i],tile.x,tile.z).gpuBytes()+caps.get(i).gpuBytes();triangles+=pages[i].triangles();}}catch(IllegalStateException invalid){if(invalid.getMessage().startsWith("clip "))continue;throw invalid;}
     if(gpu<exactGpu&&triangles<combined.triangles()+snowTriangles){var joined=TerrainPageClipper.join(Arrays.asList(pages));if(TerrainPageClipper.boundary(joined).equals(contour))candidates.add(new Candidate(pages,joined,gpu,triangles));}
    }
   }
   candidates.sort(Comparator.comparingLong(Candidate::gpu).thenComparingInt(Candidate::triangles));
   for(int level=1;level<3;level++)for(var candidate:candidates){
    double allowance=TileBake.errorLimit(level)-cover;if(allowance<=0)continue;
    var error=SurfaceError.compare(ref,candidate.joined,allowance);
    if(error.withinLimit()){
     double tighter=Math.max(.25,error.sampledMax()+.125);
     if(tighter<error.upperBound()*.95){var refined=SurfaceError.compare(ref,candidate.joined,tighter);if(refined.withinLimit())error=refined;}
     accepted[level]=candidate.pages;errors[level]=new SurfaceError.Result(true,error.upperBound()+cover,error.sampledMax(),error.queries(),"joint_patch_after_page_clipping_plus_snow_bound");break;}
   }
   // Joining is useful at constrained seams, not automatically cheaper everywhere.
   // Keep an already smooth independent group when it wins BOTH cost metrics.
   if(accepted[1]!=null||accepted[2]!=null){
    var independent=new ArrayList<LayeredTileBake.Layer>();
    for(var tile:tiles)independent.add(LayeredTileBake.build(s,ctx,tile.x,tile.z,32,true).stream().filter(l->l.name().equals("terrain")).findFirst().orElseThrow());
    for(int level=1;level<3;level++)if(accepted[level]!=null){
     var pages=new SurfaceMesh[4];long gpu=0,currentGpu=0;int triangles=0,currentTriangles=0,queries=0;double bound=0,sampled=0;boolean all=true;
     for(int i=0;i<4;i++){var layer=independent.get(i);var choice=layer.choices()[level];if(!choice.isMesh()){all=false;break;}pages[i]=choice.mesh();var tile=tiles.get(i);gpu+=CompactTerrainLayout.build(pages[i],tile.x,tile.z).gpuBytes()+caps.get(i).gpuBytes();triangles+=pages[i].triangles();currentGpu+=CompactTerrainLayout.build(accepted[level][i],tile.x,tile.z).gpuBytes()+caps.get(i).gpuBytes();currentTriangles+=accepted[level][i].triangles();var audit=layer.errors()[level];bound=Math.max(bound,audit.upperBound());sampled=Math.max(sampled,audit.sampledMax());queries+=audit.queries();}
     if(all&&gpu<currentGpu&&triangles<=currentTriangles&&TerrainPageClipper.boundary(TerrainPageClipper.join(Arrays.asList(pages))).equals(contour)){
      accepted[level]=pages;errors[level]=new SurfaceError.Result(true,bound,sampled,queries,"independent_smooth_pages_exact_shared_contours_max_certified_bound");
     }
    }
   }
  }catch(IllegalStateException error){if(!error.getMessage().contains("budget"))throw error;}
  var result=new ArrayList<Tile>();
  for(int i=0;i<4;i++){
   Tile tile=tiles.get(i);var layers=new ArrayList<LayeredTileBake.Layer>();
   for(var layer:tile.layers){if(!layer.name().equals("terrain")){layers.add(layer);continue;}
    var choices=layer.choices().clone();for(int l=1;l<3;l++)if(accepted[l]!=null)choices[l]=new TileBake.Choice(accepted[l][i],null,"joint_64_patch_strict_aggregate_gpu_and_triangle_gain");
    layers.add(new LayeredTileBake.Layer("terrain",layer.exact(),choices,errors.clone(),accepted[1]!=null||accepted[2]!=null?caps.get(i):layer.surfaceMap(),layer.sourceErrors()));
   }result.add(new Tile(tile.x,tile.z,layers));
  }
  return result;
 }
 private static List<Tile> exact(VoxelSnapshot s,LayeredTileBake.Context ctx,int x,int z){var out=new ArrayList<Tile>();for(int dz=0;dz<64;dz+=32)for(int dx=0;dx<64;dx+=32)out.add(new Tile(x+dx,z+dz,LayeredTileBake.build(s,ctx,x+dx,z+dz,32,false)));return out;}
 private JoinedTerrainBake(){}
}
