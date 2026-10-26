package me.cortex.voxy.client.core.rendering.lod.bake;

import java.util.*;

/** Offline edge-collapse proof. QEM cost is an approximation, NOT a Hausdorff/errorPixels guarantee.
 * Discrete material/biome seams, author protection and shared tile contours stay locked.
 * Link-condition and local normal checks reject topology changes and foldovers.
 */
public final class QuadricSimplifier {
    public record Result(SurfaceMesh mesh,int collapses,int rejected,int peakQueue,double maxAcceptedCost) {}
    private static final int QUEUE_LIMIT=200_000;
    private static final class Vertex {
        double x,y,z,nx,ny,nz;
        final double[] q=new double[10];
        final Set<Integer> faces=new HashSet<>();
        boolean locked,alive=true;
        int version;
    }
    private static final class Face {
        final int[] v;
        final int data;
        boolean alive=true;
        Face(int a,int b,int c,int data) { v=new int[]{a,b,c};this.data=data; }
    }
    private record Edge(int a,int b,int av,int bv,double x,double y,double z,double cost) {}

    public static Result simplify(SurfaceMesh source,double ratio,double maxCostDistance) {
        if(!Double.isFinite(ratio)||ratio<=0||ratio>1||!Double.isFinite(maxCostDistance)||maxCostDistance<0)
            throw new IllegalArgumentException("Invalid simplification settings");
        return new QuadricSimplifier(source,false).run(ratio,maxCostDistance);
    }

    /** Direct merged surfaces also reject geometric vertex/edge contacts introduced by collapse. */
    public static Result simplifyDirect(SurfaceMesh source,double ratio,double maxCostDistance) {
        if(!Double.isFinite(ratio)||ratio<=0||ratio>1||!Double.isFinite(maxCostDistance)||maxCostDistance<0)
            throw new IllegalArgumentException("Invalid simplification settings");
        return new QuadricSimplifier(source,true).run(ratio,maxCostDistance);
    }
    /** Input must come from ConformingSurface: its locks already encode visual material domains.
     * In particular leaf simulation properties may differ without introducing a geometric seam. */
    public static Result simplifyExtracted(SurfaceMesh source,double ratio,double maxCostDistance){
        if(!Double.isFinite(ratio)||ratio<=0||ratio>1||!Double.isFinite(maxCostDistance)||maxCostDistance<0)
            throw new IllegalArgumentException("Invalid simplification settings");
        return new QuadricSimplifier(source,true,true).run(ratio,maxCostDistance);
    }
    public static Result simplifyCompactTerrain(SurfaceMesh source,double ratio,double maxCostDistance){
        if(!Double.isFinite(ratio)||ratio<=0||ratio>1||!Double.isFinite(maxCostDistance)||maxCostDistance<0)throw new IllegalArgumentException("Invalid terrain settings");
        return new QuadricSimplifier(source,true,true,16).run(ratio,maxCostDistance);
    }
    /** Reuse one collapse search for all ascending cost cuts. Capturing a compact
     * snapshot does not mutate the search, so every cut matches an independent run. */
    public static List<SurfaceMesh> sampleCompactTerrain(SurfaceMesh source,double ratio,double[] costs){
        if(costs.length==0||!Double.isFinite(ratio)||ratio<=0||ratio>1)throw new IllegalArgumentException("Sampling settings");
        for(int i=0;i<costs.length;i++)if(!Double.isFinite(costs[i])||costs[i]<0||(i>0&&costs[i]<=costs[i-1]))throw new IllegalArgumentException("Ascending costs required");
        var reducer=new QuadricSimplifier(source,true,true,16);reducer.sampleCosts=costs.clone();reducer.sampled=new ArrayList<>();
        var result=reducer.run(ratio,costs[costs.length-1]);
        while(reducer.nextSample<costs.length){reducer.sampled.add(result.mesh);reducer.nextSample++;}
        return List.copyOf(reducer.sampled);
    }
    public static ProgressiveMesh progressive(SurfaceMesh source,double ratio,double maxCostDistance) {
        if(!Double.isFinite(ratio)||ratio<=0||ratio>1||!Double.isFinite(maxCostDistance)||maxCostDistance<0)
            throw new IllegalArgumentException("Invalid simplification settings");
        var simplifier=new QuadricSimplifier(source,true);
        simplifier.history=new ArrayList<>();
        simplifier.run(ratio,maxCostDistance);
        return new ProgressiveMesh(source,simplifier.history);
    }
    private List<ProgressiveMesh.Step> history;
    private List<SurfaceMesh> sampled;private double[] sampleCosts;private int nextSample;
    private record TriangleKey(int a,int b,int c){}
    private final boolean guardContacts;
    private final int precision;
    private double minX=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY,minZ=Double.POSITIVE_INFINITY,maxZ=Double.NEGATIVE_INFINITY;
    private static final long CONTACT_WORK_LIMIT=100_000_000L;
    private long contactWork;
    private SpatialBins pointBins,faceBins;
    /** Conservative broad phase. Membership is removed before a collapse and rebuilt after it.
     * Giant AABBs live in the overflow set, so a long edge can never evade a query. */
    private static final class SpatialBins {
        private record Cell(int x,int y,int z) {}
        private final Map<Cell,Set<Integer>> cells=new HashMap<>();
        private final Map<Integer,List<Cell>> membership=new HashMap<>();
        private final Set<Integer> overflow=new HashSet<>();
        private static int cell(double v){return (int)Math.floor(v/4.);}
        void remove(int id){var old=membership.remove(id);if(old!=null)for(Cell key:old){var bin=cells.get(key);bin.remove(id);if(bin.isEmpty())cells.remove(key);}overflow.remove(id);}
        void put(int id,double ax,double ay,double az,double bx,double by,double bz){
            remove(id);int x0=cell(Math.min(ax,bx)-1e-8),y0=cell(Math.min(ay,by)-1e-8),z0=cell(Math.min(az,bz)-1e-8);
            int x1=cell(Math.max(ax,bx)+1e-8),y1=cell(Math.max(ay,by)+1e-8),z1=cell(Math.max(az,bz)+1e-8);
            if((long)(x1-x0+1)*(y1-y0+1)*(z1-z0+1)>4096){overflow.add(id);return;}
            var keys=new ArrayList<Cell>();for(int z=z0;z<=z1;z++)for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++){
                var key=new Cell(x,y,z);keys.add(key);cells.computeIfAbsent(key,k->new HashSet<>()).add(id);
            }membership.put(id,keys);
        }
        Set<Integer> query(double ax,double ay,double az,double bx,double by,double bz){
            int x0=cell(Math.min(ax,bx)-1e-8),y0=cell(Math.min(ay,by)-1e-8),z0=cell(Math.min(az,bz)-1e-8);
            int x1=cell(Math.max(ax,bx)+1e-8),y1=cell(Math.max(ay,by)+1e-8),z1=cell(Math.max(az,bz)+1e-8);
            var found=new TreeSet<Integer>(overflow);
            if((long)(x1-x0+1)*(y1-y0+1)*(z1-z0+1)>4096){found.addAll(membership.keySet());return found;}
            for(int z=z0;z<=z1;z++)for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++){var bin=cells.get(new Cell(x,y,z));if(bin!=null)found.addAll(bin);}return found;
        }
    }
    private void indexFace(int id){
        Face f=faces[id];if(!f.alive){faceBins.remove(id);return;}
        Vertex a=vertices[f.v[0]],b=vertices[f.v[1]],c=vertices[f.v[2]];
        faceBins.put(id,Math.min(a.x,Math.min(b.x,c.x)),Math.min(a.y,Math.min(b.y,c.y)),Math.min(a.z,Math.min(b.z,c.z)),
                Math.max(a.x,Math.max(b.x,c.x)),Math.max(a.y,Math.max(b.y,c.y)),Math.max(a.z,Math.max(b.z,c.z)));
    }
    private final Vertex[] vertices;
    private final Face[] faces;
    private int triangleCount;
    private QuadricSimplifier(SurfaceMesh source,boolean guardContacts){this(source,guardContacts,false);}
    private QuadricSimplifier(SurfaceMesh source,boolean guardContacts,boolean domainsPrelocked){this(source,guardContacts,domainsPrelocked,256);}
    private QuadricSimplifier(SurfaceMesh source,boolean guardContacts,boolean domainsPrelocked,int precision) {
        this.precision=precision;
        this.guardContacts=guardContacts;
        vertices=new Vertex[source.vertices()];faces=new Face[source.triangles()];triangleCount=faces.length;
        for(int i=0;i<vertices.length;i++) {
            Vertex v=new Vertex();v.x=source.positions()[i*3];v.y=source.positions()[i*3+1];v.z=source.positions()[i*3+2];
            v.nx=source.normals()[i*3];v.ny=source.normals()[i*3+1];v.nz=source.normals()[i*3+2];v.locked=source.locked()[i];vertices[i]=v;
            minX=Math.min(minX,v.x);maxX=Math.max(maxX,v.x);minZ=Math.min(minZ,v.z);maxZ=Math.max(maxZ,v.z);
        }
        for(int i=0;i<faces.length;i++) {
            Face f=new Face(source.indices()[i*3],source.indices()[i*3+1],source.indices()[i*3+2],source.faceData()[i]);faces[i]=f;
            double[] n=normal(f,-1,0,0,0);double length=length(n);
            if(length<1e-10) throw new IllegalArgumentException("Degenerate source triangle");
            double a=n[0]/length,b=n[1]/length,c=n[2]/length;
            Vertex p=vertices[f.v[0]];double d=-(a*p.x+b*p.y+c*p.z);
            double[] q={a*a,a*b,a*c,a*d,b*b,b*c,b*d,c*c,c*d,d*d};
            for(int id:f.v) { Vertex v=vertices[id];v.faces.add(i);for(int k=0;k<10;k++)v.q[k]+=q[k]; }
        }
        if(!domainsPrelocked)for(Vertex v:vertices) {
            int material=-1;
            for(int id:v.faces) {
                int next=faces[id].data>>>11;
                if(material>=0&&material!=next)v.locked=true;
                material=next;
            }
        }
    }

    private Result run(double ratio,double error) {
        if(guardContacts){pointBins=new SpatialBins();faceBins=new SpatialBins();
            for(int i=0;i<vertices.length;i++){Vertex v=vertices[i];pointBins.put(i,v.x,v.y,v.z,v.x,v.y,v.z);}
            for(int i=0;i<faces.length;i++)indexFace(i);
        }
        var queue=new PriorityQueue<Edge>(Comparator.comparingDouble(Edge::cost).thenComparingInt(Edge::a).thenComparingInt(Edge::b));
        refill(queue);
        int target=(int)Math.ceil(faces.length*ratio),collapses=0,rejected=0,peak=queue.size();double maxCost=0;
        while(triangleCount>target&&!queue.isEmpty()) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            Edge edge=queue.poll();Vertex a=vertices[edge.a],b=vertices[edge.b];
            if(!a.alive||!b.alive||a.version!=edge.av||b.version!=edge.bv)continue;
            if(sampled!=null)while(nextSample<sampleCosts.length&&edge.cost>sampleCosts[nextSample]*sampleCosts[nextSample]){sampled.add(compact());nextSample++;}
            if(edge.cost>error*error)break;
            if(!valid(edge)) { rejected++;if(guardContacts&&contactWork>CONTACT_WORK_LIMIT)break;continue; }
            collapse(edge);collapses++;maxCost=Math.max(maxCost,edge.cost);
            var affected=new TreeSet<Integer>();affected.add(edge.a);
            for(int id:a.faces)for(int v:faces[id].v)affected.add(v);
            for(int v:affected)for(int n:neighbours(v))if(v<n)add(queue,v,n);
            // Include edges where only the higher-ID endpoint is affected.
            for(int v:affected)for(int n:neighbours(v))if(n<v&&!affected.contains(n))add(queue,n,v);
            peak=Math.max(peak,queue.size());
            if(queue.size()>QUEUE_LIMIT)refill(queue);
        }
        return new Result(compact(),collapses,rejected,peak,maxCost);
    }

    private void refill(PriorityQueue<Edge> queue) {
        queue.clear();var edges=new HashSet<Long>();
        for(Face f:faces)if(f.alive)for(int k=0;k<3;k++) {
            int a=Math.min(f.v[k],f.v[(k+1)%3]),b=Math.max(f.v[k],f.v[(k+1)%3]);
            long key=((long)a<<32)|b;
            if(edges.add(key))add(queue,a,b);
        }
    }

    private Set<Integer> neighbours(int id) {
        var result=new TreeSet<Integer>();
        for(int face:vertices[id].faces)for(int v:faces[face].v)if(v!=id)result.add(v);
        return result;
    }

    private void add(PriorityQueue<Edge> queue,int ia,int ib) {
        Vertex a=vertices[ia],b=vertices[ib];
        if(!a.alive||!b.alive||(a.locked&&b.locked))return;
        double[] q=new double[10];for(int k=0;k<10;k++)q[k]=a.q[k]+b.q[k];
        double[][] choices=a.locked?new double[][]{{a.x,a.y,a.z}}:b.locked?new double[][]{{b.x,b.y,b.z}}:
                new double[][]{{(a.x+b.x)*.5,(a.y+b.y)*.5,(a.z+b.z)*.5},{a.x,a.y,a.z},{b.x,b.y,b.z},optimal(q)};
        Edge best=null;
        for(double[] p:choices)if(p!=null) {
            // Evaluate the *published* quantized point, so quantization cannot silently introduce a foldover.
            double x=quant(p[0]),y=quant(p[1]),z=quant(p[2]);
            if(precision==16&&(x<minX||x>maxX||z<minZ||z>maxZ))continue;
            double cost=Math.max(0,cost(q,x,y,z));
            if(!Double.isFinite(cost))continue;
            if(best==null||cost<best.cost)best=new Edge(ia,ib,a.version,b.version,x,y,z,cost);
        }
        if(best!=null)queue.add(best);
    }

    private boolean valid(Edge e) {
        Vertex a=vertices[e.a],b=vertices[e.b];
        Set<Integer> common=neighbours(e.a);common.retainAll(neighbours(e.b));
        var opposite=new HashSet<Integer>();int shared=0;
        for(int id:a.faces)if(b.faces.contains(id)) {
            shared++;for(int v:faces[id].v)if(v!=e.a&&v!=e.b)opposite.add(v);
        }
        // The manifold link condition. A protected/non-manifold junction is left untouched.
        if(shared<1||shared>2||!common.equals(opposite))return false;
        var affected=new HashSet<Integer>(a.faces);affected.addAll(b.faces);
        for(int id:affected) {
            Face f=faces[id];boolean hasA=false,hasB=false;
            for(int v:f.v){hasA|=v==e.a;hasB|=v==e.b;}
            if(hasA&&hasB)continue;
            double[] before=normal(f,-1,0,0,0),after=normal(f,hasA?e.a:e.b,e.x,e.y,e.z);
            double bl=length(before),al=length(after);
            if(al<1e-8||bl<1e-8||(before[0]*after[0]+before[1]*after[1]+before[2]*after[2])<.25*bl*al)return false;
        }
        // The vertex-only link test is insufficient for tetrahedral components:
        // reject collapses that create duplicate triangles and erase a closed component.
        var remaining=new HashSet<TriangleKey>();
        for(int id:affected){Face f=faces[id];int x=f.v[0]==e.b?e.a:f.v[0],y=f.v[1]==e.b?e.a:f.v[1],z=f.v[2]==e.b?e.a:f.v[2];
            if(x==y||y==z||z==x)continue;int min=Math.min(x,Math.min(y,z)),max=Math.max(x,Math.max(y,z));
            if(!remaining.add(new TriangleKey(min,x+y+z-min-max,max)))return false;
        }
        if(history!=null)for(int id:affected) {
            Face f=faces[id];
            if(!a.faces.contains(id)||!b.faces.contains(id))continue;
            // Shared triangles intentionally disappear at t=1. Their oriented area is
            // quadratic; divide its known (1-t) factor and check both ends exactly.
            double[] before=normal(f,-1,0,0,0);
            double[] mid=morphNormal(f,e,.5),end=morphNormal(f,e,1.);
            double d0=dot(before,before),dm=dot(before,mid),d1=dot(before,end);
            double quadratic=2*(d1+d0-2*dm);
            if(d0-quadratic<=1e-12)return false;
        }
        if(guardContacts&&!contactsValid(e))return false;
        return true;
    }

    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    private double[] morphNormal(Face f,Edge e,double t) {
        double[][] p=new double[3][3];
        for(int k=0;k<3;k++) {
            Vertex v=vertices[f.v[k]];boolean moving=f.v[k]==e.a||f.v[k]==e.b;
            p[k]=moving?new double[]{v.x+(e.x-v.x)*t,v.y+(e.y-v.y)*t,v.z+(e.z-v.z)*t}:new double[]{v.x,v.y,v.z};
        }
        double ax=p[1][0]-p[0][0],ay=p[1][1]-p[0][1],az=p[1][2]-p[0][2];
        double bx=p[2][0]-p[0][0],by=p[2][1]-p[0][1],bz=p[2][2]-p[0][2];
        return new double[]{ay*bz-az*by,az*bx-ax*bz,ax*by-ay*bx};
    }
    private boolean contactsValid(Edge e){
        boolean result=contactsIndexed(e);
        assert contactWork>CONTACT_WORK_LIMIT||result==contactsExhaustive(e):"Spatial contact index disagrees with exhaustive guard";
        return result;
    }
    /** Assertion oracle used in CPU checks, disabled in normal bakes. */
    private boolean contactsExhaustive(Edge e){
        var adjacent=neighbours(e.a);adjacent.addAll(neighbours(e.b));adjacent.remove(e.a);adjacent.remove(e.b);
        for(int other:adjacent){Vertex end=vertices[other];
            for(int i=0;i<vertices.length;i++)if(i!=e.a&&i!=e.b&&i!=other&&vertices[i].alive&&!vertices[i].faces.isEmpty()){
                Vertex p=vertices[i];if(onSegment(p.x,p.y,p.z,e.x,e.y,e.z,end.x,end.y,end.z))return false;
            }
        }
        for(Face f:faces)if(f.alive)for(int k=0;k<3;k++){
            int a=f.v[k],b=f.v[(k+1)%3];if(a==e.a||a==e.b||b==e.a||b==e.b)continue;
            Vertex va=vertices[a],vb=vertices[b];if(onSegment(e.x,e.y,e.z,va.x,va.y,va.z,vb.x,vb.y,vb.z))return false;
        }return true;
    }
    private boolean contactsIndexed(Edge e) {
        // A topologically valid collapse can still put a new edge through an unrelated
        // locked contour point, or its vertex on another edge. Reject those contacts.
        var neighbours=neighbours(e.a);neighbours.addAll(neighbours(e.b));neighbours.remove(e.a);neighbours.remove(e.b);

        for(int other:neighbours) {
            Vertex end=vertices[other];
            for(int i:pointBins.query(e.x,e.y,e.z,end.x,end.y,end.z))if(i!=e.a&&i!=e.b&&i!=other&&vertices[i].alive&&!vertices[i].faces.isEmpty()) {
                if(++contactWork>CONTACT_WORK_LIMIT)return false;
                Vertex p=vertices[i];
                if(onSegment(p.x,p.y,p.z,e.x,e.y,e.z,end.x,end.y,end.z))return false;
            }
        }
        for(int id:faceBins.query(e.x,e.y,e.z,e.x,e.y,e.z)){Face f=faces[id];if(!f.alive)continue;for(int k=0;k<3;k++) {
            if(++contactWork>CONTACT_WORK_LIMIT)return false;
            int a=f.v[k],b=f.v[(k+1)%3];
            if(a==e.a||a==e.b||b==e.a||b==e.b)continue;
            Vertex va=vertices[a],vb=vertices[b];
            if(onSegment(e.x,e.y,e.z,va.x,va.y,va.z,vb.x,vb.y,vb.z))return false;
        }
        }
        return true;
    }
    private static boolean onSegment(double px,double py,double pz,double ax,double ay,double az,double bx,double by,double bz) {
        double dx=bx-ax,dy=by-ay,dz=bz-az,ux=px-ax,uy=py-ay,uz=pz-az;
        double length=dx*dx+dy*dy+dz*dz,dot=ux*dx+uy*dy+uz*dz;
        if(dot<0||dot>length)return false;
        double cx=dy*uz-dz*uy,cy=dz*ux-dx*uz,cz=dx*uy-dy*ux;
        return cx*cx+cy*cy+cz*cz<1e-12;
    }

    private void collapse(Edge e) {
        Vertex a=vertices[e.a],b=vertices[e.b];
        var affected=new HashSet<Integer>(a.faces);affected.addAll(b.faces);
        for(int id:affected) {
            Face f=faces[id];for(int v:f.v)vertices[v].faces.remove(id);
            for(int k=0;k<3;k++)if(f.v[k]==e.b)f.v[k]=e.a;
            if(f.v[0]==f.v[1]||f.v[1]==f.v[2]||f.v[0]==f.v[2]) {f.alive=false;triangleCount--;}
            else for(int v:f.v)vertices[v].faces.add(id);
        }
        if(!a.locked&&b.locked) {a.nx=b.nx;a.ny=b.ny;a.nz=b.nz;}
        else if(!a.locked) {a.nx+=b.nx;a.ny+=b.ny;a.nz+=b.nz;}
        a.x=e.x;a.y=e.y;a.z=e.z;a.locked|=b.locked;
        for(int k=0;k<10;k++)a.q[k]+=b.q[k];
        a.version++;b.version++;b.alive=false;b.faces.clear();
        if(guardContacts){pointBins.remove(e.b);pointBins.put(e.a,a.x,a.y,a.z,a.x,a.y,a.z);for(int id:affected)indexFace(id);}
        if(history!=null) {
            double length=Math.sqrt(a.nx*a.nx+a.ny*a.ny+a.nz*a.nz);
            history.add(new ProgressiveMesh.Step(e.a,e.b,(float)a.x,(float)a.y,(float)a.z,
                    (float)(a.locked?a.nx:length>1e-12?a.nx/length:0),
                    (float)(a.locked?a.ny:length>1e-12?a.ny/length:1),
                    (float)(a.locked?a.nz:length>1e-12?a.nz/length:0),a.locked));
        }
    }

    private SurfaceMesh compact() {
        int[] remap=new int[vertices.length];Arrays.fill(remap,-1);int count=0;
        for(Face f:faces)if(f.alive)for(int v:f.v)if(remap[v]<0)remap[v]=count++;
        float[] p=new float[count*3],n=new float[count*3];boolean[] locked=new boolean[count];
        for(int i=0;i<vertices.length;i++)if(remap[i]>=0) {
            Vertex v=vertices[i];int to=remap[i];p[to*3]=(float)v.x;p[to*3+1]=(float)v.y;p[to*3+2]=(float)v.z;
            double length=Math.sqrt(v.nx*v.nx+v.ny*v.ny+v.nz*v.nz);
            n[to*3]=v.locked?(float)v.nx:length>1e-12?(float)(v.nx/length):0;
            n[to*3+1]=v.locked?(float)v.ny:length>1e-12?(float)(v.ny/length):1;
            n[to*3+2]=v.locked?(float)v.nz:length>1e-12?(float)(v.nz/length):0;
            locked[to]=v.locked;
        }
        int[] indices=new int[triangleCount*3],data=new int[triangleCount];int cursor=0;
        for(Face f:faces)if(f.alive) {for(int k=0;k<3;k++)indices[cursor*3+k]=remap[f.v[k]];data[cursor++]=f.data;}
        return new SurfaceMesh(p,n,indices,data,locked);
    }

    private double[] normal(Face f,int moved,double x,double y,double z) {
        double[][] p=new double[3][3];
        for(int k=0;k<3;k++) {Vertex v=vertices[f.v[k]];p[k]=f.v[k]==moved?new double[]{x,y,z}:new double[]{v.x,v.y,v.z};}
        double ax=p[1][0]-p[0][0],ay=p[1][1]-p[0][1],az=p[1][2]-p[0][2];
        double bx=p[2][0]-p[0][0],by=p[2][1]-p[0][1],bz=p[2][2]-p[0][2];
        return new double[]{ay*bz-az*by,az*bx-ax*bz,ax*by-ay*bx};
    }
    private static double length(double[] p) {return Math.sqrt(p[0]*p[0]+p[1]*p[1]+p[2]*p[2]);}
    private double quant(double v) {return Math.round(v*precision)/(double)precision;}
    private static double cost(double[] q,double x,double y,double z) {
        return q[0]*x*x+2*q[1]*x*y+2*q[2]*x*z+2*q[3]*x+q[4]*y*y+2*q[5]*y*z+2*q[6]*y+q[7]*z*z+2*q[8]*z+q[9];
    }
    private static double[] optimal(double[] q) {
        double[][] a={{q[0],q[1],q[2],-q[3]},{q[1],q[4],q[5],-q[6]},{q[2],q[5],q[7],-q[8]}};
        for(int k=0;k<3;k++) {
            int pivot=k;for(int i=k+1;i<3;i++)if(Math.abs(a[i][k])>Math.abs(a[pivot][k]))pivot=i;
            if(Math.abs(a[pivot][k])<1e-10)return null;
            double[] row=a[k];a[k]=a[pivot];a[pivot]=row;
            double divisor=a[k][k];for(int j=k;j<4;j++)a[k][j]/=divisor;
            for(int i=0;i<3;i++)if(i!=k){double factor=a[i][k];for(int j=k;j<4;j++)a[i][j]-=factor*a[k][j];}
        }
        return new double[]{a[0][3],a[1][3],a[2][3]};
    }
}
