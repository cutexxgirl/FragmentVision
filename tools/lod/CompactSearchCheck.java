import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.util.*;
public final class CompactSearchCheck {
 public static void main(String[] args){
  var s=LodComparisonCheck.fixture(1);var ref=ConformingSurface.extract(s,7,7,32,GreedyQuadMesher.build(s,7,7,32).selected());var seed=SurfaceRelaxer.relax(ref,.35,16);double[] costs={.5,1,2,4};var cuts=QuadricSimplifier.sampleCompactTerrain(seed,.1,costs);
  for(int i=0;i<costs.length;i++){var expected=QuadricSimplifier.simplifyCompactTerrain(seed,.1,costs[i]).mesh();var actual=cuts.get(i);if(!Arrays.equals(actual.positions(),expected.positions())||!Arrays.equals(actual.indices(),expected.indices())||!Arrays.equals(actual.faceData(),expected.faceData())||!Arrays.equals(actual.normals(),expected.normals()))throw new AssertionError("Shared search cut differs at "+costs[i]);}
  float[] p={0,0,0,1,0,0,0,1,0,0,0,1};int[] ix={0,2,1,0,1,3,0,3,2,1,2,3};
  var tetra=new SurfaceMesh(p,new float[]{-1,-1,-1,1,0,0,0,1,0,0,0,1},ix,new int[4],new boolean[4]);
  var reduced=QuadricSimplifier.simplifyCompactTerrain(tetra,.01,100).mesh();if(reduced.triangles()!=4)throw new AssertionError("Closed tetrahedral component must not collapse into duplicate faces/disappear");
  System.out.println("Compact cost cuts equal independent runs; closed tetrahedral topology retained");
 }
}
