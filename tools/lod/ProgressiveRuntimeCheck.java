import me.cortex.voxy.client.core.rendering.lod.bake.*;
import me.cortex.voxy.client.core.rendering.lod.runtime.LodWorkQueue;
import java.util.*;
import java.util.concurrent.*;

public final class ProgressiveRuntimeCheck {
    private static int checks;
    public static void main(String[] args)throws Exception {
        var s=LodComparisonCheck.fixture(1);
        var source=ConformingSurface.extract(s,7,7,32,GreedyQuadMesher.build(s,7,7,32).selected());
        var p=QuadricSimplifier.progressive(source,.1,8);
        require(p.steps().size()>100,"nontrivial history");
        byte[] encoded=ProgressiveMeshFile.encode(p);
        var replay=ProgressiveMeshFile.decode(encoded,encoded.length);
        require(replay.steps().equals(p.steps()),"dependent history survives disk format");
        for(int prefix:p.threePrefixes()) {
            var a=p.level(prefix);var b=replay.level(prefix);
            require(Arrays.equals(a.positions(),b.positions())&&Arrays.equals(a.normals(),b.normals())&&Arrays.equals(a.indices(),b.indices())&&Arrays.equals(a.faceData(),b.faceData())&&Arrays.equals(a.locked(),b.locked()),"linked levels replay bit-exactly");
        }
        int last=source.triangles();
        for(int prefix:p.threePrefixes()) {
            var mesh=p.level(prefix);require(mesh.triangles()<last,"three distinct decreasing levels");last=mesh.triangles();
        }
        for(int i=0;i<p.steps().size();i+=Math.max(1,p.steps().size()/20)) {
            var before=p.level(i);var after=p.level(i+1);
            require(Arrays.equals(before.positions(),compactPositions(p.transition(i,0))),"transition starts exactly at fine geometry");
            require(trianglesByCoordinates(after).equals(nonzeroTriangles(p.transition(i,1))),"transition endpoint equals compact coarse geometry including attributes");
            var frame=p.transition(i,.5);require(frame.triangles()==before.triangles(),"one fine topology during morph");
            for(int v=0;v<frame.vertices();v++)if(source.locked()[v])for(int c=0;c<3;c++)require(frame.positions()[v*3+c]==source.positions()[v*3+c],"source contour never moves");
        }
        var far=p.level(p.steps().size());
        require(SurfaceError.compare(source,far,1.5).withinLimit(),"linked far surface passes original distance audit");
        // History owns input and accessors do not expose its source arrays.
        var exported=p.source();exported.positions()[0]+=100;
        require(p.source().positions()[0]==source.positions()[0],"source accessor isolated");
        workQueue();System.out.println("Progressive/runtime checks passed: "+checks+"; levels="+Arrays.toString(Arrays.stream(p.threePrefixes()).map(i->p.level(i).triangles()).toArray()));
    }
    private static float[] compactPositions(SurfaceMesh mesh){var ids=new LinkedHashSet<Integer>();for(int v:mesh.indices())ids.add(v);float[] p=new float[ids.size()*3];int n=0;for(int v:ids)for(int c=0;c<3;c++)p[n++]=mesh.positions()[v*3+c];return p;}
    private static Set<String> trianglesByCoordinates(SurfaceMesh m){return nonzeroTriangles(m);}
    private static Set<String> nonzeroTriangles(SurfaceMesh m) {
        var out=new HashSet<String>();
        for(int t=0;t<m.triangles();t++) {
            float[][] v=new float[3][3];for(int k=0;k<3;k++)System.arraycopy(m.positions(),m.indices()[t*3+k]*3,v[k],0,3);
            double ax=v[1][0]-v[0][0],ay=v[1][1]-v[0][1],az=v[1][2]-v[0][2],bx=v[2][0]-v[0][0],by=v[2][1]-v[0][1],bz=v[2][2]-v[0][2];
            double x=ay*bz-az*by,y=az*bx-ax*bz,z=ax*by-ay*bx;if(x*x+y*y+z*z<1e-20)continue;
            out.add(Arrays.deepToString(v)+":"+m.faceData()[t]);
        }return out;
    }
    private static void workQueue()throws Exception {
        try(var queue=new LodWorkQueue<String,String>(2,3,300)) {
            var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
            for(int i=0;i<2;i++){final int id=i;require(queue.submit("block"+i,1,0,100,()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new CancellationException();}return "ok";}),"parallel job accepted");}
            require(entered.await(5,TimeUnit.SECONDS),"workers actually run concurrently");
            require(queue.submit("replace",1,9,100,()->"old"),"queued job accepted");
            require(!queue.submit("overflow",1,0,100,()->"bad"),"queue/memory bounded");
            require(queue.submit("replace",2,1,100,()->"new"),"pending replacement reuses reservation");
            require(!queue.submit("replace",1,0,100,()->"stale"),"old revision refused");
            release.countDown();
            int completed=0;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean sawNew=false;
            while(completed<3&&System.nanoTime()<deadline) {
                var item=queue.poll((key,revision)->true);
                if(item==null){Thread.onSpinWait();continue;}
                require(item.failure()==null,"worker succeeded");completed++;
                if(item.key().equals("replace")){require(item.revision()==2&&item.value().equals("new"),"only latest output published");sawNew=true;}
            }
            require(completed==3&&sawNew&&queue.reservedBytes()==0&&queue.jobCount()==0,"output drain releases all reservations");
            require(queue.submit("discard",1,0,100,()->"stale output"),"stale output task accepted");
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(queue.jobCount()>0&&System.nanoTime()<deadline)if(queue.poll((key,revision)->false)!=null)throw new AssertionError("world revision predicate accepted stale output");
            require(queue.jobCount()==0&&queue.reservedBytes()==0,"stale output reservation released");
            var started=new CountDownLatch(1);var ended=new CountDownLatch(1);
            require(queue.submit("cancel",1,0,100,()->{started.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){ended.countDown();throw new CancellationException();}return "bad";}),"cancellation task accepted");
            require(started.await(5,TimeUnit.SECONDS),"cancellation task started");queue.invalidate("cancel");require(ended.await(5,TimeUnit.SECONDS),"running work interrupted");
        }
    }
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
