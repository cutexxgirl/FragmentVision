import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.util.*;

/** Every open ground contour must remain exact next to other tiles and retained caves. */
public final class LayeredSeamCheck {
 record Point(float x,float y,float z)implements Comparable<Point>{public int compareTo(Point b){int q=Float.compare(x,b.x);if(q!=0)return q;q=Float.compare(y,b.y);return q!=0?q:Float.compare(z,b.z);}}
 record Edge(Point a,Point b){}
 static Point point(SurfaceMesh mesh,int i){return new Point(mesh.positions()[i*3],mesh.positions()[i*3+1],mesh.positions()[i*3+2]);}
 static Set<Edge> contour(SurfaceMesh mesh){
  var counts=new HashMap<Edge,Integer>();for(int t=0;t<mesh.indices().length;t+=3)for(int k=0;k<3;k++){Point a=point(mesh,mesh.indices()[t+k]),b=point(mesh,mesh.indices()[t+(k+1)%3]);if(a.compareTo(b)>0){Point p=a;a=b;b=p;}counts.merge(new Edge(a,b),1,Integer::sum);}
  var contour=new HashSet<Edge>();for(var entry:counts.entrySet())if(entry.getValue()!=2)contour.add(entry.getKey());return contour;
 }
 public static void main(String[] args){
  int sx=78,sy=28,sz=46,cells=sx*sy*sz;int[] blocks=new int[cells];byte[] natural=new byte[cells],light=new byte[cells];Arrays.fill(light,(byte)240);
  var source=new VoxelSnapshot(-128,-4,192,sx,sy,sz,new String[]{"minecraft:air","minecraft:stone","minecraft:grass_block[snowy=false]"},new String[]{"minecraft:plains","minecraft:plains","minecraft:forest"},blocks,light,natural);
  for(int z=0;z<sz;z++)for(int x=0;x<sx;x++){
   int height=(int)(12+3*Math.sin(x*.23)+3*Math.cos(z*.19));
   for(int y=0;y<height;y++){int i=source.index(x,y,z);blocks[i]=y==height-1?2:1;natural[i]=1;}
  }
  // A tunnel enters an exterior valley: retained cave/outer surface junctions must not drift.
  for(int x=27;x<53;x++)for(int z=19;z<26;z++)for(int y=8;y<12;y++){int i=source.index(x,y,z);blocks[i]=0;natural[i]=0;}
  var context=LayeredTileBake.prepare(source);int accepted=0;SurfaceMaterialMap.Data first=null;
  for(int x:new int[]{7,39}){
   var layer=LayeredTileBake.build(source,context,x,7,32,true).stream().filter(p->p.name().equals("terrain")).findFirst().orElseThrow();
   var original=ConformingSurface.extractTerrain(context.terrainSource(),x,7,32,layer.exact());var expected=contour(original);
   for(var choice:layer.choices())if(choice.isMesh()){
    accepted++;if(!contour(choice.mesh()).equals(expected))throw new AssertionError("Changed native/tile/cave contour");
    for(int i=0;i<choice.mesh().vertices();i++){float px=choice.mesh().positions()[i*3];if(px<x||px>x+32)throw new AssertionError("Escaped tile bounds");}
   }
   if(layer.surfaceMap()!=null){if(first==null)first=layer.surfaceMap();else for(int z=0;z<32;z++)if(first.material(32,z)!=layer.surfaceMap().material(0,z))throw new AssertionError("Shared material halo disagrees");}
  }
  if(accepted==0)throw new AssertionError("Fixture must exercise an accepted smooth mesh");
  System.out.println("Adjacent terrain tiles and retained cave interfaces: exact open contours at every accepted level; shared material halo agrees; accepted="+accepted);
 }
}
