import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.util.*;

/** Checks the direct path independently of its QEM objective and budget selection. */
public final class DirectGeometryCheck {
    private static int assertions;
    public static void main(String[] args) {
        for(int kind=0;kind<7;kind++) {
            var snapshot=LodComparisonCheck.fixture(kind);
            var packed=GreedyQuadMesher.build(snapshot,7,7,32).selected();
            var source=ConformingSurface.extract(snapshot,7,7,32,packed);
            var expected=new HashMap<Integer,Double>();
            for(long q:packed.data())expected.merge((PackedQuads.material(q)<<11)|(PackedQuads.face(q)<<8)|PackedQuads.light(q),(double)PackedQuads.u(q)*PackedQuads.v(q),Double::sum);
            var area=new HashMap<Integer,Double>();
            for(int t=0;t<source.triangles();t++) {
                double[] a=point(source,source.indices()[t*3]),b=point(source,source.indices()[t*3+1]),c=point(source,source.indices()[t*3+2]);
                double[] ab=sub(b,a),ac=sub(c,a),cross=cross(ab,ac);
                int face=(source.faceData()[t]>>>8)&7,axis=face/2;double signed=cross[axis]*(face%2==0?-1:1);
                require(signed>0,"direct triangle winding/positive area");
                for(int dim=0;dim<3;dim++)if(dim!=axis)require(cross[dim]==0,"source triangles stay in original face planes");
                area.merge(source.faceData()[t],signed*.5,Double::sum);
            }
            require(area.equals(expected),"exact area preserved separately for every face/state/biome/light key");
            for(float value:source.positions())require(value==Math.rint(value),"no movement or smoothing during source extraction");
            if(kind==1||kind==3) {
                checkEdges(source,kind==3);
                if(kind==3)checkEdges(QuadricSimplifier.simplifyDirect(source,.1,8.).mesh(),true);
                var reduced=TileBake.build(snapshot,7,7,32).choices()[2];
                if(reduced.isMesh())checkEdges(reduced.mesh(),false);
            }
        }
        protectedInterior();distanceChecks();
        System.out.println("Direct geometry assertions passed: "+assertions);
    }
    private static void checkEdges(SurfaceMesh mesh,boolean closed) {
        var counts=new HashMap<Long,Integer>();
        for(int t=0;t<mesh.triangles();t++)for(int k=0;k<3;k++) {
            int a=mesh.indices()[t*3+k],b=mesh.indices()[t*3+(k+1)%3];
            counts.merge(((long)Math.min(a,b)<<32)|Math.max(a,b),1,Integer::sum);
        }
        for(var entry:counts.entrySet()) {
            require(entry.getValue()==2||(!closed&&entry.getValue()==1),"manifold surface incidence");
            double[] a=point(mesh,(int)(entry.getKey()>>>32)),b=point(mesh,(int)(long)entry.getKey());
            if(entry.getValue()==1)require((a[0]==b[0]&&(a[0]==7||a[0]==39))||(a[2]==b[2]&&(a[2]==7||a[2]==39)),"only canonical tile border may be open");
            for(int v=0;v<mesh.vertices();v++) {
                double[] p=point(mesh,v),ab=sub(b,a),ap=sub(p,a),cp=cross(ab,ap);
                double dot=dot(ab,ap),length=dot(ab,ab);
                require(!(dot>1e-8&&dot<length-1e-8&&dot(cp,cp)<1e-12),"no remaining geometric T-junction a="+Arrays.toString(a)+" b="+Arrays.toString(b)+" p="+Arrays.toString(p));
            }
        }
        require(mesh.vertices()-counts.size()+mesh.triangles()==(closed?2:1),"surface Euler characteristic preserved");
    }
    private static void protectedInterior() {
        var snapshot=LodComparisonCheck.fixture(1);var packed=GreedyQuadMesher.build(snapshot,7,7,32).selected();
        long chosen=0;boolean found=false;
        for(long q:packed.data())if(PackedQuads.face(q)==3&&PackedQuads.u(q)>=3&&PackedQuads.v(q)>=3) {chosen=q;found=true;break;}
        require(found,"fixture has a large rectangle with protected voxel away from corners");
        int x=PackedQuads.x(chosen)+7,y=PackedQuads.y(chosen),z=PackedQuads.z(chosen)+7;
        snapshot.natural()[snapshot.index(x+1,y,z+1)]=0;
        var source=ConformingSurface.extract(snapshot,7,7,32,packed);
        int locked=0;
        for(int v=0;v<source.vertices();v++) {
            double[] p=point(source,v);
            if(p[1]==y+1&&p[0]>=x&&p[0]<=x+PackedQuads.u(chosen)&&p[2]>=z&&p[2]<=z+PackedQuads.v(chosen)) {
                require(source.locked()[v],"protection inside a merged rectangle locks its whole polygon");locked++;
            }
        }
        require(locked>=4,"all rectangle corners protected");
    }
    private static SurfaceMesh square(float height,boolean hole) {
        if(hole) {
            float[] p={0,height,0, 4,height,0, 4,height,4, 0,height,4, 1,height,1, 3,height,1, 3,height,3, 1,height,3};
            int[] t={0,1,5,0,5,4, 1,2,6,1,6,5, 2,3,7,2,7,6, 3,0,4,3,4,7};
            return new SurfaceMesh(p,new float[p.length],t,new int[t.length/3],new boolean[p.length/3]);
        }
        return new SurfaceMesh(new float[]{0,height,0,4,height,0,4,height,4,0,height,4},new float[12],new int[]{0,1,2,0,2,3},new int[2],new boolean[4]);
    }
    private static void distanceChecks() {
        var plane=square(0,false);
        var same=SurfaceError.compare(plane,plane,.25);
        require(same.withinLimit()&&same.sampledMax()<1e-12&&same.upperBound()<=.25,"identical geometry passes conservative bound");
        var translated=SurfaceError.compare(plane,square(.375f,false),.5);
        require(translated.withinLimit()&&translated.sampledMax()>=.375&&translated.upperBound()>=.375&&translated.upperBound()<=.5,"known parallel-plane distance is enclosed");
        require(!SurfaceError.compare(plane,square(.375f,false),.25).withinLimit(),"actual distance exceeds cap");
        var ring=square(0,true);
        require(!SurfaceError.compare(plane,ring,.5).withinLimit(),"a hole with all outer vertices unchanged must fail");
        require(!SurfaceError.compare(ring,plane,.5).withinLimit(),"reverse direction must detect a filled hole");
        var empty=new SurfaceMesh(new float[0],new float[0],new int[0],new int[0],new boolean[0]);
        require(!SurfaceError.compare(empty,plane,1).withinLimit(),"missing surface rejected");
        var unresolved=SurfaceError.compare(plane,plane,1e-9);
        require(!unresolved.withinLimit()&&unresolved.reason().contains("budget"),"finite subdivision budget must fail closed");
    }
    private static double[] point(SurfaceMesh m,int id){return new double[]{m.positions()[id*3],m.positions()[id*3+1],m.positions()[id*3+2]};}
    private static double[] sub(double[] a,double[] b){return new double[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]};}
    private static double[] cross(double[] a,double[] b){return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    private static void require(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
