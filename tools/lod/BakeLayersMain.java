import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.nio.file.*;
import java.util.*;

/** CPU-only layered bake. Use immutable snapshots; no debug OBJ explosion. */
public final class BakeLayersMain {
    public static void main(String[] args)throws Exception{
        if(args.length<2||args.length>4)throw new IllegalArgumentException("input snapshot, output directory, [tile=32], [exact]");
        int width=args.length>=3?Integer.parseInt(args[2]):32,h=SurfaceExtractor.REQUIRED_HALO;
        boolean smooth=args.length<4||!args[3].equals("exact");
        if(args.length==4&&smooth)throw new IllegalArgumentException("Unknown mode");
        if(width<4||width>32)throw new IllegalArgumentException("Tile width");
        Path input=Path.of(args[0]),target=Path.of(args[1]);
        VoxelSnapshot raw=VoxelSnapshot.read(input),s=LodInputPolicy.clean(raw);
        if(Arrays.stream(s.blocks()).anyMatch(i->i<0))throw new IllegalArgumentException("Unknown input blocks");
        if((s.sizeX()-2*h)%width!=0||(s.sizeZ()-2*h)%width!=0||s.sizeX()<=2*h||s.sizeZ()<=2*h)throw new IllegalArgumentException("Incomplete tile/halo");
        if(Files.exists(target))throw new IllegalArgumentException("Refusing to overwrite package");
        Files.createDirectories(target.toAbsolutePath().getParent());Path staging=Files.createTempDirectory(target.toAbsolutePath().getParent(),".layers-");boolean published=false;
        try{
            var palette=new ArrayList<String>();for(int i=0;i<s.states().length;i++)palette.add("{\"state\":"+BakeProofMain.json(s.states()[i])+",\"biome\":"+BakeProofMain.json(s.biomes()[i])+"}");
            Files.writeString(staging.resolve("palette.json"),"["+String.join(",",palette)+"]\n");
            var terrainSource=LayeredTileBake.prepare(s);
            var pages=new ArrayList<String>();long bytes=0,exactTriangles=0,farTriangles=0,exactGpu=0,farGpu=0;
            for(int z=h;z<s.sizeZ()-h;z+=width)for(int x=h;x<s.sizeX()-h;x+=width){
                for(var layer:LayeredTileBake.build(s,terrainSource,x,z,width,smooth)){
                    String name="tile-"+((x-h)/width)+"-"+((z-h)/width)+"-"+layer.name();var unique=new HashMap<String,Path>();
                    String mapJson="";
                    if(layer.surfaceMap()!=null){
                        Path mapPath=staging.resolve(name+".fvcap");SurfaceMaterialMap.write(mapPath,layer.surfaceMap());bytes+=Files.size(mapPath);
                        mapJson=",\"surface_map\":"+BakeProofMain.json(mapPath.getFileName().toString())+",\"surface_map_sha256\":"+BakeProofMain.json(BakeProofMain.hash(mapPath))+",\"surface_map_bytes\":"+Files.size(mapPath)+",\"surface_map_gpu_bytes\":"+layer.surfaceMap().gpuBytes();
                    }
                    for(int level=0;level<3;level++){
                        var choice=layer.choices()[level];Path path=staging.resolve(name+"-lod"+level+(choice.isMesh()?".fvmesh":".fvquad"));
                        if(choice.isMesh())MeshPage.write(path,choice.mesh(),s.originX()+x,s.originY(),s.originZ()+z,x,z);else choice.quads().write(path,s.originX()+x,s.originY(),s.originZ()+z);
                        String hash=BakeProofMain.hash(path);Path prior=unique.putIfAbsent(hash,path);if(prior!=null){Files.delete(path);path=prior;}else bytes+=Files.size(path);
                        long gpu=choice.isMesh()?CompactTerrainLayout.build(choice.mesh(),x,z).gpuBytes():choice.quads().gpuBytes();
                        pages.add("{\"file\":"+BakeProofMain.json(path.getFileName().toString())+",\"sha256\":"+BakeProofMain.json(hash)+",\"tile\":["+(s.originX()+x)+","+(s.originZ()+z)+"],\"tile_width\":"+width+",\"layer\":"+BakeProofMain.json(layer.name())+",\"level\":"+level+",\"mesh_layout\":\"compact-terrain-strip-v1\",\"representation\":"+BakeProofMain.json(choice.isMesh()?"mesh":"quads")+",\"triangles\":"+choice.triangles()+",\"vertices\":"+(choice.isMesh()?choice.mesh().vertices():0)+",\"payload_bytes\":"+choice.payloadBytes()+",\"gpu_geometry_bytes\":"+gpu+mapJson+",\"source_shape_error\":"+layer.sourceErrors()[level]+",\"bytes\":"+Files.size(path)+",\"packed_triangles\":"+layer.exact().triangles()+",\"packed_payload_bytes\":"+layer.exact().payloadBytes()+",\"packed_gpu_bytes\":"+layer.exact().gpuBytes()+",\"candidate_error\":"+BakeProofMain.errorJson(layer.errors()[level])+",\"error_limit_blocks\":"+TileBake.errorLimit(level)+",\"reason\":"+BakeProofMain.json(choice.reason())+"}");
                        if(level==2){exactTriangles+=layer.exact().triangles();farTriangles+=choice.triangles();exactGpu+=layer.exact().gpuBytes();farGpu+=gpu+(layer.surfaceMap()==null?0:layer.surfaceMap().gpuBytes());}
                    }
                }
                System.out.println("Tile "+(s.originX()+x)+","+(s.originZ()+z)+" ready");
            }
            Files.writeString(staging.resolve("manifest.json"),"{\"format\":\"fragment-lod-proof-3\",\"algorithm\":\"layered-terrain-column-material-qem-v2\",\"input_policy\":\"small-vegetation-removed-leaves-retained-v2\",\"snapshot_sha256\":"+BakeProofMain.json(BakeProofMain.hash(input))+",\"palette_sha256\":"+BakeProofMain.json(BakeProofMain.hash(staging.resolve("palette.json")))+",\"unknown_voxels\":0,\"tile_width\":"+width+",\"halo\":"+h+",\"prepared_unique_pages_bytes\":"+bytes+",\"pages\":["+String.join(",\n",pages)+"]}\n");
            Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE);published=true;
            System.out.println("Far total triangles "+exactTriangles+" -> "+farTriangles+", GPU geometry "+exactGpu+" -> "+farGpu+", unique bytes="+bytes);
        }finally{if(!published)try(var walk=Files.walk(staging)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
