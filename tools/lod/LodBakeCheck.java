import me.cortex.voxy.client.core.rendering.lod.bake.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Invariants that are easy to lose when replacing voxel geometry with independently baked pages. */
public final class LodBakeCheck {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args.length==0?"build/lod-proof/check":args[0]);Files.createDirectories(output);
        plane();seams();arch();protection();unknown();budget();codec(output);
        var hills=fixture(46,40,46);
        for(int z=0;z<46;z++)for(int x=0;x<46;x++) {
            int height=10+(int)(4*Math.sin(x*.19)+3*Math.cos(z*.16));
            for(int y=0;y<height;y++)set(hills,x,y,z,1,true);
        }
        // White material patch follows the real surface, never fills an entire column down the cliff.
        for(int z=16;z<29;z++)for(int x=20;x<32;x++)for(int y=0;y<39;y++)
            if(hills.block(x,y,z)>0&&hills.block(x,y+1,z)==0)set(hills,x,y,z,2,true);
        writeSnapshot(output.resolve("hills.fvvol.gz"),hills);
        writeSnapshot(output.resolve("arch.fvvol.gz"),archFixture());
        System.out.println("LOD bake invariants passed: "+checks+"; fixtures in "+output);
    }

    private static void plane() {
        VoxelSnapshot s=fixture(46,24,46);
        for(int z=0;z<46;z++)for(int x=0;x<46;x++)for(int y=0;y<8;y++)set(s,x,y,z,1,true);
        SurfaceMesh m=SurfaceExtractor.extract(s,7,7,32,true);
        var result=QuadricSimplifier.simplify(m,.15,.8);
        require(result.mesh().triangles()<m.triangles()/4,"plane must remove topology, not merely move vertices");
        require(result.mesh().vertices()<m.vertices()/2,"unused vertices must be removed from the output");
        for(int i=1;i<result.mesh().positions().length;i+=3)require(result.mesh().positions()[i]==8,"a plane must stay planar");
        valid(result.mesh());
        System.out.printf("plane: %d -> %d triangles, %d -> %d vertices, %d -> %d packed bytes%n",m.triangles(),result.mesh().triangles(),m.vertices(),result.mesh().vertices(),m.packedBytes(),result.mesh().packedBytes());
    }

    private static void seams() {
        VoxelSnapshot s=fixture(78,32,46);
        for(int z=0;z<s.sizeZ();z++)for(int x=0;x<s.sizeX();x++) {
            int h=9+(int)(3*Math.sin(x*.2)+2*Math.cos(z*.25));
            for(int y=0;y<h;y++)set(s,x,y,z,1,true);
        }
        SurfaceMesh a=SurfaceExtractor.extract(s,7,7,32,true),b=SurfaceExtractor.extract(s,39,7,32,true);
        SurfaceMesh exact=SurfaceExtractor.extract(s,7,7,32,false);
        var contour=new HashSet<String>();
        for(int i=0;i<exact.vertices();i++)if(exact.positions()[i*3]==39)contour.add(point(a,i));
        Set<String> boundary=edges(a,contour);
        require(!boundary.isEmpty(),"seam fixture must exercise a contour");
        require(boundary.equals(edges(b,contour)),"neighbours must have the same fine contour");
        var coarse=QuadricSimplifier.simplify(b,.15,.8).mesh();
        require(boundary.equals(edges(coarse,contour)),"fine/coarse neighbours must share the contour");
        var replay=SurfaceExtractor.extract(s,39,7,32,true);
        require(Arrays.equals(b.positions(),replay.positions())&&Arrays.equals(b.indices(),replay.indices()),"bake must be deterministic");
        // A neighbouring page baked from its own translated volume has the SAME world contour.
        VoxelSnapshot crop=crop(s,32,0,0,46,32,46);
        SurfaceMesh local=SurfaceExtractor.extract(crop,7,7,32,true);
        float[] translated=local.positions().clone();for(int i=0;i<translated.length;i+=3)translated[i]+=32;
        local=new SurfaceMesh(translated,local.normals(),local.indices(),local.faceData(),local.locked());
        require(boundary.equals(edges(local,contour)),"halo calculation must be independent of snapshot origin");
        valid(a);valid(coarse);
    }

    private static VoxelSnapshot archFixture() {
        VoxelSnapshot s=fixture(46,40,46);
        for(int z=12;z<34;z++)for(int x=12;x<34;x++)for(int y=5;y<27;y++)
            if(!(x>=19&&x<27&&y>=5&&y<19))set(s,x,y,z,1,true);
        return s;
    }
    private static void arch() {
        VoxelSnapshot s=archFixture();
        SurfaceMesh m=SurfaceExtractor.extract(s,7,7,32,true);
        var coarse=QuadricSimplifier.simplify(m,.2,.8).mesh();
        require(euler(m)==euler(coarse),"simplification must preserve the arch topology");
        require(closed(m)&&closed(coarse),"closed source must remain watertight");
        boolean ceiling=false;
        for(int i=0;i<coarse.triangles();i++)if(((coarse.faceData()[i]>>>8)&7)==2) {
            float y=coarse.positions()[coarse.indices()[i*3]*3+1];
            ceiling|=y>17&&y<21;
        }
        require(ceiling,"volumetric extraction must keep the underside of the overhang");
        valid(coarse);
    }
    private static void protection() {
        VoxelSnapshot s=fixture(46,32,46);
        // A house made from exactly the same stone as the terrain. Metadata, not material, protects it.
        for(int z=15;z<24;z++)for(int x=15;x<24;x++)for(int y=5;y<20;y++)set(s,x,y,z,1,false);
        SurfaceMesh reference=SurfaceExtractor.extract(s,7,7,32,false),smooth=SurfaceExtractor.extract(s,7,7,32,true);
        var result=QuadricSimplifier.simplify(smooth,.1,1).mesh();
        require(Arrays.equals(reference.positions(),smooth.positions()),"protected stone construction must not be smoothed");
        require(result.triangles()==reference.triangles(),"protected geometry must remain exact in this proof");
        // An actual local edit changes the rebuilt output and survives snapshot save/read.
        set(s,18,19,18,0,false);
        var edited=SurfaceExtractor.extract(s,7,7,32,true);
        require(edited.triangles()!=smooth.triangles(),"rebuilding an edited source must not restore the base geometry");
    }
    private static void unknown() {
        VoxelSnapshot s=fixture(46,24,46);Arrays.fill(s.blocks(),-1);
        require(SurfaceExtractor.extract(s,7,7,32,true).triangles()==0,"unknown sections must not become invented air");
        set(s,16,10,16,1,true);
        require(SurfaceExtractor.extract(s,7,7,32,true).triangles()==0,"a solid block beside unknown data must not acquire fake exposed faces");
    }
    private static void codec(Path output) throws Exception {
        VoxelSnapshot s=archFixture();
        SurfaceMesh m=QuadricSimplifier.simplify(SurfaceExtractor.extract(s,7,7,32,true),.2,.8).mesh();
        Path path=output.resolve("roundtrip.fvmesh");Files.deleteIfExists(path);
        MeshPage.write(path,m,1_000_007,-64,-1_000_007,7,7);
        var read=MeshPage.read(path);
        require(read.originX()==1_000_007&&read.originY()==-64,"page origin must keep large coordinates as integers");
        require(Files.size(path)==MeshPage.HEADER_BYTES+m.packedBytes(),"reported payload must include the actual vertex and index streams");
        require(Arrays.equals(m.indices(),read.mesh().indices())&&Arrays.equals(m.faceData(),read.mesh().faceData()),"codec must preserve indices and discrete face materials");
        for(int i=0;i<m.vertices();i++) {
            require(m.positions()[i*3]-7==read.mesh().positions()[i*3]&&m.positions()[i*3+1]==read.mesh().positions()[i*3+1]&&m.positions()[i*3+2]-7==read.mesh().positions()[i*3+2],"shared position lattice must survive packing exactly");
            double dot=0;for(int k=0;k<3;k++)dot+=m.normals()[i*3+k]*read.mesh().normals()[i*3+k];
            require(dot>.9998,"octahedral normal precision (<~1 degree)");
        }
        byte[] data=Files.readAllBytes(path);
        Path bad=output.resolve("bad.fvmesh");Files.write(bad,Arrays.copyOf(data,data.length-1));
        try {MeshPage.read(bad);throw new AssertionError("truncated page accepted");}catch(IOException expected){checks++;}
        writeSnapshot(output.resolve("roundtrip.fvvol.gz"),s);
        var snapshot=VoxelSnapshot.read(output.resolve("roundtrip.fvvol.gz"));
        require(Arrays.equals(s.blocks(),snapshot.blocks())&&Arrays.equals(s.natural(),snapshot.natural()),"source roundtrip retains authored protection and block states");
    }

    private static void budget() {
        VoxelSnapshot s=fixture(46,64,46);
        for(int y=0;y<64;y++)for(int z=0;z<46;z++)for(int x=0;x<46;x++)
            if(((x+y+z)&1)==0)set(s,x,y,z,1,false);
        try {
            SurfaceExtractor.extract(s,7,7,32,true);
            throw new AssertionError("pathological tile exceeded its extraction budget without refusing");
        } catch(IllegalStateException expected) {
            require(expected.getMessage().contains("budget"),"complex tile must refuse on the explicit budget");
        }
    }

    private static Map<Long,Integer> edgeCounts(SurfaceMesh m) {
        var counts=new HashMap<Long,Integer>();
        for(int i=0;i<m.indices().length;i+=3)for(int k=0;k<3;k++) {
            int a=m.indices()[i+k],b=m.indices()[i+(k+1)%3];long key=((long)Math.min(a,b)<<32)|Math.max(a,b);counts.merge(key,1,Integer::sum);
        }
        return counts;
    }
    private static int euler(SurfaceMesh m){return m.vertices()-edgeCounts(m).size()+m.triangles();}
    private static boolean closed(SurfaceMesh m){return edgeCounts(m).values().stream().allMatch(v->v==2);}
    private static Set<String> edges(SurfaceMesh m,Set<String> contour) {
        var result=new TreeSet<String>();
        for(var e:edgeCounts(m).entrySet())if(e.getValue()==1) {
            int a=(int)(e.getKey()>>>32),b=(int)(long)e.getKey();
            if(contour.contains(point(m,a))&&contour.contains(point(m,b))) {
                String aa=point(m,a),bb=point(m,b);result.add(aa.compareTo(bb)<0?aa+"/"+bb:bb+"/"+aa);
            }
        }
        return result;
    }
    private static String point(SurfaceMesh m,int id) {
        return Arrays.toString(Arrays.copyOfRange(m.positions(),id*3,id*3+3))+Arrays.toString(Arrays.copyOfRange(m.normals(),id*3,id*3+3));
    }
    private static void valid(SurfaceMesh m) {
        boolean[] used=new boolean[m.vertices()];
        for(int index:m.indices())used[index]=true;
        for(boolean value:used)require(value,"unused output vertex");
        for(int i=0;i<m.indices().length;i+=3)require(m.indices()[i]!=m.indices()[i+1]&&m.indices()[i+1]!=m.indices()[i+2]&&m.indices()[i]!=m.indices()[i+2],"collapsed triangle in output");
    }
    private static VoxelSnapshot fixture(int sx,int sy,int sz) {
        return new VoxelSnapshot(-100_007,-64,100_007,sx,sy,sz,new String[]{"minecraft:air","minecraft:stone","minecraft:snow_block"},
                new String[]{"minecraft:plains","minecraft:plains","minecraft:snowy_plains"},new int[sx*sy*sz],new byte[sx*sy*sz],new byte[sx*sy*sz]);
    }
    private static void set(VoxelSnapshot s,int x,int y,int z,int id,boolean natural) {
        int i=s.index(x,y,z);s.blocks()[i]=id;s.natural()[i]=(byte)(natural?1:0);
    }
    private static VoxelSnapshot crop(VoxelSnapshot s,int x0,int y0,int z0,int sx,int sy,int sz) {
        VoxelSnapshot out=new VoxelSnapshot(s.originX()+x0,s.originY()+y0,s.originZ()+z0,sx,sy,sz,s.states(),s.biomes(),new int[sx*sy*sz],new byte[sx*sy*sz],new byte[sx*sy*sz]);
        for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++) {
            int a=s.index(x+x0,y+y0,z+z0),b=out.index(x,y,z);out.blocks()[b]=s.blocks()[a];out.light()[b]=s.light()[a];out.natural()[b]=s.natural()[a];
        }
        return out;
    }
    static void writeSnapshot(Path path,VoxelSnapshot s) throws IOException {
        try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
            out.writeBytes("FVOL0001");for(int value:new int[]{s.originX(),s.originY(),s.originZ(),s.sizeX(),s.sizeY(),s.sizeZ(),s.states().length})out.writeInt(value);
            for(int i=0;i<s.states().length;i++)for(String value:new String[]{s.states()[i],s.biomes()[i]}){byte[] b=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);}
            for(int block:s.blocks())out.writeInt(block);out.write(s.light());out.write(s.natural());
        }
    }
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
