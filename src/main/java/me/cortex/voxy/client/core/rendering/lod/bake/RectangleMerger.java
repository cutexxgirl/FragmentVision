package me.cortex.voxy.client.core.rendering.lod.bake;

/** Offline 32x32 candidate, independent of the unchanged shipping scanner.
 * GreedyQuadMesher checks its rectangle count against the shipping result per domain.
 */
public final class RectangleMerger {
    @FunctionalInterface public interface Emitter { void quad(int x,int y,int width,int height,long key); }
    public static void scan(long[] input,Emitter out) {
        if(input.length!=1024)throw new IllegalArgumentException("Expected 32x32 plane");
        long[] remaining=input.clone();
        for(int y=0;y<32;y++)for(int x=0;x<32;x++) {
            long key=remaining[y*32+x];if(key==0)continue;
            int width=1,height=1;
            while(x+width<32&&remaining[y*32+x+width]==key)width++;
            boolean extend=true;
            while(y+height<32&&extend) {
                for(int u=0;u<width;u++)if(remaining[(y+height)*32+x+u]!=key){extend=false;break;}
                if(extend)height++;
            }
            for(int v=0;v<height;v++)java.util.Arrays.fill(remaining,(y+v)*32+x,(y+v)*32+x+width,0);
            out.quad(x,y,width,height,key);
        }
    }
    private RectangleMerger(){}
}
