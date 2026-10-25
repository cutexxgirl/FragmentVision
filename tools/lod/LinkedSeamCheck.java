import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.util.*;

/** Shared contour stays identical for independent neighbouring histories and phases.
 * This exercises the current exact-border prototype, not a coarse parent seam hierarchy.
 */
public final class LinkedSeamCheck {
    public static void main(String[] args) {
        int sx=78,sy=40,sz=46;var s=new VoxelSnapshot(-7,0,-7,sx,sy,sz,new String[]{"minecraft:air","minecraft:stone"},new String[]{"minecraft:plains","minecraft:plains"},new int[sx*sy*sz],new byte[sx*sy*sz],new byte[sx*sy*sz]);
        for(int z=0;z<sz;z++)for(int x=0;x<sx;x++)for(int y=0,h=16+(int)(4*Math.sin(x*.19)+3*Math.cos(z*.16));y<h;y++){int id=s.index(x,y,z);s.blocks()[id]=1;s.natural()[id]=1;}
        var left=build(s,7);var right=build(s,39);
        var expected=contour(left.level(0));
        if(!expected.equals(contour(right.level(0))))throw new AssertionError("source contours disagree");
        for(int a:left.threePrefixes())for(int b:right.threePrefixes()) {
            if(!expected.equals(contour(left.level(a)))||!expected.equals(contour(right.level(b))))throw new AssertionError("LOD seam changed");
            if(a<left.steps().size()&&!expected.equals(contour(left.transition(a,.5))))throw new AssertionError("left morph seam changed");
            if(b<right.steps().size()&&!expected.equals(contour(right.transition(b,.5))))throw new AssertionError("right morph seam changed");
        }
        System.out.println("Linked neighbouring histories: positions/normals match at 9 level pairs and intermediate phases; contour vertices="+expected.size());
    }
    private static ProgressiveMesh build(VoxelSnapshot s,int x){return QuadricSimplifier.progressive(ConformingSurface.extract(s,x,7,32,GreedyQuadMesher.build(s,x,7,32).selected()),.1,8);}
    private static Set<String> contour(SurfaceMesh m){
        var edges=new HashMap<Long,Integer>();
        for(int t=0;t<m.triangles();t++)for(int k=0;k<3;k++){int a=m.indices()[t*3+k],b=m.indices()[t*3+(k+1)%3];edges.merge(((long)Math.min(a,b)<<32)|Math.max(a,b),1,Integer::sum);}
        var out=new HashSet<String>();
        for(var edge:edges.entrySet())if(edge.getValue()==1){
            int a=(int)(edge.getKey()>>>32),b=(int)(long)edge.getKey();
            if(m.positions()[a*3]!=39||m.positions()[b*3]!=39)continue;
            float za=m.positions()[a*3+2],zb=m.positions()[b*3+2];
            if(za==zb&&(za==7||za==39))continue; // edge to a third tile, not the shared open contour
            for(int v:new int[]{a,b})out.add(m.positions()[v*3+1]+","+m.positions()[v*3+2]+":"+m.normals()[v*3]+","+m.normals()[v*3+1]+","+m.normals()[v*3+2]);
        }return out;
    }
}
