import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.nio.IntBuffer;
import java.util.*;

public final class QuadFaceBucketsCheck {
    public static void main(String[] args) throws Exception {
        Random random=new Random(193);
        long[] q=new long[600];
        for(int i=0;i<q.length;i++)q[i]=PackedQuads.encode(random.nextInt(32),random.nextInt(64),random.nextInt(32),1,1,i%6,i%5==0?2:1,255);
        var source=new PackedQuads(q);
        var partition=QuadFaceBuckets.partition(source,new boolean[]{false,true,false});
        long[] a=q.clone(),b=partition.quads().data().clone();Arrays.sort(a);Arrays.sort(b);
        if(!Arrays.equals(a,b))throw new AssertionError("Partition lost or duplicated quads");
        IntBuffer commands=IntBuffer.allocate(35);
        for(int trial=0;trial<1000;trial++) {
            double x=random.nextDouble()*200-100,y=random.nextDouble()*200-100,z=random.nextDouble()*200-100;
            commands.clear();long submitted=partition.layout().commands(commands,x,y,z),count=0;
            for(int group=0;group<7;group++) {
                int offset=commands.get(group*5+4),instances=commands.get(group*5+1);
                if(commands.get(group*5)!=6||commands.get(group*5+2)!=0||commands.get(group*5+3)!=0)throw new AssertionError("Indirect ABI");
                count+=instances*2L;
                for(int i=partition.layout().starts()[group];i<partition.layout().starts()[group]+partition.layout().counts()[group];i++) {
                    long quad=partition.quads().data()[i];int face=PackedQuads.face(quad);
                    double plane=(face<2?PackedQuads.x(quad):face<4?PackedQuads.y(quad):PackedQuads.z(quad))+(face&1);
                    double camera=face<2?x:face<4?y:z;
                    boolean front=(face&1)==0?camera<=plane+.01:camera>=plane-.01;
                    if((front||PackedQuads.material(quad)==2)&&instances==0)throw new AssertionError("Culled visible or two-sided quad");
                    if(instances>0&&(i<offset||i>=offset+instances))throw new AssertionError("Bad baseInstance span");
                }
            }
            if(count!=submitted)throw new AssertionError("Submission counter");
        }
        long[] cube=new long[6];for(int f=0;f<6;f++)cube[f]=PackedQuads.encode(0,0,0,1,1,f,1,255);
        var shell=QuadFaceBuckets.partition(new PackedQuads(cube),new boolean[]{false,true});
        commands.clear();if(shell.layout().commands(commands,10,10,10)!=6)throw new AssertionError("Outside cube must submit three faces");
        commands.clear();if(shell.layout().commands(commands,.5,.5,.5)!=0)throw new AssertionError("Inside opaque cube");
        var leaves=QuadFaceBuckets.partition(new PackedQuads(cube),new boolean[]{false,false});
        commands.clear();if(leaves.layout().commands(commands,.5,.5,.5)!=12)throw new AssertionError("Two-sided material must survive");
        // Export actual Java-produced indirect commands for shipping-shader readback.
        var root=java.nio.file.Path.of("build/lod-proof/gpu-layout");java.nio.file.Files.createDirectories(root);
        long[] scene=new long[18];
        for(int c=0;c<3;c++)for(int face=0;face<6;face++)scene[c*6+face]=PackedQuads.encode(c*4,0,0,1,1,face,c==2?2:1,255);
        var grouped=QuadFaceBuckets.partition(new PackedQuads(scene),new boolean[]{false,true,false});
        for(int variant=0;variant<2;variant++) {
            var bytes=java.nio.ByteBuffer.allocate(scene.length*8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for(long quad:variant==0?scene:grouped.quads().data())bytes.putLong(quad);
            java.nio.file.Files.write(root.resolve(variant==0?"bucket-source.bin":"bucket-sorted.bin"),bytes.array());
        }
        double[][] cameras={{20,15,20},{-15,15,20},{20,-15,20},{20,15,-20},{4,20,0},{4,-20,0}};
        for(int i=0;i<cameras.length;i++) {
            var bytes=java.nio.ByteBuffer.allocate(140).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            grouped.layout().commands(bytes.asIntBuffer(),cameras[i][0],cameras[i][1],cameras[i][2]);
            java.nio.file.Files.write(root.resolve("bucket-commands-"+i+".bin"),bytes.array());
        }
        if(args.length==1) {
            VoxelSnapshot saved=VoxelSnapshot.read(java.nio.file.Path.of(args[0]));
            boolean[] eligible=new boolean[saved.states().length];
            for(int cell=0;cell<saved.blocks().length;cell++)if(saved.natural()[cell]!=0)eligible[saved.blocks()[cell]]=true;
            PackedQuads exact=GreedyQuadMesher.build(saved,7,7,32).selected();
            var real=QuadFaceBuckets.partition(exact,eligible);
            commands.clear();long sent=real.layout().commands(commands,512-(saved.originX()+7),180-saved.originY(),704-(saved.originZ()+7));
            System.out.printf("Saved snapshot, camera (512,180,704), culling eligible ground only: %d -> %d submitted triangles; leaves/non-ground retained%n",exact.triangles(),sent);
        }
        System.out.println("Quad direction buckets: 1000 camera positions, indirect ABI, opaque cube and two-sided coverage passed");
    }
}
