package me.cortex.voxy.client.core.rendering.lod.bake;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** v3: exact 1/256 positions and discrete attributes; page-local bit widths.
 * Common terrain uses 7-byte vertices and 4-byte triangles. Normals are oct8.
 * No extra coordinate/material loss is introduced by the bit packing.
 */
public final class MeshPage {
    public static final int HEADER_BYTES=52;
    public record Decoded(int originX,int originY,int originZ,SurfaceMesh mesh) {}
    private record Layout(int[] minimum,int[] bits,int vertexBytes,int indexBits,int attributeBits,int triangleBytes,int[] attributes) {}
    private static Layout layout(SurfaceMesh mesh) {
        if(mesh.vertices()>65536)throw new IllegalArgumentException("Page exceeds 16-bit index range");
        int[] attributes=Arrays.stream(mesh.faceData()).distinct().sorted().toArray();
        if(attributes.length>65536)throw new IllegalArgumentException("Page attribute dictionary limit");
        int[] min={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},max={Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE},bits=new int[3];
        for(int i=0;i<mesh.positions().length;i++){int q=quant(mesh.positions()[i]);min[i%3]=Math.min(min[i%3],q);max[i%3]=Math.max(max[i%3],q);}
        if(mesh.vertices()==0){Arrays.fill(min,0);Arrays.fill(max,0);}
        for(int c=0;c<3;c++)bits[c]=bitCount((long)max[c]-min[c]);
        int positionBits=bits[0]+bits[1]+bits[2];
        if(positionBits>48)throw new IllegalArgumentException("Page coordinate span exceeds packed vertex range");
        int ib=Math.max(1,bitCount(mesh.vertices()-1L)),ab=Math.max(1,bitCount(attributes.length-1L));
        return new Layout(min,bits,(positionBits+16+7)/8,ib,ab,(ib*3+ab+7)/8,attributes);
    }
    public static long payloadBytes(SurfaceMesh mesh) {
        Layout l=layout(mesh);
        return mesh.vertices()*(long)l.vertexBytes+mesh.triangles()*(long)l.triangleBytes+l.attributes.length*4L;
    }
    public static void write(Path path,SurfaceMesh mesh,int ox,int oy,int oz,int offsetX,int offsetZ) throws IOException {
        Layout l=layout(mesh);var ids=new HashMap<Integer,Integer>();for(int i=0;i<l.attributes.length;i++)ids.put(l.attributes[i],i);
        try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)))) {
            out.writeBytes("FVMSH003");out.writeInt(ox);out.writeInt(oy);out.writeInt(oz);
            out.writeInt(mesh.vertices());out.writeInt(mesh.triangles());out.writeInt(256);out.writeInt(l.attributes.length);
            out.writeInt(Math.subtractExact(l.minimum[0],Math.multiplyExact(offsetX,256)));
            out.writeInt(l.minimum[1]);
            out.writeInt(Math.subtractExact(l.minimum[2],Math.multiplyExact(offsetZ,256)));
            for(int b:l.bits)out.writeByte(b);out.writeByte(0);
            int positionBits=l.bits[0]+l.bits[1]+l.bits[2];
            for(int i=0;i<mesh.vertices();i++) {
                long q=0;int shift=0;
                for(int c=0;c<3;c++){q|=((long)quant(mesh.positions()[i*3+c])-l.minimum[c])<<shift;shift+=l.bits[c];}
                long normal=encodeNormal(mesh.normals()[i*3],mesh.normals()[i*3+1],mesh.normals()[i*3+2]);
                writeWord(out,q|(normal<<positionBits),l.vertexBytes);
            }
            for(int attribute:l.attributes)out.writeInt(attribute);
            for(int i=0;i<mesh.triangles();i++) {
                long q=0;
                for(int k=0;k<3;k++)q|=(long)mesh.indices()[i*3+k]<<(k*l.indexBits);
                q|=(long)ids.get(mesh.faceData()[i])<<(3*l.indexBits);
                writeWord(out,q,l.triangleBytes);
            }
        }
    }
    public static Decoded read(Path path) throws IOException {
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            String magic=new String(in.readNBytes(8),StandardCharsets.US_ASCII);
            if(magic.equals("FVMSH001"))return readLegacy(in,path);
            if(magic.equals("FVMSH002"))return readV2(in,path);
            if(!magic.equals("FVMSH003"))throw new IOException("Unsupported mesh page");
            int ox=in.readInt(),oy=in.readInt(),oz=in.readInt(),vc=in.readInt(),tc=in.readInt(),scale=in.readInt(),ac=in.readInt();
            int[] min={in.readInt(),in.readInt(),in.readInt()},bits={in.readUnsignedByte(),in.readUnsignedByte(),in.readUnsignedByte()};
            int reserved=in.readUnsignedByte(),pb=bits[0]+bits[1]+bits[2];
            if(vc<0||vc>65536||tc<0||tc>600000||scale!=256||ac<0||ac>Math.min(65536,tc)||reserved!=0
                    ||bits[0]>32||bits[1]>32||bits[2]>32||pb>48)throw new IOException("Invalid mesh header");
            int vb=(pb+16+7)/8,ib=Math.max(1,bitCount(vc-1L)),ab=Math.max(1,bitCount(ac-1L)),tb=(ib*3+ab+7)/8;
            if(Files.size(path)!=HEADER_BYTES+vc*(long)vb+ac*4L+tc*(long)tb)throw new IOException("Invalid mesh page size");
            float[] p=new float[vc*3],n=new float[vc*3];
            for(int i=0;i<vc;i++){
                long q=readWord(in,vb);int shift=0;
                for(int c=0;c<3;c++){long code=(q>>>shift)&mask(bits[c]);long value=(long)min[c]+code;if(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE)throw new IOException("Coordinate overflow");p[i*3+c]=value/256f;shift+=bits[c];}
                decodeOct8(n,i,q>>>pb);
            }
            int[] attrs=new int[ac];for(int i=0;i<ac;i++)attrs[i]=in.readInt();
            int[] indices=new int[tc*3],data=new int[tc];
            for(int i=0;i<tc;i++){
                long q=readWord(in,tb);for(int k=0;k<3;k++)indices[i*3+k]=(int)((q>>>(k*ib))&mask(ib));
                int id=(int)((q>>>(3*ib))&mask(ab));if(id>=ac)throw new IOException("Attribute index out of bounds");data[i]=attrs[id];
            }
            return decoded(ox,oy,oz,p,n,indices,data);
        }
    }
    private static Decoded readV2(DataInputStream in,Path path) throws IOException {
        int ox=in.readInt(),oy=in.readInt(),oz=in.readInt(),vc=in.readInt(),tc=in.readInt(),scale=in.readInt(),ac=in.readInt();
        if(vc<0||vc>65536||tc<0||tc>600000||scale!=256||ac<0||ac>Math.min(65536,tc)||Files.size(path)!=36+vc*8L+ac*4L+tc*(ac<=256?7L:8L))throw new IOException("Invalid v2 page size");
        float[] p=new float[vc*3],n=new float[vc*3];
        for(int i=0;i<vc;i++){long q=in.readLong();p[i*3]=((q&32767)-256)/256f;p[i*3+1]=((q>>>30)&262143)/256f;p[i*3+2]=(((q>>>15)&32767)-256)/256f;decodeOct8(n,i,q>>>48);}
        int[] attrs=new int[ac];for(int i=0;i<ac;i++)attrs[i]=in.readInt();
        int[] indices=new int[tc*3],data=new int[tc];
        for(int i=0;i<tc;i++){for(int k=0;k<3;k++)indices[i*3+k]=in.readUnsignedShort();int id=ac<=256?in.readUnsignedByte():in.readUnsignedShort();if(id>=ac)throw new IOException("Attribute index out of bounds");data[i]=attrs[id];}
        return decoded(ox,oy,oz,p,n,indices,data);
    }
    private static Decoded readLegacy(DataInputStream in,Path path) throws IOException {
        int ox=in.readInt(),oy=in.readInt(),oz=in.readInt(),vc=in.readInt(),tc=in.readInt(),scale=in.readInt();
        if(vc<0||vc>250000||tc<0||tc>600000||scale!=256||Files.size(path)!=32+vc*12L+tc*16L)throw new IOException("Invalid legacy page size");
        float[] p=new float[vc*3],n=new float[vc*3];
        for(int i=0;i<vc;i++){p[i*3]=in.readShort()/256f;p[i*3+1]=in.readInt()/256f;p[i*3+2]=in.readShort()/256f;normal(n,i,in.readShort()/32767.,in.readShort()/32767.);}
        int[] indices=new int[tc*3],data=new int[tc];for(int i=0;i<tc;i++){for(int k=0;k<3;k++)indices[i*3+k]=in.readInt();data[i]=in.readInt();}
        return decoded(ox,oy,oz,p,n,indices,data);
    }
    private static Decoded decoded(int x,int y,int z,float[] p,float[] n,int[] indices,int[] attrs) throws IOException {
        try{return new Decoded(x,y,z,new SurfaceMesh(p,n,indices,attrs,new boolean[p.length/3]));}
        catch(IllegalArgumentException bad){throw new IOException(bad.getMessage(),bad);}
    }
    private static long encodeNormal(double nx,double ny,double nz){
        double length=Math.abs(nx)+Math.abs(ny)+Math.abs(nz);if(length<1e-10){nx=0;ny=1;nz=0;length=1;}
        double u=nx/length,v=nz/length;if(ny<0){double old=u;u=(1-Math.abs(v))*sign(u);v=(1-Math.abs(old))*sign(v);}
        return (Math.round(u*127)&255)|((Math.round(v*127)&255)<<8);
    }
    private static void decodeOct8(float[] n,int i,long q) throws IOException {int u=(byte)q,v=(byte)(q>>>8);if(u==-128||v==-128)throw new IOException("Invalid oct8 normal");normal(n,i,u/127.,v/127.);}
    private static void normal(float[] n,int i,double u,double v){
        double y=1-Math.abs(u)-Math.abs(v);if(y<0){double old=u;u=(1-Math.abs(v))*sign(u);v=(1-Math.abs(old))*sign(v);}
        double length=Math.sqrt(u*u+y*y+v*v);n[i*3]=(float)(u/length);n[i*3+1]=(float)(y/length);n[i*3+2]=(float)(v/length);
    }
    private static int quant(float v){double q=Math.rint(v*256.);if(!Double.isFinite(q)||q<Integer.MIN_VALUE||q>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid coordinate");return (int)q;}
    private static int bitCount(long maximum){return maximum<=0?0:64-Long.numberOfLeadingZeros(maximum);}
    private static long mask(int bits){return (1L<<bits)-1;}
    private static void writeWord(DataOutputStream out,long q,int bytes) throws IOException {for(int j=bytes-1;j>=0;j--)out.writeByte((int)(q>>>(j*8)));}
    private static long readWord(DataInputStream in,int bytes) throws IOException {long q=0;for(int j=0;j<bytes;j++)q=(q<<8)|in.readUnsignedByte();return q;}
    private static double sign(double v){return v>=0?1:-1;}
    private MeshPage(){}
}
