package me.cortex.voxy.client.core.rendering.lod.bake;

import java.util.*;

/** Bidirectional geometric distance to the original surface, independent of QEM cost.
 * Adaptive subdivision bounds each source triangle using the 1-Lipschitz distance
 * function: max(vertex distances) + longestEdge/sqrt(3) covers the triangle.
 * Unresolved/budget-limited checks reject the candidate; a few passing samples do not accept it.
 */
public final class SurfaceError {
    public record Result(boolean withinLimit,double upperBound,double sampledMax,int queries,String reason) {}
    private record Point(double x,double y,double z) {
        Point midpoint(Point b){return new Point((x+b.x)*.5,(y+b.y)*.5,(z+b.z)*.5);}
    }
    private static final int MAX_QUERIES=300000,MAX_DEPTH=12;
    private static final double GUARD=1e-6;
    private static final class Node {
        double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
        double[] hi={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        Node left,right;int first,last;
    }
    private static final class Tree {
        final SurfaceMesh mesh;final Integer[] triangles;final Node root;
        Tree(SurfaceMesh mesh){this.mesh=mesh;triangles=new Integer[mesh.triangles()];for(int i=0;i<triangles.length;i++)triangles[i]=i;root=build(0,triangles.length);}
        Point vertex(int triangle,int k){int i=mesh.indices()[triangle*3+k]*3;return new Point(mesh.positions()[i],mesh.positions()[i+1],mesh.positions()[i+2]);}
        Node build(int first,int last) {
            Node n=new Node();n.first=first;n.last=last;
            for(int t=first;t<last;t++)for(int k=0;k<3;k++){Point p=vertex(triangles[t],k);double[] a={p.x,p.y,p.z};for(int c=0;c<3;c++){n.lo[c]=Math.min(n.lo[c],a[c]);n.hi[c]=Math.max(n.hi[c],a[c]);}}
            if(last-first>8){int axis=0;for(int c=1;c<3;c++)if(n.hi[c]-n.lo[c]>n.hi[axis]-n.lo[axis])axis=c;final int split=axis;
                Arrays.sort(triangles,first,last,Comparator.comparingDouble(t->{double sum=0;for(int k=0;k<3;k++){Point p=vertex(t,k);sum+=split==0?p.x:split==1?p.y:p.z;}return sum;}));
                int mid=(first+last)>>>1;n.left=build(first,mid);n.right=build(mid,last);
            }return n;
        }
        double distance(Point p){return Math.sqrt(nearest(root,p,Double.POSITIVE_INFINITY));}
        double nearest(Node n,Point p,double best) {
            if(box(n,p)>=best)return best;
            if(n.left==null){for(int i=n.first;i<n.last;i++){int t=triangles[i];best=Math.min(best,triangleDistanceSquared(p,vertex(t,0),vertex(t,1),vertex(t,2)));}return best;}
            Node a=n.left,b=n.right;if(box(a,p)>box(b,p)){a=n.right;b=n.left;}
            best=nearest(a,p,best);return nearest(b,p,best);
        }
    }
    private static final class Audit {
        final Tree target;final double limit;final Map<Point,Double> cache=new HashMap<>();
        double sampledMax;String reason="within_bound";
        Audit(Tree target,double limit){this.target=target;this.limit=limit;}
        double distance(Point p) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            Double old=cache.get(p);if(old!=null)return old;
            if(cache.size()>=MAX_QUERIES){reason="error_audit_query_budget";return Double.POSITIVE_INFINITY;}
            double value=target.distance(p);cache.put(p,value);sampledMax=Math.max(sampledMax,value);return value;
        }
        double triangle(Point a,Point b,Point c,int depth) {
            double maximum=Math.max(distance(a),Math.max(distance(b),distance(c)));
            if(maximum>limit){if(reason.equals("within_bound"))reason="geometric_error_exceeded";return Double.POSITIVE_INFINITY;}
            double edge=Math.sqrt(Math.max(squared(a,b),Math.max(squared(b,c),squared(c,a))));
            double bound=maximum+edge/Math.sqrt(3)+GUARD;
            if(bound<=limit)return bound;
            if(distance(new Point((a.x+b.x+c.x)/3,(a.y+b.y+c.y)/3,(a.z+b.z+c.z)/3))>limit){if(reason.equals("within_bound"))reason="geometric_error_exceeded";return Double.POSITIVE_INFINITY;}
            if(depth>=MAX_DEPTH){reason="error_audit_depth_budget";return Double.POSITIVE_INFINITY;}
            Point ab=a.midpoint(b),bc=b.midpoint(c),ca=c.midpoint(a);double maximumBound=0;
            Point[][] children={{a,ab,ca},{ab,b,bc},{ca,bc,c},{ab,bc,ca}};
            for(Point[] triangle:children){double next=triangle(triangle[0],triangle[1],triangle[2],depth+1);if(!Double.isFinite(next))return next;maximumBound=Math.max(maximumBound,next);}
            return maximumBound;
        }
    }
    public static Result compare(SurfaceMesh original,SurfaceMesh candidate,double limit) {
        if(!Double.isFinite(limit)||limit<=0)throw new IllegalArgumentException("Positive geometric error limit required");
        if(original.triangles()==0||candidate.triangles()==0) {
            boolean bothEmpty=original.triangles()==candidate.triangles();
            return new Result(bothEmpty,bothEmpty?0:Double.NaN,0,0,bothEmpty?"empty_surface":"empty_surface_mismatch");
        }
        Tree reference=new Tree(original),reduced=new Tree(candidate);double maximumBound=0,sampled=0;int queries=0;
        for(int direction=0;direction<2;direction++) {
            Tree source=direction==0?reference:reduced,target=direction==0?reduced:reference;Audit audit=new Audit(target,limit);
            for(int t=0;t<source.mesh.triangles();t++) {
                double bound=audit.triangle(source.vertex(t,0),source.vertex(t,1),source.vertex(t,2),0);
                if(!Double.isFinite(bound))return new Result(false,Double.NaN,Math.max(sampled,audit.sampledMax),queries+audit.cache.size(),audit.reason);
                maximumBound=Math.max(maximumBound,bound);
            }
            sampled=Math.max(sampled,audit.sampledMax);queries+=audit.cache.size();
        }
        return new Result(true,maximumBound,sampled,queries,"within_bidirectional_bound");
    }
    private static double box(Node n,Point p){double result=0;double[] values={p.x,p.y,p.z};for(int c=0;c<3;c++){double d=Math.max(0,Math.max(n.lo[c]-values[c],values[c]-n.hi[c]));result+=d*d;}return result;}
    private static Point subtract(Point a,Point b){return new Point(a.x-b.x,a.y-b.y,a.z-b.z);}
    private static double dot(Point a,Point b){return a.x*b.x+a.y*b.y+a.z*b.z;}
    private static double squared(Point a,Point b){Point d=subtract(a,b);return dot(d,d);}
    private static double edgeDistance(Point p,Point a,Point b){Point d=subtract(b,a);double denominator=dot(d,d);double t=denominator>0?Math.clamp(dot(subtract(p,a),d)/denominator,0.,1.):0;return squared(p,new Point(a.x+d.x*t,a.y+d.y*t,a.z+d.z*t));}
    private static double triangleDistanceSquared(Point p,Point a,Point b,Point c) {
        Point ab=subtract(b,a),ac=subtract(c,a),ap=subtract(p,a);double d1=dot(ab,ap),d2=dot(ac,ap);
        if(d1<=0&&d2<=0)return dot(ap,ap);
        Point bp=subtract(p,b);double d3=dot(ab,bp),d4=dot(ac,bp);if(d3>=0&&d4<=d3)return dot(bp,bp);
        double vc=d1*d4-d3*d2;if(vc<=0&&d1>=0&&d3<=0){double v=d1/(d1-d3);return squared(p,new Point(a.x+v*ab.x,a.y+v*ab.y,a.z+v*ab.z));}
        Point cp=subtract(p,c);double d5=dot(ab,cp),d6=dot(ac,cp);if(d6>=0&&d5<=d6)return dot(cp,cp);
        double vb=d5*d2-d1*d6;if(vb<=0&&d2>=0&&d6<=0){double w=d2/(d2-d6);return squared(p,new Point(a.x+w*ac.x,a.y+w*ac.y,a.z+w*ac.z));}
        double va=d3*d6-d5*d4;if(va<=0&&d4-d3>=0&&d5-d6>=0){double w=(d4-d3)/(d4-d3+d5-d6);return squared(p,new Point(b.x+w*(c.x-b.x),b.y+w*(c.y-b.y),b.z+w*(c.z-b.z)));}
        double denominator=va+vb+vc;if(Math.abs(denominator)<1e-20)return Math.min(edgeDistance(p,a,b),Math.min(edgeDistance(p,b,c),edgeDistance(p,c,a)));
        double v=vb/denominator,w=vc/denominator;return squared(p,new Point(a.x+ab.x*v+ac.x*w,a.y+ab.y*v+ac.y*w,a.z+ab.z*v+ac.z*w));
    }
    private SurfaceError(){}
}
