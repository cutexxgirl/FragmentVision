package me.cortex.voxy.client.core.rendering.lod.bake;

import java.util.*;

/** CPU reference for the planned native-to-parent collar; not connected to rendering.
 * Cells describe ready exterior columns, NOT a camera-distance approximation.
 * Collinear exposed edges merge before evaluation, so internal chunk lines cannot
 * change the field. A product gives a differentiable concave-corner blend instead
 * of the diagonal crease of min(edgeWeights). Production needs spatial bins.
 */
public final class NativeTransitionField {
    public record Cell(int x,int z) {}
    private record Line(boolean vertical,long coordinate) implements Comparable<Line> {
        public int compareTo(Line b){int c=Boolean.compare(vertical,b.vertical);return c!=0?c:Long.compare(coordinate,b.coordinate);}
    }
    private record Interval(long start,long end) {}
    private record Edge(boolean vertical,long coordinate,long start,long end) {
        double distance(double x,double z){
            double along=vertical?z:x,across=(vertical?x:z)-coordinate;
            double outside=Math.max(0,Math.max(start-along,along-end));
            return Math.hypot(across,outside);
        }
    }
    private final Set<Cell> nativeCells;
    private final List<Edge> edges;
    private final double exactWidth,blendWidth;
    public NativeTransitionField(Set<Cell> cells,double exactWidth,double blendWidth){
        if(!Double.isFinite(exactWidth)||exactWidth<0||!Double.isFinite(blendWidth)||blendWidth<=0)throw new IllegalArgumentException("Transition widths");
        this.nativeCells=Set.copyOf(cells);this.exactWidth=exactWidth;this.blendWidth=blendWidth;
        var lines=new TreeMap<Line,List<Interval>>();
        for(Cell c:cells){
            long x=c.x*16L,z=c.z*16L;
            if(!contains((long)c.x-1,c.z))add(lines,new Line(true,x),z,z+16);
            if(!contains((long)c.x+1,c.z))add(lines,new Line(true,x+16),z,z+16);
            if(!contains(c.x,(long)c.z-1))add(lines,new Line(false,z),x,x+16);
            if(!contains(c.x,(long)c.z+1))add(lines,new Line(false,z+16),x,x+16);
        }
        var result=new ArrayList<Edge>();
        for(var entry:lines.entrySet()){
            var intervals=entry.getValue();intervals.sort(Comparator.comparingLong(Interval::start));
            long start=intervals.getFirst().start,end=intervals.getFirst().end;
            for(int i=1;i<intervals.size();i++){var interval=intervals.get(i);if(interval.start<=end)end=Math.max(end,interval.end);else{result.add(new Edge(entry.getKey().vertical,entry.getKey().coordinate,start,end));start=interval.start;end=interval.end;}}
            result.add(new Edge(entry.getKey().vertical,entry.getKey().coordinate,start,end));
        }
        edges=List.copyOf(result);
    }
    private boolean contains(long x,long z){return x>=Integer.MIN_VALUE&&x<=Integer.MAX_VALUE&&z>=Integer.MIN_VALUE&&z<=Integer.MAX_VALUE&&nativeCells.contains(new Cell((int)x,(int)z));}
    private static void add(Map<Line,List<Interval>> lines,Line line,long start,long end){lines.computeIfAbsent(line,k->new ArrayList<>()).add(new Interval(start,end));}
    public int boundarySegments(){return edges.size();}
    /** 0 = exact/native, 1 = parent. Both coordinates are absolute world positions. */
    public double weight(double x,double z){
        if(!Double.isFinite(x)||!Double.isFinite(z)||Math.abs(x)>Integer.MAX_VALUE||Math.abs(z)>Integer.MAX_VALUE)throw new IllegalArgumentException("World coordinate");
        if(contains((long)Math.floor(x/16),(long)Math.floor(z/16)))return 0;
        double result=1;
        for(Edge edge:edges){
            double d=edge.distance(x,z);if(d<=exactWidth)return 0;
            double t=(d-exactWidth)/blendWidth;if(t>=1)continue;
            result*=t*t*t*(t*(t*6-15)+10);
        }
        return result;
    }
}
