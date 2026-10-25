import me.cortex.voxy.client.core.rendering.lod.bake.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Standalone Java 21 tool. Deliberately no dependency on Minecraft, GL, Gradle or native libraries. */
public final class BakeProofMain {
    public static void main(String[] args) throws Exception {
        if(args.length<2||args.length>4)throw new IllegalArgumentException("Usage: BakeProofMain input.fvvol.gz output-directory [tileWidth=32] [triangle-priority]");
        int tile=args.length>=3?Integer.parseInt(args[2]):32,halo=SurfaceExtractor.REQUIRED_HALO;
        if(tile<4||tile>64)throw new IllegalArgumentException("Tile width must be 4..64");
        VoxelSnapshot source=VoxelSnapshot.read(Path.of(args[0]));
        if(Arrays.stream(source.blocks()).anyMatch(v->v<0))
            throw new IOException("Incomplete source: proof packages require known chunks throughout every tile and halo");
        if((source.sizeX()-halo*2)%tile!=0||(source.sizeZ()-halo*2)%tile!=0||source.sizeX()<=halo*2||source.sizeZ()<=halo*2)
            throw new IllegalArgumentException("Snapshot XZ must be N*tileWidth + 2*halo (halo="+halo+")");
        if(args.length==4&&!args[3].equals("triangle-priority"))throw new IllegalArgumentException("Unknown bake policy");
        Path target=Path.of(args[1]).toAbsolutePath();
        if(Files.exists(target))throw new IOException("Refusing to overwrite a proof package: "+target);
        Files.createDirectories(target.getParent());
        Path staging=Files.createTempDirectory(target.getParent(),".lod-proof-");
        boolean published=false;
        long start=System.nanoTime();
        try {
            var palette=new StringBuilder("[\n");
            for(int i=0;i<source.states().length;i++)palette.append(i==0?"":",\n").append("{\"state\":").append(json(source.states()[i]))
                    .append(",\"biome\":").append(json(source.biomes()[i])).append('}');
            Files.writeString(staging.resolve("palette.json"),palette.append("\n]\n").toString());
            var pages=new ArrayList<String>();
            long preparedBytes=0;
            for(int z=halo;z+tile<=source.sizeZ()-halo;z+=tile)for(int x=halo;x+tile<=source.sizeX()-halo;x+=tile) {
                long tileStart=System.nanoTime();
                var baked=TileBake.build(source,x,z,tile,args.length==4&&args[3].equals("triangle-priority"));
                var baseline=baked.exact().baseline();
                var ownExact=baked.exact().selected();
                String name="tile-"+(x-halo)/tile+"-"+(z-halo)/tile;
                if(ownExact.data().length<=32768)obj(staging.resolve(name+"-reference.obj"),ownExact.mesh(x,z));
                if(baked.reference()!=null)obj(staging.resolve(name+"-direct-source.obj"),baked.reference());
                var unique=new HashMap<String,Path>();
                for(int level=0;level<3;level++) {
                    var choice=baked.choices()[level];
                    SurfaceMesh candidate=baked.candidates()==null?null:baked.candidates()[level];
                    if(candidate!=null)obj(staging.resolve(name+"-candidate-lod"+level+".obj"),candidate);
                    String extension=choice.isMesh()?".fvmesh":".fvquad";
                    Path file=staging.resolve(name+"-lod"+level+extension);
                    int ox=source.originX()+x,oz=source.originZ()+z;
                    if(choice.isMesh())MeshPage.write(file,choice.mesh(),ox,source.originY(),oz,x,z);
                    else choice.quads().write(file,ox,source.originY(),oz);
                    String checksum=hash(file);
                    long bytes=Files.size(file);
                    Path same=unique.get(checksum);
                    if(same==null){unique.put(checksum,file);preparedBytes+=bytes;}
                    else {Files.delete(file);file=same;}
                    if(choice.isMesh())obj(staging.resolve(name+"-lod"+level+".obj"),choice.mesh());
                    else if(choice.quads().data().length<=32768)obj(staging.resolve(name+"-lod"+level+".obj"),choice.quads().mesh(x,z));
                    pages.add("{\"file\":"+json(file.getFileName().toString())+",\"sha256\":"+json(checksum)+",\"tile\":["+ox+","+oz+"],\"level\":"+level+
                            ",\"representation\":"+json(choice.isMesh()?"mesh":"quads")+",\"vertices\":"+(choice.isMesh()?choice.mesh().vertices():0)+
                            ",\"triangles\":"+choice.triangles()+",\"payload_bytes\":"+choice.payloadBytes()+",\"bytes\":"+bytes+
                            ",\"voxy_triangles\":"+baseline.triangles()+",\"voxy_quad_payload_bytes\":"+baseline.payloadBytes()+
                            ",\"packed_triangles\":"+ownExact.triangles()+",\"packed_payload_bytes\":"+ownExact.payloadBytes()+
                            ",\"error_limit_blocks\":"+TileBake.errorLimit(level)+",\"candidate_error\":"+errorJson(baked.errors()[level])+",\"reason\":"+json(choice.reason())+
                            ",\"candidate_triangles\":"+(candidate==null?"null":candidate.triangles())+
                            ",\"candidate_payload_bytes\":"+(candidate==null?"null":candidate.packedBytes())+"}");
                }
                System.out.printf(Locale.ROOT,"%s: Voxy baseline=%d tri/%d B, selected far=%d tri/%d B (%s); %.1fms%n",
                        name,baseline.triangles(),baseline.payloadBytes(),baked.choices()[2].triangles(),baked.choices()[2].payloadBytes(),
                        baked.choices()[2].isMesh()?"mesh":"quads",(System.nanoTime()-tileStart)/1e6);
            }
            long unknown=Arrays.stream(source.blocks()).filter(v->v<0).count();
            String manifest="{\n\"format\":\"fragment-lod-proof-3\",\"algorithm\":"+json(TileBake.ALGORITHM)+",\"snapshot_sha256\":"+json(hash(Path.of(args[0])))+",\"palette_sha256\":"+json(hash(staging.resolve("palette.json")))+
                    ",\"tile_width\":"+tile+",\"halo\":"+halo+",\"unknown_voxels\":"+unknown+",\"prepared_unique_pages_bytes\":"+preparedBytes+
                    ",\"material_input\":\"saved state+biome+face+light; no baked tint/albedo\",\"cost_metric\":\"bidirectional world-space surface-distance bound; QEM is search only, not screen-space error\",\"protected_models\":\"diagnostic full voxel cubes; NOT final baked-model geometry\",\n\"pages\":[\n"+String.join(",\n",pages)+"\n]}\n";
            Files.writeString(staging.resolve("manifest.json"),manifest,StandardCharsets.UTF_8);
            Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE);published=true;
            System.out.printf(Locale.ROOT,"Published %s in %.2fs. This is an offline geometry proof; no game backend was enabled.%n",target,(System.nanoTime()-start)/1e9);
        } finally {
            if(!published)try(var paths=Files.walk(staging)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }

    static String errorJson(SurfaceError.Result error) {
        if(error==null)return "null";
        return "{\"within_limit\":"+error.withinLimit()+",\"upper_bound_blocks\":"+(Double.isFinite(error.upperBound())?error.upperBound():"null")+
                ",\"sampled_max_blocks\":"+error.sampledMax()+",\"queries\":"+error.queries()+",\"reason\":"+json(error.reason())+"}";
    }
    private static void obj(Path path,SurfaceMesh mesh) throws IOException {
        try(var writer=Files.newBufferedWriter(path,StandardCharsets.US_ASCII)) {
            writer.write("# Diagnostic geometry only. Coordinates relative to snapshot origin.\n");
            for(int i=0;i<mesh.vertices();i++)writer.write(String.format(Locale.ROOT,"v %.6f %.6f %.6f%n",mesh.positions()[i*3],mesh.positions()[i*3+1],mesh.positions()[i*3+2]));
            for(int i=0;i<mesh.vertices();i++)writer.write(String.format(Locale.ROOT,"vn %.6f %.6f %.6f%n",mesh.normals()[i*3],mesh.normals()[i*3+1],mesh.normals()[i*3+2]));
            int last=-1;
            for(int i=0;i<mesh.triangles();i++) {
                int data=mesh.faceData()[i];
                if(data!=last){writer.write("g palette_"+(data>>>11)+"_face_"+((data>>>8)&7)+"_light_"+(data&255)+"\n");last=data;}
                int a=mesh.indices()[i*3]+1,b=mesh.indices()[i*3+1]+1,c=mesh.indices()[i*3+2]+1;
                writer.write("f "+a+"//"+a+" "+b+"//"+b+" "+c+"//"+c+"\n");
            }
        }
    }
    private static String hash(Path path) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] b=new byte[65536];for(int n;(n=in.read(b))>=0;)digest.update(b,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String json(String value) {
        var out=new StringBuilder("\"");
        for(char c:value.toCharArray()) {if(c=='"'||c=='\\')out.append('\\').append(c);else if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}
        return out.append('"').toString();
    }
}
