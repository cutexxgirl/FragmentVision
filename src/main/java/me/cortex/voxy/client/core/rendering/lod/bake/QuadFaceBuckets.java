package me.cortex.voxy.client.core.rendering.lod.bake;

import java.nio.IntBuffer;
import java.util.Arrays;

/** Directional submission for exact axis-aligned quads only. Curved meshes must not
 * use their original material face as a geometric facing. Unclassified/two-sided
 * materials always enter bucket 6. No dependency on Sodium implementation code.
 */
public final class QuadFaceBuckets {
    public static final int BUCKETS=7, COMMAND_INTS=5, COMMAND_BYTES=BUCKETS*COMMAND_INTS*4;
    public record Partition(PackedQuads quads, Layout layout) {}
    public record Layout(int[] starts,int[] counts,double[] planes) {
        /** All camera coordinates are relative to the page origin. Keep coplanar
         * faces with an epsilon to avoid camera rounding flicker. */
        public boolean visible(int bucket,double x,double y,double z) {
            if(bucket==6)return true;
            double camera=bucket<2?x:bucket<4?y:z;
            return (bucket&1)==0?camera<=planes[bucket]+.01:camera>=planes[bucket]-.01;
        }
        public long commands(IntBuffer target,double x,double y,double z) {
            long submitted=0;
            for(int bucket=0;bucket<BUCKETS;bucket++) {
                int count=visible(bucket,x,y,z)?counts[bucket]:0;
                target.put(6).put(count).put(0).put(0).put(starts[bucket]);
                submitted+=count*2L;
            }
            return submitted;
        }
    }
    public static Partition partition(PackedQuads source,boolean[] singleSided) {
        int[] counts=new int[BUCKETS],starts=new int[BUCKETS];double[] planes=new double[6];
        for(int f=0;f<6;f++)planes[f]=(f&1)==0?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY;
        for(long q:source.data()) {
            int material=PackedQuads.material(q);
            if(material>=singleSided.length)throw new IllegalArgumentException("Quad material out of range");
            int f=singleSided[material]?PackedQuads.face(q):6;
            counts[f]++;
            if(f<6) {
                double plane=(f<2?PackedQuads.x(q):f<4?PackedQuads.y(q):PackedQuads.z(q))+(f&1);
                planes[f]=(f&1)==0?Math.max(planes[f],plane):Math.min(planes[f],plane);
            }
        }
        for(int f=1;f<BUCKETS;f++)starts[f]=starts[f-1]+counts[f-1];
        int[] cursor=Arrays.copyOf(starts,BUCKETS);long[] ordered=new long[source.data().length];
        for(long q:source.data())ordered[cursor[singleSided[PackedQuads.material(q)]?PackedQuads.face(q):6]++]=q;
        return new Partition(new PackedQuads(ordered),new Layout(starts,counts,planes));
    }
    private QuadFaceBuckets() {}
}
