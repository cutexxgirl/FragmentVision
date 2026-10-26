package me.cortex.voxy.client.core.rendering.lod.bake;

import me.cortex.voxy.client.core.util.ScanMesher2D;
import java.util.*;

/** Same visible unit faces/material/light for both paths. Comparison domains align to world 32-cells.
 * The unchanged shipping scanner (16 limit) is the baseline. A separate 32-limit candidate is
 * accepted per scan domain only if it emits no more rectangles than that baseline.
 */
public final class GreedyQuadMesher {
    @FunctionalInterface public interface FaceSelector {boolean accepts(int x,int y,int z,int face);}
    public record Result(PackedQuads selected,PackedQuads baseline,long visibleUnitFaces) {}
    private static final int[][] N={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private static final int MAX_QUADS=262_144;
    private static final class Collector extends ScanMesher2D {
        final ArrayList<Long> out=new ArrayList<>();
        final int face,depth,uBase,vBase;
        Collector(int face,int depth,int uBase,int vBase){this.face=face;this.depth=depth;this.uBase=uBase;this.vBase=vBase;}
        @Override protected void emitQuad(int endU,int endV,int width,int height,long key) {
            int u=uBase+endU-width+1,v=vBase+endV-height+1;
            int x=face<2?depth:u,y=face<2?v:face<4?depth:v,z=face<2?u:face<4?v:depth;
            long quad=PackedQuads.encode(x,y,z,width,height,face,(int)((key>>>8)&65535),(int)(key&255));
            out.add((key&(1L<<37))!=0?PackedQuads.shape(quad,(int)((key>>>24)&7)):quad);
        }
    }
    public static Result build(VoxelSnapshot s,int minX,int minZ,int width) {return build(s,minX,minZ,width,id->true);}
    public static Result build(VoxelSnapshot s,int minX,int minZ,int width,java.util.function.IntPredicate owned) {return build(s,minX,minZ,width,owned,(x,y,z,face)->true);}
    public static Result build(VoxelSnapshot s,int minX,int minZ,int width,java.util.function.IntPredicate owned,FaceSelector selector) {return build(s,minX,minZ,width,owned,selector,false);}
    public static Result build(VoxelSnapshot s,int minX,int minZ,int width,java.util.function.IntPredicate owned,FaceSelector selector,boolean shapes) {
        int[] heights=new int[s.states().length];boolean[] snow=new boolean[heights.length];for(int i=1;i<heights.length;i++){heights[i]=shapes?TerrainShapes.height(s.states()[i]):8;snow[i]=shapes&&TerrainShapes.snow(s.states()[i]);}
        if(width<1||width>64||minX<0||minZ<0||minX+width>s.sizeX()||minZ+width>s.sizeZ())throw new IllegalArgumentException("Invalid quad tile");
        var chosen=new ArrayList<Long>();var baseline=new ArrayList<Long>();long faces=0;
        for(int f=0;f<6;f++) {
            int depths=f<2?width:f<4?s.sizeY():width,vLength=f<2?s.sizeY():f<4?width:s.sizeY();
            int uWorld=f<2?s.originZ()+minZ:s.originX()+minX;
            int vWorld=f<2?s.originY():f<4?s.originZ()+minZ:s.originY();
            int uStart=-Math.floorMod(uWorld,32),vStart=-Math.floorMod(vWorld,32);
            for(int d=0;d<depths;d++)for(int vb=vStart;vb<vLength;vb+=32)for(int ub=uStart;ub<width;ub+=32) {
                var a=new Collector(f,d,ub,vb);var b=new Collector(f,d,ub,vb);long[] plane=new long[1024];
                for(int v=0;v<32;v++)for(int u=0;u<32;u++) {
                    int uu=ub+u,vv=vb+v;long key=0;
                    if(uu>=0&&uu<width&&vv>=0&&vv<vLength) {
                        int x=minX+(f<2?d:uu),y=f<2?vv:f<4?d:vv,z=minZ+(f<2?uu:f<4?vv:d);
                        int material=s.block(x,y,z);
                        if(material>0&&owned.test(material)&&selector.accepts(x,y,z,f)) {
                            int nx=x+N[f][0],ny=y+N[f][1],nz=z+N[f][2],neighbour=s.block(nx,ny,nz),low=0;
                            boolean visible=neighbour==0;
                            if(shapes&&neighbour>=0){if(f==3)visible=heights[material]<8||neighbour==0;else if(f==2)visible=heights[neighbour]<8;else{visible=heights[material]>heights[neighbour];low=heights[neighbour];}}
                            if(visible){int light=ny>=0&&ny<s.sizeY()?s.light()[s.index(nx,ny,nz)]&255:0;
                                key=((long)material<<8)|light;
                                if(shapes&&(snow[material]||low>0)){key|=((long)low<<24)|(1L<<37);if(f<2||f>3)key|=((long)(y+1)<<27);}
                                faces++;
                            }
                        }
                    }
                    a.putNext(key);plane[v*32+u]=key;
                }
                a.finish();RectangleMerger.scan(plane,(u,v,w,h,key)->b.emitQuad(u+w-1,v+h-1,w,h,key));
                baseline.addAll(a.out);chosen.addAll(b.out.size()<=a.out.size()?b.out:a.out);
                if(baseline.size()>MAX_QUADS||chosen.size()>MAX_QUADS)throw new IllegalStateException("Quad tile budget exceeded");
            }
        }
        return new Result(new PackedQuads(chosen.stream().mapToLong(Long::longValue).toArray()),new PackedQuads(baseline.stream().mapToLong(Long::longValue).toArray()),faces);
    }
    private GreedyQuadMesher() {}
}
