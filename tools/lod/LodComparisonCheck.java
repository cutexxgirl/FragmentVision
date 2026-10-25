import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.nio.file.*;
import java.util.*;
import me.cortex.voxy.client.core.util.ScanMesher2D;

/** Geometry comparison on identical volume, face attributes and world-aligned scan domains. */
public final class LodComparisonCheck {
    private static final int H=7,W=32;
    private static int assertions;
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);
        scannerRegression();meshDictionary(output);mixedContour();
        var report=new StringBuilder("{\"scope\":\"same unit faces/state/biome/light; shipping ScanMesher2D default16 vs packed32/direct QEM; no smoothing pass; world error caps 0.5/1/1.5 blocks; not game GPU timing\",\"scenes\":[\n");
        String[] scenes={"plane","hills","rugged","arch","protected","material_light","flat_step_neighbour"};
        for(int kind=0;kind<scenes.length;kind++) {
            VoxelSnapshot s=fixture(kind);
            if(kind==1||kind==3)LodBakeCheck.writeSnapshot(output.resolve(scenes[kind]+".fvvol.gz"),s);
            var baked=TileBake.build(s,H,H,W);var exact=baked.exact();
            Set<String> expected=unitFaces(s),actual=expand(exact.selected());
            require(expected.equals(actual),"exact face/material/light coverage: "+scenes[kind]);
            require(expected.equals(expand(exact.baseline())),"baseline coverage: "+scenes[kind]);
            require(exact.visibleUnitFaces()==expected.size(),"visible face accounting");
            require(exact.selected().data().length<=exact.baseline().data().length,"packed selection must never regress the baseline per domain");
            for(int level=0;level<3;level++) {
                var selected=baked.choices()[level];
                require(selected.triangles()<=exact.baseline().triangles(),"selected triangles exceed baseline");
                require(selected.payloadBytes()<=exact.baseline().payloadBytes(),"selected geometry bytes exceed baseline");
                require(selected.triangles()<=exact.selected().triangles()&&selected.payloadBytes()<=exact.selected().payloadBytes(),"must also beat own exact representation");
                if(selected.isMesh())require(baked.errors()[level]!=null&&baked.errors()[level].withinLimit()&&baked.errors()[level].upperBound()<=TileBake.errorLimit(level),"selected geometry must pass independent distance bound");
            }
            if(kind==0) {
                require(exact.baseline().triangles()==8,"shipping plane baseline should be four 16x16 quads");
                require(exact.selected().triangles()==2&&exact.selected().payloadBytes()==8,"plane must become one packed rectangle");
            }
            Path file=output.resolve(scenes[kind]+".fvquad");Files.deleteIfExists(file);
            exact.selected().write(file,0,0,0);
            require(Arrays.equals(exact.selected().data(),PackedQuads.read(file).quads().data()),"quad codec roundtrip");
            require(Files.size(file)==24+exact.selected().payloadBytes(),"quad file accounting");
            if(kind>0)report.append(",\n");
            report.append("{\"name\":\"").append(scenes[kind]).append("\",\"voxy_triangles\":").append(exact.baseline().triangles())
                    .append(",\"voxy_quad_payload_bytes\":").append(exact.baseline().payloadBytes()).append(",\"packed_triangles\":").append(exact.selected().triangles())
                    .append(",\"packed_payload_bytes\":").append(exact.selected().payloadBytes()).append(",\"levels\":[");
            for(int i=0;i<3;i++) {
                var selected=baked.choices()[i];var mesh=baked.candidates()==null?null:baked.candidates()[i];
                if(i>0)report.append(',');report.append("{\"level\":").append(i).append(",\"representation\":\"").append(selected.isMesh()?"mesh":"quads")
                        .append("\",\"triangles\":").append(selected.triangles()).append(",\"payload_bytes\":").append(selected.payloadBytes())
                        .append(",\"error_limit_blocks\":").append(TileBake.errorLimit(i)).append(",\"candidate_error\":").append(BakeProofMain.errorJson(baked.errors()[i])).append(",\"candidate_triangles\":").append(mesh==null?"null":mesh.triangles())
                        .append(",\"candidate_payload_bytes\":").append(mesh==null?"null":mesh.packedBytes()).append('}');
            }
            report.append("]}");
            System.out.printf("%s: Voxy=%d tri/%d B; packed=%d tri/%d B; far=%d tri/%d B (%s)%n",scenes[kind],exact.baseline().triangles(),exact.baseline().payloadBytes(),exact.selected().triangles(),exact.selected().payloadBytes(),baked.choices()[2].triangles(),baked.choices()[2].payloadBytes(),baked.choices()[2].reason());
        }
        Files.writeString(output.resolve("comparison.json"),report.append("\n]}\n").toString());
        System.out.println("Voxy comparison assertions passed: "+assertions);
    }
    static VoxelSnapshot fixture(int kind) {
        int sx=46,sy=40,sz=46;
        var s=new VoxelSnapshot(-H,0,-H,sx,sy,sz,new String[]{"minecraft:air","minecraft:stone","minecraft:snow_block"},new String[]{"minecraft:plains","minecraft:plains","minecraft:snowy_plains"},new int[sx*sy*sz],new byte[sx*sy*sz],new byte[sx*sy*sz]);
        for(int z=0;z<sz;z++)for(int x=0;x<sx;x++) {
            int height=switch(kind){case 1->12+(int)(4*Math.sin(x*.19)+3*Math.cos(z*.16));case 2->15+(int)(8*Math.sin(x*.7)*Math.cos(z*.65));case 6->x<39?8:12;default->8;};
            for(int y=0;y<height;y++) {
                int p=s.index(x,y,z);s.blocks()[p]=kind==5&&((x/4+z/4)&1)==0?2:1;s.natural()[p]=(byte)(kind==4?0:1);
            }
            if(kind==5)s.light()[s.index(x,height,z)]=(byte)(x<23?0xf0:0x70);
        }
        if(kind==3) {
            Arrays.fill(s.blocks(),0);Arrays.fill(s.natural(),(byte)0);
            for(int z=12;z<34;z++)for(int x=12;x<34;x++)for(int y=5;y<27;y++)if(!(x>=19&&x<27&&y<19)) {
                int p=s.index(x,y,z);s.blocks()[p]=1;s.natural()[p]=1;
            }
        }
        return s;
    }
    private static Set<String> unitFaces(VoxelSnapshot s) {
        int[][] n={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};var faces=new HashSet<String>();
        for(int y=0;y<s.sizeY();y++)for(int z=H;z<H+W;z++)for(int x=H;x<H+W;x++)if(s.block(x,y,z)>0)for(int f=0;f<6;f++) {
            int nx=x+n[f][0],ny=y+n[f][1],nz=z+n[f][2];
            if(s.block(nx,ny,nz)==0)faces.add(key(x-H,y,z-H,f,s.block(x,y,z),s.light()[s.index(nx,ny,nz)]&255));
        }
        return faces;
    }
    private static Set<String> expand(PackedQuads packed) {
        var faces=new HashSet<String>();
        for(long q:packed.data())for(int v=0;v<PackedQuads.v(q);v++)for(int u=0;u<PackedQuads.u(q);u++) {
            int f=PackedQuads.face(q),x=PackedQuads.x(q)+(f<2?0:u),y=PackedQuads.y(q)+(f<2?v:f<4?0:v),z=PackedQuads.z(q)+(f<2?u:f<4?v:0);
            require(faces.add(key(x,y,z,f,PackedQuads.material(q),PackedQuads.light(q))),"overlapping exact quads");
        }
        return faces;
    }
    private static void scannerRegression() {
        var random=new Random(413);
        for(int trial=0;trial<100;trial++) {
            long[] input=new long[1024];for(int i=0;i<input.length;i++)input[i]=random.nextInt(5)==0?0:1+random.nextInt(3);
            var defaultRects=new ArrayList<String>();
            long[] recovered=new long[1024];
            var shipping=new ScanMesher2D(){@Override protected void emitQuad(int x,int z,int w,int h,long data){defaultRects.add(x+","+z+","+w+","+h+","+data);}};
            RectangleMerger.Emitter wider=(x,z,w,h,data)->{
                require(w>=1&&w<=32&&h>=1&&h<=32,"scan rectangle bounds");
                for(int v=z;v<z+h;v++)for(int u=x;u<x+w;u++){
                    require(v>=0&&v<32&&u>=0&&u<32,"scan output bounds");
                    require(recovered[v*32+u]==0,"scan overlap");recovered[v*32+u]=data;
                }
            };
            for(long value:input)shipping.putNext(value);
            shipping.finish();RectangleMerger.scan(input,wider);
            require(!defaultRects.isEmpty(),"shipping baseline was executed");
            require(Arrays.equals(input,recovered),"wider scan must preserve exact material coverage");
        }
        long q=PackedQuads.encode(63,511,63,64,64,5,65535,255);
        require(PackedQuads.x(q)==63&&PackedQuads.y(q)==511&&PackedQuads.z(q)==63&&PackedQuads.u(q)==64&&PackedQuads.v(q)==64&&PackedQuads.face(q)==5&&PackedQuads.material(q)==65535&&PackedQuads.light(q)==255,"packed bitfield limits");
        try{new PackedQuads(new long[]{q|(1L<<60)});throw new AssertionError("reserved bits accepted");}catch(IllegalArgumentException expected){assertions++;}
    }
    private static void meshDictionary(Path output) throws Exception {
        int count=257;float[] p=new float[count*9],n=new float[p.length];int[] indices=new int[count*3],attrs=new int[count];
        var random=new Random(201);
        for(int i=0;i<count;i++) {
            double nx=random.nextDouble()-.5,ny=random.nextDouble()-.5,nz=random.nextDouble()-.5,length=Math.sqrt(nx*nx+ny*ny+nz*nz);
            for(int k=0;k<3;k++){int id=i*3+k;indices[id]=id;p[id*3]=i%20+(k==1?1:0);p[id*3+1]=i/20;p[id*3+2]=k==2?1:0;n[id*3]=(float)(nx/length);n[id*3+1]=(float)(ny/length);n[id*3+2]=(float)(nz/length);}
            attrs[i]=((i+1)<<11)|(3<<8)|(i&255);
        }
        SurfaceMesh mesh=new SurfaceMesh(p,n,indices,attrs,new boolean[count*3]);
        Path path=output.resolve("wide-dictionary.fvmesh");Files.deleteIfExists(path);MeshPage.write(path,mesh,0,0,0,0,0);
        var decoded=MeshPage.read(path).mesh();
        require(Arrays.equals(attrs,decoded.faceData())&&Arrays.equals(p,decoded.positions()),"16-bit attribute dictionary preserves all material/light data and positions");
        require(Files.size(path)==MeshPage.HEADER_BYTES+mesh.packedBytes(),"dictionary byte accounting");
        for(int i=0;i<n.length;i+=3)require(n[i]*decoded.normals()[i]+n[i+1]*decoded.normals()[i+1]+n[i+2]*decoded.normals()[i+2]>.9998,"oct8 error on arbitrary normals");
        Path legacy=output.resolve("legacy.fvmesh");
        try(var out=new java.io.DataOutputStream(Files.newOutputStream(legacy))) {
            out.writeBytes("FVMSH001");for(int value:new int[]{0,0,0,3,1,256})out.writeInt(value);
            for(int i=0;i<3;i++){out.writeShort(i*256);out.writeInt(256);out.writeShort(0);out.writeShort(0);out.writeShort(0);}
            out.writeInt(0);out.writeInt(1);out.writeInt(2);out.writeInt(2048);
        }
        require(MeshPage.read(legacy).mesh().triangles()==1,"v1 proof pages remain readable");
        // Exercise a full 64-bit triangle word, including its sign bit: 16*3 index + 16 attribute bits.
        int limit=65536;float[] lp=new float[limit*3],ln=new float[lp.length];int[] li=new int[limit*3],la=new int[limit];
        for(int i=0;i<limit;i++){ln[i*3+1]=1;li[i*3]=i;li[i*3+1]=(i+1)&65535;li[i*3+2]=(i+2)&65535;la[i]=((i/2+1)<<11)|(3<<8)|(i&1);}
        SurfaceMesh largest=new SurfaceMesh(lp,ln,li,la,new boolean[limit]);
        Path large=output.resolve("wide-indices.fvmesh");Files.deleteIfExists(large);MeshPage.write(large,largest,0,0,0,0,0);
        var largeRead=MeshPage.read(large).mesh();
        require(Arrays.equals(li,largeRead.indices())&&Arrays.equals(la,largeRead.faceData()),"full 64-bit triangle word retains unsigned indices and dictionary IDs");
        SurfaceMesh empty=new SurfaceMesh(new float[0],new float[0],new int[0],new int[0],new boolean[0]);
        Path zero=output.resolve("empty.fvmesh");Files.deleteIfExists(zero);MeshPage.write(zero,empty,0,0,0,0,0);
        require(MeshPage.read(zero).mesh().triangles()==0&&Files.size(zero)==MeshPage.HEADER_BYTES,"empty v3 page");
    }
    private static void mixedContour() {
        int sx=78,sy=32,sz=46;
        var s=new VoxelSnapshot(-H,0,-H,sx,sy,sz,new String[]{"minecraft:air","minecraft:stone"},new String[]{"minecraft:plains","minecraft:plains"},new int[sx*sy*sz],new byte[sx*sy*sz],new byte[sx*sy*sz]);
        for(int z=0;z<sz;z++)for(int x=0;x<sx;x++)for(int y=0,h=x<39?8:12+(int)(3*Math.sin((x-39)*.2)+Math.sin(z*.3));y<h;y++) {int id=s.index(x,y,z);s.blocks()[id]=1;s.natural()[id]=1;}
        var left=GreedyQuadMesher.build(s,H,H,W).selected();
        require(left.triangles()==2,"flat tile beside a slope stays one rectangle");
        SurfaceMesh right=ConformingSurface.extract(s,39,H,W,GreedyQuadMesher.build(s,39,H,W).selected());
        right=QuadricSimplifier.simplifyDirect(right,.1,8.).mesh();
        int found=0;
        for(int i=0;i<right.vertices();i++)if(right.positions()[i*3]==39&&right.positions()[i*3+1]==8) {
            float z=right.positions()[i*3+2];require(z>=H&&z<=H+W&&z==Math.rint(z),"mesh contour stays on the packed neighbour's exact edge");found++;
        }
        require(found==W+1,"complete packed/smooth shared edge");
    }
    private static String key(int x,int y,int z,int f,int m,int l){return x+","+y+","+z+","+f+","+m+","+l;}
    private static void require(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
