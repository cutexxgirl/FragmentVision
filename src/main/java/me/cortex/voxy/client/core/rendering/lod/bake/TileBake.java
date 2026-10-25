package me.cortex.voxy.client.core.rendering.lod.bake;

/** Direct geometric simplification of a conforming merged boundary; no smoothing pass.
 * Each level must improve the tile's own exact representation in payload or triangles,
 * regress neither in the default policy, and pass an independent bidirectional world-space distance bound.
 * The opt-in triangle-priority policy permits a bounded encoded-memory trade.
 * These are geometry gates, not frame-time or total GPU-memory guarantees.
 */
public final class TileBake {
    public static final String ALGORITHM="merged-boundary-edge-collapse-v1";
    private static final double[] ERROR_LIMITS={.5,1.,1.5};
    private static final double[] SEARCH_COSTS={.25,.5,1.,2.,4.,8.};
    public record Choice(SurfaceMesh mesh,PackedQuads quads,String reason) {
        public boolean isMesh(){return mesh!=null;}
        public long payloadBytes(){return isMesh()?mesh.packedBytes():quads.payloadBytes();}
        public int triangles(){return isMesh()?mesh.triangles():quads.triangles();}
    }
    public record Result(GreedyQuadMesher.Result exact,SurfaceMesh reference,SurfaceMesh[] candidates,
                         Choice[] choices,SurfaceError.Result[] errors,String extractionFailure) {}
    public static double errorLimit(int level){return ERROR_LIMITS[level];}

    public static Result build(VoxelSnapshot s,int x,int z,int width) {return build(s,x,z,width,false);}
    /** Opt-in triangle priority: at least 25% fewer triangles, at most 2x encoded geometry.
     * This is an explicit memory/triangle trade, never a claim of lower frame time. */
    public static Result build(VoxelSnapshot s,int x,int z,int width,boolean trianglePriority) {
        var exact=GreedyQuadMesher.build(s,x,z,width);
        SurfaceMesh reference;
        try {
            reference=ConformingSurface.extract(s,x,z,width,exact.selected());
        } catch(IllegalStateException limited) {
            if(!limited.getMessage().contains("budget"))throw limited;
            var choices=new Choice[3];for(int i=0;i<3;i++)choices[i]=new Choice(null,exact.selected(),"direct_extraction_refused: "+limited.getMessage());
            return new Result(exact,null,null,choices,new SurfaceError.Result[3],limited.getMessage());
        }
        var pool=new java.util.ArrayList<SurfaceMesh>();
        for(double searchCost:SEARCH_COSTS) {
            SurfaceMesh mesh=QuadricSimplifier.simplifyExtracted(reference,.1,searchCost).mesh();
            if(pool.stream().noneMatch(p->java.util.Arrays.equals(p.positions(),mesh.positions())&&java.util.Arrays.equals(p.indices(),mesh.indices())&&
                    java.util.Arrays.equals(p.normals(),mesh.normals())&&java.util.Arrays.equals(p.faceData(),mesh.faceData())))pool.add(mesh);
        }
        pool.sort(java.util.Comparator.comparingLong(SurfaceMesh::packedBytes).thenComparingInt(SurfaceMesh::triangles));
        var choices=new Choice[3];var candidates=new SurfaceMesh[3];var errors=new SurfaceError.Result[3];
        for(int level=0;level<3;level++) {
            choices[level]=new Choice(null,exact.selected(),"exact_is_cheaper_than_candidates");
            candidates[level]=pool.getFirst();
            for(SurfaceMesh mesh:pool) {
                if(mesh.triangles()>exact.selected().triangles()*(trianglePriority?.75:1.)||mesh.packedBytes()>exact.selected().payloadBytes()*(trianglePriority?2.:1.)||
                        (mesh.triangles()==exact.selected().triangles()&&mesh.packedBytes()==exact.selected().payloadBytes()))continue;
                SurfaceError.Result error=SurfaceError.compare(reference,mesh,ERROR_LIMITS[level]);
                // If all attempts fail, expose the cheapest rejected candidate and its error.
                if(errors[level]==null){candidates[level]=mesh;errors[level]=error;}
                if(error.withinLimit()) {
                    candidates[level]=mesh;errors[level]=error;
                    choices[level]=new Choice(mesh,null,trianglePriority?"triangle_priority_bounded_memory_trade":"direct_simplification_within_geometry_and_distance_budgets");break;
                }
                choices[level]=new Choice(null,exact.selected(),"geometric_error_unmet_exact_fallback");
            }
        }
        return new Result(exact,reference,candidates,choices,errors,null);
    }
    private TileBake() {}
}
