package me.cortex.voxy.client.core.rendering.lod.bake;
import java.io.*;import java.nio.file.*;
/** Integer exact source endpoint, independent of the simplified parent's coordinates. */
public final class NativeFinePositions {
 public static void write(Path file,SurfaceMesh fine)throws IOException{
  try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)))){out.writeBytes("FVFINE01");out.writeInt(fine.vertices());for(float v:fine.positions()){int q=Math.round(v);if(v!=q||q<0||q>65535)throw new IOException("Exact source coordinate");out.writeShort(q);}}
 }
 public static float[] read(Path file,int expected)throws IOException{
  if(expected<0||expected>65536||Files.size(file)!=12L+expected*6L)throw new IOException("Fine endpoint size");
  try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))){if(!new String(in.readNBytes(8),java.nio.charset.StandardCharsets.US_ASCII).equals("FVFINE01")||in.readInt()!=expected)throw new IOException("Fine endpoint ABI");float[] p=new float[expected*3];for(int i=0;i<p.length;i++)p[i]=in.readUnsignedShort();return p;}
 }
 private NativeFinePositions(){}
}
