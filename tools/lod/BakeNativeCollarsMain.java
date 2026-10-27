import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.nio.file.*;
import java.util.*;
/** Bounded near-surface sidecars, same topology at both morph endpoints. */
public final class BakeNativeCollarsMain {
 public static void main(String[] args)throws Exception{
  var input=Path.of(args[0]);var output=Path.of(args[1]);Files.createDirectories(output);
  int minX=Integer.parseInt(args[2]),minZ=Integer.parseInt(args[3]),maxX=Integer.parseInt(args[4]),maxZ=Integer.parseInt(args[5]);
  var palette=new HashMap<String,Integer>();for(String line:Files.readAllLines(Path.of(args[6]))){String[] v=line.split("\t");palette.put(v[0]+"\t"+v[1],Integer.parseInt(v[2]));}
  List<Path> inputs;try(var files=Files.list(input)){inputs=files.filter(p->p.getFileName().toString().matches("b[0-9]+[.]fvvol[.]gz")).sorted().toList();}
  if(inputs.isEmpty())throw new IllegalArgumentException("No immutable batch snapshots");
  for(var batch:inputs){
   var raw=VoxelSnapshot.read(batch);
   int h=7;if(raw.originX()+raw.sizeX()-h<=minX||raw.originX()+h>=maxX||raw.originZ()+raw.sizeZ()-h<=minZ||raw.originZ()+h>=maxZ)continue;
   var s=LodInputPolicy.clean(raw);var ctx=LayeredTileBake.prepare(s);
   for(int z=h;z<s.sizeZ()-h;z+=32)for(int x=h;x<s.sizeX()-h;x+=32){
    int wx=s.originX()+x,wz=s.originZ()+z;if(wx<minX||wx>=maxX||wz<minZ||wz>=maxZ)continue;
    var path=output.resolve("collar_"+wx+"_"+wz+".mesh");if(Files.exists(path))continue;
    var layer=LayeredTileBake.build(s,ctx,x,z,32,false).stream().filter(l->l.name().equals("terrain")).findFirst().orElse(null);if(layer==null)continue;
    try{
     var fine=ConformingSurface.extractTerrain(ctx.terrainSource(),x,z,32,layer.exact());
     // Pin all contacts with retained models, water and snow. Their independent
     // geometry cannot move with the ground. Caves and outer contours are pinned by extraction.
     boolean[] locked=fine.locked().clone();
     for(int i=0;i<fine.vertices();i++)for(int dz=-1;dz<=0;dz++)for(int dy=-1;dy<=0;dy++)for(int dx=-1;dx<=0;dx++){
      int xx=(int)fine.positions()[i*3]+dx,yy=(int)fine.positions()[i*3+1]+dy,zz=(int)fine.positions()[i*3+2]+dz;
      int id=s.block(xx,yy,zz);if(id>0&&!LodInputPolicy.terrain(s.states()[id]))locked[i]=true;
     }
     fine=new SurfaceMesh(fine.positions(),fine.normals(),fine.indices(),fine.faceData(),locked);
     var target=NativeCollarMesh.target(fine);
     for(int i=0;i<target.positions().length;i++)if(Math.round(target.positions()[i])!=fine.positions()[i])throw new IllegalStateException("Source round-trip");
     int[] attributes=target.faceData().clone();for(int i=0;i<attributes.length;i++){int id=attributes[i]>>>11;Integer global=palette.get(s.states()[id]+"\t"+s.biomes()[id]);if(global==null)throw new IllegalStateException("Palette mismatch");attributes[i]=(global<<11)|(attributes[i]&2047);}
     target=new SurfaceMesh(target.positions(),target.normals(),target.indices(),attributes,target.locked());
     MeshPage.write(path,target,wx,s.originY(),wz,x,z);
     System.out.println(path.getFileName()+" vertices="+target.vertices()+" triangles="+target.triangles());
    }catch(IllegalStateException limit){System.out.println("EXACT FALLBACK "+wx+","+wz+": "+limit.getMessage());}
   }
  }
 }
}
