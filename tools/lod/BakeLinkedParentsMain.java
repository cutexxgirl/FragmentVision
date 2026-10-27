import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.nio.file.*;import java.util.*;
/** Offline linked fine/parent bake. Inputs are immutable, never live world saves. */
public final class BakeLinkedParentsMain {
 static SurfaceMesh remap(SurfaceMesh m,VoxelSnapshot s,Map<String,Integer> palette){int[] a=m.faceData().clone();for(int i=0;i<a.length;i++){int id=a[i]>>>11;Integer global=palette.get(s.states()[id]+"\t"+s.biomes()[id]);if(global==null)throw new IllegalStateException("Palette mismatch");a[i]=(global<<11)|(a[i]&2047);}return new SurfaceMesh(m.positions(),m.normals(),m.indices(),a,m.locked());}
 public static void main(String[] args)throws Exception{
  var input=Path.of(args[0]);var output=Path.of(args[1]);Files.createDirectories(output);int minX=Integer.parseInt(args[2]),minZ=Integer.parseInt(args[3]),maxX=Integer.parseInt(args[4]),maxZ=Integer.parseInt(args[5]);
  var palette=new HashMap<String,Integer>();for(String line:Files.readAllLines(Path.of(args[6]))){String[] v=line.split("\t");palette.put(v[0]+"\t"+v[1],Integer.parseInt(v[2]));}
  List<Path> inputs;try(var files=Files.list(input)){inputs=files.filter(p->p.getFileName().toString().matches("b[0-9]+[.]fvvol[.]gz")).sorted().toList();}
  for(var batch:inputs){var raw=VoxelSnapshot.read(batch);int h=7;if(raw.originX()+raw.sizeX()-h<=minX||raw.originX()+h>=maxX||raw.originZ()+raw.sizeZ()-h<=minZ||raw.originZ()+h>=maxZ)continue;
   var s=LodInputPolicy.clean(raw);var ctx=LayeredTileBake.prepare(s);
   for(int z=h;z<s.sizeZ()-h;z+=32)for(int x=h;x<s.sizeX()-h;x+=32){int wx=s.originX()+x,wz=s.originZ()+z;if(wx<minX||wx>=maxX||wz<minZ||wz>=maxZ)continue;
    String key=wx+"_"+wz;var done=output.resolve("linked_"+key+".tsv");
    var layer=LayeredTileBake.build(s,ctx,x,z,32,false).stream().filter(l->l.name().equals("terrain")).findFirst().orElse(null);if(layer==null)continue;
    var cap=SurfaceMaterialMap.build(s,x,z,32,ctx.terrain(),ctx.snow());int[] words=cap.words().clone();
    java.util.function.IntUnaryOperator global=id->{Integer g=palette.get(s.states()[id]+"\t"+s.biomes()[id]);if(g==null)throw new IllegalStateException("Cap palette");return g;};
    for(int i=0;i<words[1];i++){int at=2+i/2,shift=(i%2)*16,id=(cap.words()[at]>>>shift)&65535;words[at]=(words[at]&~(65535<<shift))|(global.applyAsInt(id)<<shift);}
    for(int i=cap.controls();i<cap.controls()+cap.macroWidth()*cap.macroWidth();i++)if(words[i]<0)words[i]=0x80000000|global.applyAsInt(words[i]&65535);
    var capPath=output.resolve("linked_"+key+".cap");if(!Files.exists(capPath))SurfaceMaterialMap.write(capPath,new SurfaceMaterialMap.Data(wx,wz,32,cap.macroWidth(),words,cap.coverError()));
    if(Files.exists(done))continue;
    var fine=ConformingSurface.extractTerrain(ctx.terrainSource(),x,z,32,layer.exact());boolean[] locked=fine.locked().clone();
    for(int i=0;i<fine.vertices();i++)for(int dz=-1;dz<=0;dz++)for(int dy=-1;dy<=0;dy++)for(int dx=-1;dx<=0;dx++){
     int xx=(int)fine.positions()[i*3]+dx,yy=(int)fine.positions()[i*3+1]+dy,zz=(int)fine.positions()[i*3+2]+dz,id=s.block(xx,yy,zz);
     if(id>0&&!LodInputPolicy.terrain(s.states()[id])&&ctx.snow()[s.index(xx,yy,zz)]==0)locked[i]=true;
    }
    fine=new SurfaceMesh(fine.positions(),fine.normals(),fine.indices(),fine.faceData(),locked);var seed=NativeCollarMesh.target(fine);
    var history=QuadricSimplifier.progressiveCollar(fine,seed,.12,4);int total=history.steps().size();int[] cuts={(int)(total*.75),(int)(total*.9),total};
    StringBuilder report=new StringBuilder();int cut0=0;
    for(int level=0;level<3;level++){
     int cut=cuts[level];double limit=new double[]{1.5,2,3}[level],cover=layer.surfaceMap()==null?0:layer.surfaceMap().coverError();SurfaceMesh parent;SurfaceError.Result audit;
     while(true){parent=history.level(cut);audit=SurfaceError.compare(fine,parent,limit-cover);if(audit.withinLimit())break;if(cut==0)throw new IllegalStateException("Seed error rejected "+key+" "+audit);cut=Math.max(0,cut-Math.max(1,total/8));}
     if(level==0)cut0=cut;String name="parent_"+key+"_"+level+".mesh";Files.deleteIfExists(output.resolve(name));MeshPage.write(output.resolve(name),remap(parent,s,palette),wx,s.originY(),wz,x,z);
     report.append(level+"\t"+name+"\t"+parent.triangles()+"\t"+(audit.upperBound()+cover)+"\t"+audit.sampledMax()+"\t"+audit.queries()+"\t"+cut+"\t"+layer.exact().triangles()+"\t"+fine.triangles()+"\n");
    }
    String collar="collar_"+key+".mesh",source="linked_"+key+".fine";Files.deleteIfExists(output.resolve(collar));Files.deleteIfExists(output.resolve(source));
    MeshPage.write(output.resolve(collar),remap(history.parentOnFineTopology(cut0),s,palette),wx,s.originY(),wz,x,z);
    float[] local=fine.positions().clone();for(int i=0;i<local.length;i+=3){local[i]-=x;local[i+2]-=z;}NativeFinePositions.write(output.resolve(source),new SurfaceMesh(local,fine.normals(),fine.indices(),fine.faceData(),fine.locked()));
    var reportTmp=done.resolveSibling(done.getFileName()+".tmp");Files.writeString(reportTmp,report);Files.move(reportTmp,done,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);System.out.println("LINKED "+key+" "+report.toString().replace('\n',';'));System.out.flush();
   }
  }
 }
}
