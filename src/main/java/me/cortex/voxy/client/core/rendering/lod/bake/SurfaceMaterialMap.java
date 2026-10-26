package me.cortex.voxy.client.core.rendering.lod.bake;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Lossless nearest source-column materials. One-cell halo, uniform 4x4 macros,
 * and a tile-local ID dictionary; no atlas blur, colour averaging or baked tint. */
public final class SurfaceMaterialMap {
 public record Data(int originX,int originZ,int width,int macroWidth,int[] words,double coverError){
  public long gpuBytes(){return words.length*4L;}
  public int controls(){return 2+(words[1]+1)/2;}
  public int material(int x,int z){
   int xx=Math.max(0,Math.min(macroWidth*4-1,x+1)),zz=Math.max(0,Math.min(macroWidth*4-1,z+1));
   int code=words[controls()+(zz/4)*macroWidth+xx/4];if(code<0)return code&65535;
   int sub=(zz%4)*4+xx%4,bits=words[0],per=32/bits,id=(words[code+sub/per]>>>((sub%per)*bits))&((1<<bits)-1);
   return(words[2+id/2]>>>((id%2)*16))&65535;
  }
  public void validate(int paletteSize){
   if(width<4||width>32||macroWidth!=(width+5)/4||words.length<3||words.length>2048||!Double.isFinite(coverError)||coverError<0||coverError>1)throw new IllegalArgumentException("Surface map bounds");
   int bits=words[0],count=words[1];if((bits!=4&&bits!=8&&bits!=16)||count<1||count>1296||count>(1<<bits))throw new IllegalArgumentException("Surface map dictionary");
   int controls=controls();if(words.length<controls+macroWidth*macroWidth)throw new IllegalArgumentException("Surface cap control bounds");
   for(int i=0;i<count;i++)if(((words[2+i/2]>>>((i%2)*16))&65535)>=paletteSize)throw new IllegalArgumentException("Surface cap dictionary ID");
   for(int i=0;i<macroWidth*macroWidth;i++){
    int code=words[controls+i];if(code<0){if((code&0x7fff0000)!=0||(code&65535)>=paletteSize)throw new IllegalArgumentException("Surface cap ID");}
    else{int per=32/bits;if(code<controls+macroWidth*macroWidth||code+16/per>words.length)throw new IllegalArgumentException("Surface cap pointer");
     for(int k=0;k<16;k++)if(((words[code+k/per]>>>((k%per)*bits))&((1<<bits)-1))>=count)throw new IllegalArgumentException("Surface cap dictionary index");}
   }
  }
 }
 public static Data build(VoxelSnapshot s,int minX,int minZ,int width,boolean[] terrain,byte[] projectableSnow){
  int macro=(width+5)/4,extent=macro*4;int[] columns=new int[extent*extent];double cover=0;
  var ids=new LinkedHashMap<Integer,Integer>();
  for(int z=0;z<extent;z++)for(int x=0;x<extent;x++){
   int xx=minX+x-1,zz=minZ+z-1;
   for(int y=s.sizeY()-1;y>=0;y--){int i=s.index(xx,y,zz),id=s.blocks()[i];if(id>0&&(terrain[id]||projectableSnow[i]!=0)){columns[z*extent+x]=id;if(projectableSnow[i]!=0)cover=Math.max(cover,TerrainShapes.height(s.states()[id])/8.);break;}}
   ids.computeIfAbsent(columns[z*extent+x],key->ids.size());
  }
  int bits=ids.size()<=16?4:ids.size()<=256?8:16,controls=2+(ids.size()+1)/2,per=32/bits;
  var words=new ArrayList<Integer>();for(int i=0;i<controls+macro*macro;i++)words.add(0);words.set(0,bits);words.set(1,ids.size());
  for(var entry:ids.entrySet()){int id=entry.getValue(),at=2+id/2;words.set(at,words.get(at)|(entry.getKey()<<((id%2)*16)));}
  for(int z=0;z<macro;z++)for(int x=0;x<macro;x++){
   int[] block=new int[16];for(int v=0;v<4;v++)for(int u=0;u<4;u++)block[v*4+u]=columns[(z*4+v)*extent+x*4+u];
   boolean uniform=true;for(int id:block)uniform&=id==block[0];
   if(uniform)words.set(controls+z*macro+x,0x80000000|block[0]);
   else{words.set(controls+z*macro+x,words.size());for(int k=0;k<16;k+=per){int word=0;for(int j=0;j<per;j++)word|=ids.get(block[k+j])<<(j*bits);words.add(word);}}
  }
  var result=new Data(s.originX()+minX,s.originZ()+minZ,width,macro,words.stream().mapToInt(Integer::intValue).toArray(),cover);result.validate(s.states().length);return result;
 }
 public static void write(Path p,Data data)throws IOException{
  try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p,StandardOpenOption.CREATE_NEW)))){
   out.writeBytes("FVCAP002");out.writeInt(data.originX);out.writeInt(data.originZ);out.writeInt(data.width);out.writeInt(data.macroWidth);out.writeInt(data.words.length);out.writeFloat((float)data.coverError);for(int word:data.words)out.writeInt(word);
  }
 }
 public static Data read(Path p)throws IOException{
  try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(p)))){
   if(!new String(in.readNBytes(8),java.nio.charset.StandardCharsets.US_ASCII).equals("FVCAP002"))throw new IOException("Surface map magic");
   int x=in.readInt(),z=in.readInt(),w=in.readInt(),m=in.readInt(),n=in.readInt();double error=in.readFloat();if(n<0||n>2048||Files.size(p)!=32+n*4L)throw new IOException("Surface map length");int[] words=new int[n];for(int i=0;i<n;i++)words[i]=in.readInt();var data=new Data(x,z,w,m,words,error);data.validate(65536);return data;
  }
 }
 private SurfaceMaterialMap(){}
}
