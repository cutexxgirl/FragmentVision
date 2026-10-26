package me.cortex.voxy.client.core.rendering.lod.bake;

import java.util.*;

/** Terrain has its own welded boundary; foliage/models never freeze unrelated mountain vertices.
 * Faces still use the combined cleaned volume for visibility and protection, so interfaces agree.
 * A terrain candidate must improve both GPU storage and triangle count, with a strict GPU-storage gain. Disk page size is reported separately.
 */
public final class LayeredTileBake {
    public record Layer(String name,PackedQuads exact,TileBake.Choice[] choices,SurfaceError.Result[] errors,SurfaceMaterialMap.Data surfaceMap,double[] sourceErrors){}
    public record Context(VoxelSnapshot terrainSource,boolean[] terrain,int[] heights,byte[] snow){}
    public static Context prepare(VoxelSnapshot source){
        byte[] snow=LodInputPolicy.projectableSnow(source);
        VoxelSnapshot terrainSource=LodInputPolicy.terrainContext(source,snow);
        boolean[] terrain=new boolean[source.states().length];for(int i=0;i<terrain.length;i++)terrain[i]=LodInputPolicy.terrain(source.states()[i]);
        int[] heights=new int[source.sizeX()*source.sizeZ()];Arrays.fill(heights,-1);
        for(int yy=0;yy<source.sizeY();yy++)for(int zz=0;zz<source.sizeZ();zz++)for(int xx=0;xx<source.sizeX();xx++){int block=terrainSource.block(xx,yy,zz);if(block>0&&terrain[block])heights[zz*source.sizeX()+xx]=yy;}
        return new Context(terrainSource,terrain,heights,snow);
    }
    public static List<Layer> build(VoxelSnapshot source,int x,int z,int width,boolean smooth){return build(source,prepare(source),x,z,width,smooth);}
    public static List<Layer> build(VoxelSnapshot source,Context context,int x,int z,int width,boolean smooth){
        VoxelSnapshot terrainSource=context.terrainSource;boolean[] terrain=context.terrain;int[] heights=context.heights;
        GreedyQuadMesher.FaceSelector outside=(xx,yy,zz,face)->{
            if(face==2)return false;if(face==3)return yy==heights[zz*source.sizeX()+xx];
            int nx=xx+(face==0?-1:face==1?1:0),nz=zz+(face==4?-1:face==5?1:0);
            return nx>=0&&nz>=0&&nx<source.sizeX()&&nz<source.sizeZ()&&yy>heights[nz*source.sizeX()+nx];
        };
        PackedQuads ground=GreedyQuadMesher.build(terrainSource,x,z,width,id->terrain[id],outside).selected();
        PackedQuads caves=GreedyQuadMesher.build(terrainSource,x,z,width,id->terrain[id],(xx,yy,zz,face)->!outside.accepts(xx,yy,zz,face)).selected();
        PackedQuads detail=GreedyQuadMesher.build(source,x,z,width,id->!terrain[id],(xx,yy,zz,face)->context.snow[source.index(xx,yy,zz)]==0,true).selected();
        PackedQuads snow=GreedyQuadMesher.build(source,x,z,width,id->!terrain[id],(xx,yy,zz,face)->context.snow[source.index(xx,yy,zz)]!=0,true).selected();
        var cap=SurfaceMaterialMap.build(source,x,z,width,terrain,context.snow);
        double[] sourceErrors={0,cap.coverError(),cap.coverError()};
        var choices=new TileBake.Choice[3];var errors=new SurfaceError.Result[3];
        for(int i=0;i<3;i++)choices[i]=new TileBake.Choice(null,ground,"terrain_exact_fallback");
        if(smooth&&ground.data().length>0)try{
            SurfaceMesh reference=ConformingSurface.extractTerrain(terrainSource,x,z,width,ground);
            var candidates=new ArrayList<SurfaceMesh>();var layouts=new IdentityHashMap<SurfaceMesh,CompactTerrainLayout.Layout>();
            for(double radius:new double[]{0,.2,.35,.45}){
                SurfaceMesh seed=radius==0?reference:SurfaceRelaxer.relax(reference,radius,16);
                for(SurfaceMesh candidate:QuadricSimplifier.sampleCompactTerrain(seed,.1,new double[]{.5,1,2,3,4,5,6,8})){
                    if(candidate.triangles()>=ground.triangles())continue;
                    var layout=CompactTerrainLayout.build(candidate,x,z);layouts.put(candidate,layout);
                    if(layout.gpuBytes()+cap.gpuBytes()+(detail.data().length>0?152:0)+(caves.data().length>0?152:0)+(snow.data().length>0?152:0)>=ground.gpuBytes())continue;
                    if(candidates.stream().noneMatch(p->Arrays.equals(p.positions(),candidate.positions())&&Arrays.equals(p.indices(),candidate.indices())))candidates.add(candidate);
                }
            }
            candidates.sort(Comparator.comparingLong((SurfaceMesh mesh)->layouts.get(mesh).gpuBytes()).thenComparingInt(SurfaceMesh::triangles));
            // L0 is exact so retained near snow layers and native chunks cannot intersect a bevel.
            for(int level=1;level<3;level++)for(var candidate:candidates){
                double allowance=TileBake.errorLimit(level)-cap.coverError();if(allowance<=0)continue;
                var audit=SurfaceError.compare(reference,candidate,allowance);
                if(audit.withinLimit()){
                    choices[level]=new TileBake.Choice(candidate,null,"smooth_terrain_strict_gpu_triangle_and_distance_gain");
                    errors[level]=new SurfaceError.Result(true,audit.upperBound()+cap.coverError(),audit.sampledMax(),audit.queries(),"bidirectional_ground_bound_plus_native_snow_thickness");break;
                }
            }
        }catch(IllegalStateException bounded){
            if(!bounded.getMessage().contains("budget"))throw bounded;
            // An extraction/work cap must never publish an incomplete surface.
        }
        var result=new ArrayList<Layer>();
        if(ground.data().length>0)result.add(new Layer("terrain",ground,choices,errors,cap.coverError()>0||Arrays.stream(choices).anyMatch(TileBake.Choice::isMesh)?cap:null,sourceErrors));
        if(detail.data().length>0){var exacts=new TileBake.Choice[3];for(int l=0;l<3;l++)exacts[l]=new TileBake.Choice(null,detail,"retained_leaf_structure_and_fluid_blocks");result.add(new Layer("detail",detail,exacts,new SurfaceError.Result[3],null,new double[3]));}
        if(caves.data().length>0){var exacts=new TileBake.Choice[3];for(int l=0;l<3;l++)exacts[l]=new TileBake.Choice(null,caves,"retained_caves_and_overhangs");result.add(new Layer("caves",caves,exacts,new SurfaceError.Result[3],null,new double[3]));}
        if(snow.data().length>0){
            var exacts=new TileBake.Choice[3];exacts[0]=new TileBake.Choice(null,snow,"native_snow_layers");
            for(int l=1;l<3;l++)exacts[l]=new TileBake.Choice(null,new PackedQuads(new long[0]),"snow_cap_projected_to_original_column_material");
            result.add(new Layer("snow",snow,exacts,new SurfaceError.Result[3],null,sourceErrors.clone()));
        }
        return result;
    }
    private LayeredTileBake(){}
}
