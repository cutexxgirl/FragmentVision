package me.cortex.voxy.client.core.rendering.lod.bake;

import java.util.*;

/** Direct boundary from merged rectangles, without unit-face tessellation or Laplacian smoothing.
 * Rectangle edges are split at all touching corners before triangulation: welding just the four
 * corners would leave T-junctions and an incorrect edge-collapse topology.
 */
public final class ConformingSurface {
    private static final int MAX_VERTICES=65536,MAX_TRIANGLES=100000;
    private static final int[][] N={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private record Point(int x,int y,int z){int coordinate(int c){return c==0?x:c==1?y:z;}}
    private static final class Vertex {
        final Point p;
        int domain=-1;
        boolean locked;
        Vertex(Point p){this.p=p;}
    }
    private record Line(int axis,int a,int b){}
    public static SurfaceMesh extract(VoxelSnapshot s,int minX,int minZ,int width,PackedQuads quads) {
        if(quads.data().length>32768)throw new IllegalStateException("Direct surface quad budget exceeded");
        SurfaceMesh rectangles=quads.mesh(0,0);
        var ids=new HashMap<Point,Integer>();var vertices=new ArrayList<Vertex>();
        int[][] corners=new int[quads.data().length][4];
        for(int q=0;q<corners.length;q++)for(int k=0;k<4;k++) {
            int p=q*12+k*3;Point point=new Point((int)rectangles.positions()[p],(int)rectangles.positions()[p+1],(int)rectangles.positions()[p+2]);
            corners[q][k]=vertex(point,ids,vertices);
        }
        // Exact lattice contour also joins adjacent packed pages and independently generated tiles.
        for(int[] quad:corners)for(int k=0;k<4;k++) {
            Point a=vertices.get(quad[k]).p,b=vertices.get(quad[(k+1)&3]).p;
            boolean border=(a.x==b.x&&(a.x==0||a.x==width))||(a.z==b.z&&(a.z==0||a.z==width));
            if(!border)continue;
            int axis=axis(a,b),lo=Math.min(a.coordinate(axis),b.coordinate(axis)),hi=Math.max(a.coordinate(axis),b.coordinate(axis));
            for(int t=lo+1;t<hi;t++)vertex(new Point(axis==0?t:a.x,axis==1?t:a.y,axis==2?t:a.z),ids,vertices);
        }
        var lines=new HashMap<Line,TreeMap<Integer,Integer>>();
        for(int i=0;i<vertices.size();i++) {
            Point p=vertices.get(i).p;
            for(int axis=0;axis<3;axis++)lines.computeIfAbsent(line(p,axis),key->new TreeMap<>()).put(p.coordinate(axis),i);
        }
        var triangles=new ArrayList<Integer>();var attributes=new ArrayList<Integer>();
        for(int q=0;q<corners.length;q++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            var polygon=new ArrayList<Integer>();
            for(int k=0;k<4;k++) {
                Point a=vertices.get(corners[q][k]).p,b=vertices.get(corners[q][(k+1)&3]).p;
                int axis=axis(a,b),ac=a.coordinate(axis),bc=b.coordinate(axis);
                var part=lines.get(line(a,axis)).subMap(Math.min(ac,bc),true,Math.max(ac,bc),true);
                Collection<Integer> ordered=ac<bc?part.values():part.descendingMap().values();
                for(int id:ordered)if(id!=corners[q][(k+1)&3])polygon.add(id);
            }
            int attr=rectangles.faceData()[q*2],domain=attr&~(7<<8);
            boolean protectedRectangle=false;
            long packed=quads.data()[q];
            for(int v=0;v<PackedQuads.v(packed);v++)for(int u=0;u<PackedQuads.u(packed);u++) {
                int f=PackedQuads.face(packed),vx=minX+PackedQuads.x(packed)+(f<2?0:u),
                        vy=PackedQuads.y(packed)+(f<2?v:f<4?0:v),
                        vz=minZ+PackedQuads.z(packed)+(f<2?u:f<4?v:0);
                if(s.natural()[s.index(vx,vy,vz)]==0)protectedRectangle=true;
            }
            for(int id:polygon){Vertex v=vertices.get(id);if(protectedRectangle||(v.domain!=-1&&v.domain!=domain))v.locked=true;v.domain=domain;}
            triangulate(polygon,vertices,(attr>>>8)&7,attr,triangles,attributes);
        }
        // Compact only vertices used by emitted triangles; line lookup may contain unused junctions.
        int[] remap=new int[vertices.size()];Arrays.fill(remap,-1);int count=0;
        for(int id:triangles)if(remap[id]<0)remap[id]=count++;
        float[] p=new float[count*3],n=new float[count*3];boolean[] locked=new boolean[count];
        for(int i=0;i<vertices.size();i++)if(remap[i]>=0) {
            Vertex v=vertices.get(i);Point point=v.p;int to=remap[i];
            p[to*3]=point.x+minX;p[to*3+1]=point.y;p[to*3+2]=point.z+minZ;
            int wx=point.x+minX,wy=point.y,wz=point.z+minZ;double nx=0,ny=0,nz=0;
            boolean fixed=v.locked||point.x==0||point.x==width||point.z==0||point.z==width;
            for(int dz=-1;dz<=0;dz++)for(int dy=-1;dy<=0;dy++)for(int dx=-1;dx<=0;dx++) {
                int x=wx+dx,y=wy+dy,z=wz+dz,block=s.block(x,y,z);
                if(block<0){fixed=true;continue;}if(block==0)continue;
                if(s.natural()[s.index(x,y,z)]==0)fixed=true;
                int[] faces={dx==0?0:1,dy==0?2:3,dz==0?4:5};
                for(int face:faces)if(s.block(x+N[face][0],y+N[face][1],z+N[face][2])==0){nx+=N[face][0];ny+=N[face][1];nz+=N[face][2];}
            }
            double length=Math.sqrt(nx*nx+ny*ny+nz*nz);
            n[to*3]=length>0?(float)(nx/length):0;n[to*3+1]=length>0?(float)(ny/length):1;n[to*3+2]=length>0?(float)(nz/length):0;
            locked[to]=fixed;
        }
        int[] indices=triangles.stream().mapToInt(id->remap[id]).toArray();
        return new SurfaceMesh(p,n,indices,attributes.stream().mapToInt(Integer::intValue).toArray(),locked);
    }
    private static int vertex(Point point,Map<Point,Integer> ids,List<Vertex> vertices) {
        Integer old=ids.get(point);if(old!=null)return old;
        if(vertices.size()>=MAX_VERTICES)throw new IllegalStateException("Direct surface vertex budget exceeded");
        int id=vertices.size();ids.put(point,id);vertices.add(new Vertex(point));return id;
    }
    private static int axis(Point a,Point b){if(a.x!=b.x)return 0;if(a.y!=b.y)return 1;if(a.z!=b.z)return 2;throw new IllegalStateException("Zero rectangle edge");}
    private static Line line(Point p,int axis){return new Line(axis,axis==0?p.y:p.x,axis==2?p.y:p.z);}
    private static long turn(Point a,Point b,Point c,int face) {
        long x=b.x-a.x,y=b.y-a.y,z=b.z-a.z,u=c.x-a.x,v=c.y-a.y,w=c.z-a.z;
        return (y*w-z*v)*N[face][0]+(z*u-x*w)*N[face][1]+(x*v-y*u)*N[face][2];
    }
    private static void triangulate(ArrayList<Integer> polygon,List<Vertex> vertices,int face,int attr,List<Integer> indices,List<Integer> attrs) {
        while(polygon.size()>3) {
            boolean removed=false;
            for(int i=0;i<polygon.size();i++) {
                int ia=polygon.get((i+polygon.size()-1)%polygon.size()),ib=polygon.get(i),ic=polygon.get((i+1)%polygon.size());
                Point a=vertices.get(ia).p,b=vertices.get(ib).p,c=vertices.get(ic).p;
                if(turn(a,b,c,face)<=0)continue;
                boolean blocked=false;
                for(int id:polygon)if(id!=ia&&id!=ib&&id!=ic) {
                    Point p=vertices.get(id).p;
                    if(turn(a,b,p,face)>=0&&turn(b,c,p,face)>=0&&turn(c,a,p,face)>=0){blocked=true;break;}
                }
                if(blocked)continue;
                emit(ia,ib,ic,attr,indices,attrs);polygon.remove(i);removed=true;break;
            }
            if(!removed)throw new IllegalStateException("Direct surface polygon triangulation failed");
        }
        if(polygon.size()==3) {
            if(turn(vertices.get(polygon.get(0)).p,vertices.get(polygon.get(1)).p,vertices.get(polygon.get(2)).p,face)<=0)
                throw new IllegalStateException("Degenerate direct surface polygon");
            emit(polygon.get(0),polygon.get(1),polygon.get(2),attr,indices,attrs);
        }
    }
    private static void emit(int a,int b,int c,int attr,List<Integer> indices,List<Integer> attrs) {
        if(attrs.size()>=MAX_TRIANGLES)throw new IllegalStateException("Direct surface triangle budget exceeded");
        indices.add(a);indices.add(b);indices.add(c);attrs.add(attr);
    }
    private ConformingSurface(){}
}
