package me.cortex.voxy.client.core.rendering.lod.bake;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exact axis-aligned rectangles: 8 bytes each, no vertex/index streams.
 * 6 X, 9 Y, 6 Z, 6 (U-1), 6 (V-1), 3 face, 8 light, 16 palette bits; 4 reserved.
 * XYZ identify the minimum owning voxel, positive faces add one on their axis.
 */
public record PackedQuads(long[] data) {
    public PackedQuads { for(long q:data)validate(q); }
    public int triangles(){return Math.multiplyExact(data.length,2);}
    /** Packed storage + shared six uint16 indices + seven indirect commands. */
    public long gpuBytes(){return payloadBytes()+12+QuadFaceBuckets.COMMAND_BYTES;}
    public long payloadBytes(){return data.length*8L;}
    public static long encode(int x,int y,int z,int u,int v,int face,int material,int light) {
        if(x<0||x>63||y<0||y>511||z<0||z>63||u<1||u>64||v<1||v>64||face<0||face>5||material<1||material>65535||light<0||light>255)
            throw new IllegalArgumentException("Packed quad range");
        return x|((long)y<<6)|((long)z<<15)|((long)(u-1)<<21)|((long)(v-1)<<27)|((long)face<<33)|((long)light<<36)|((long)material<<44);
    }
    public static int x(long q){return (int)(q&63);}
    public static int y(long q){return (int)((q>>>6)&511);}
    public static int z(long q){return (int)((q>>>15)&63);}
    public static int u(long q){return (int)((q>>>21)&63)+1;}
    public static int v(long q){return (int)((q>>>27)&63)+1;}
    public static int face(long q){return (int)((q>>>33)&7);}
    public static int light(long q){return (int)((q>>>36)&255);}
    public static int material(long q){return (int)((q>>>44)&65535);}
    private static void validate(long q) {
        if((q>>>60)!=0||face(q)>5||material(q)==0)throw new IllegalArgumentException("Invalid packed quad");
    }
    public void write(Path path,int ox,int oy,int oz) throws IOException {
        try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)))) {
            out.writeBytes("FVQUAD01");out.writeInt(ox);out.writeInt(oy);out.writeInt(oz);out.writeInt(data.length);
            for(long q:data)out.writeLong(q);
        }
    }
    public record Decoded(int originX,int originY,int originZ,PackedQuads quads) {}
    public static Decoded read(Path path) throws IOException {
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if(!new String(in.readNBytes(8),StandardCharsets.US_ASCII).equals("FVQUAD01"))throw new IOException("Unsupported quad page");
            int x=in.readInt(),y=in.readInt(),z=in.readInt(),count=in.readInt();
            if(count<0||count>1_000_000||Files.size(path)!=24+count*8L)throw new IOException("Invalid quad page length");
            long[] quads=new long[count];for(int i=0;i<count;i++)quads[i]=in.readLong();
            try{return new Decoded(x,y,z,new PackedQuads(quads));}
            catch(IllegalArgumentException bad){throw new IOException(bad.getMessage(),bad);}
        }
    }
    /** Diagnostic expansion only. Runtime pages retain the packed representation. */
    public SurfaceMesh mesh(int offsetX,int offsetZ) {
        float[] p=new float[data.length*12],n=new float[p.length];int[] indices=new int[data.length*6],attrs=new int[data.length*2];
        int[][] normal={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
        for(int i=0;i<data.length;i++) {
            long q=data[i];int x=x(q)+offsetX,y=y(q),z=z(q)+offsetZ,u=u(q),v=v(q),f=face(q);
            int[][] corners=switch(f) {
                case 0->new int[][]{{x,y,z},{x,y,z+u},{x,y+v,z+u},{x,y+v,z}};
                case 1->new int[][]{{x+1,y,z+u},{x+1,y,z},{x+1,y+v,z},{x+1,y+v,z+u}};
                case 2->new int[][]{{x,y,z+v},{x,y,z},{x+u,y,z},{x+u,y,z+v}};
                case 3->new int[][]{{x,y+1,z},{x,y+1,z+v},{x+u,y+1,z+v},{x+u,y+1,z}};
                case 4->new int[][]{{x+u,y,z},{x,y,z},{x,y+v,z},{x+u,y+v,z}};
                default->new int[][]{{x,y,z+1},{x+u,y,z+1},{x+u,y+v,z+1},{x,y+v,z+1}};
            };
            for(int k=0;k<4;k++)for(int c=0;c<3;c++){p[i*12+k*3+c]=corners[k][c];n[i*12+k*3+c]=normal[f][c];}
            int[] triangle={0,1,2,0,2,3};for(int k=0;k<6;k++)indices[i*6+k]=i*4+triangle[k];
            attrs[i*2]=attrs[i*2+1]=(material(q)<<11)|(f<<8)|light(q);
        }
        return new SurfaceMesh(p,n,indices,attrs,new boolean[data.length*4]);
    }
}
