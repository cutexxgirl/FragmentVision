package me.cortex.voxy.client.core.rendering.lod.bake;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Versioned, checksummed CPU history. This is not a GPU page or an error certificate.
 * Caller supplies a hard file-size budget; counts are checked before allocation. */
public final class ProgressiveMeshFile {
    private static final long MAGIC=0x465650524f473031L; // FVPROG01
    private static final int VERSION=1,HEADER=8+4+4+32;
    private ProgressiveMeshFile() {}
    public static byte[] encode(ProgressiveMesh history) throws IOException {
        var mesh=history.source();var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(mesh.vertices());out.writeInt(mesh.triangles());out.writeInt(history.steps().size());
            for(float v:mesh.positions())out.writeFloat(v);
            for(float v:mesh.normals())out.writeFloat(v);
            for(boolean v:mesh.locked())out.writeBoolean(v);
            for(int v:mesh.indices())out.writeInt(v);
            for(int v:mesh.faceData())out.writeInt(v);
            for(var s:history.steps()) {
                out.writeInt(s.kept());out.writeInt(s.removed());out.writeFloat(s.x());out.writeFloat(s.y());out.writeFloat(s.z());
                out.writeFloat(s.nx());out.writeFloat(s.ny());out.writeFloat(s.nz());out.writeBoolean(s.locked());
            }
        }
        byte[] payload=bytes.toByteArray();var result=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(result)){out.writeLong(MAGIC);out.writeInt(VERSION);out.writeInt(payload.length);out.write(digest(payload));out.write(payload);}
        return result.toByteArray();
    }
    public static ProgressiveMesh decode(byte[] bytes,int maxBytes) throws IOException {
        if(maxBytes<HEADER||bytes.length<HEADER||bytes.length>maxBytes)throw new IOException("History size outside budget");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readLong()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Unsupported history format");
            int length=in.readInt();byte[] expected=in.readNBytes(32);
            if(length!=bytes.length-HEADER)throw new IOException("History length mismatch");
            byte[] payload=in.readNBytes(length);
            if(!MessageDigest.isEqual(expected,digest(payload)))throw new IOException("History checksum mismatch");
            try(var data=new DataInputStream(new ByteArrayInputStream(payload))) {
                int vertices=data.readInt(),triangles=data.readInt(),steps=data.readInt();
                // Exact layout equation also prevents forged counts from allocating beyond payload.
                if(vertices<0||triangles<0||steps<0||steps>vertices||12L+25L*vertices+16L*triangles+33L*steps!=length)
                    throw new IOException("Invalid history counts");
                float[] p=new float[Math.multiplyExact(vertices,3)],n=new float[p.length];boolean[] locked=new boolean[vertices];
                int[] indices=new int[Math.multiplyExact(triangles,3)],attributes=new int[triangles];
                for(int i=0;i<p.length;i++)p[i]=data.readFloat();for(int i=0;i<n.length;i++)n[i]=data.readFloat();
                for(int i=0;i<locked.length;i++)locked[i]=readFlag(data);
                for(int i=0;i<indices.length;i++)indices[i]=data.readInt();for(int i=0;i<attributes.length;i++)attributes[i]=data.readInt();
                var history=new ArrayList<ProgressiveMesh.Step>();
                for(int i=0;i<steps;i++)history.add(new ProgressiveMesh.Step(data.readInt(),data.readInt(),data.readFloat(),data.readFloat(),data.readFloat(),data.readFloat(),data.readFloat(),data.readFloat(),readFlag(data)));
                return new ProgressiveMesh(new SurfaceMesh(p,n,indices,attributes,locked),history);
            }catch(IllegalArgumentException|ArithmeticException error){throw new IOException("Invalid history data",error);}
        }
    }
    public static ProgressiveMesh read(Path path,int maxBytes) throws IOException {
        if(maxBytes<HEADER)throw new IOException("History size outside budget");
        // Bounded read even if the file grows after opening; never readAllBytes on an unchecked file.
        try(var in=Files.newInputStream(path)) {
            byte[] bytes=in.readNBytes(maxBytes);
            if(in.read()!=-1)throw new IOException("History exceeds budget");
            return decode(bytes,maxBytes);
        }
    }
    /** Atomically replaces a derived page on filesystems supporting atomic moves. */
    public static void write(Path path,ProgressiveMesh history) throws IOException {
        Path target=path.toAbsolutePath(),parent=target.getParent();Files.createDirectories(parent);
        byte[] bytes=encode(history);Path temporary=Files.createTempFile(parent,".fv-history-",".tmp");
        try{Files.write(temporary,bytes);Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally{Files.deleteIfExists(temporary);}
    }
    private static boolean readFlag(DataInputStream in)throws IOException{int flag=in.readUnsignedByte();if(flag>1)throw new IOException("Invalid history flag");return flag==1;}
    private static byte[] digest(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException error){throw new AssertionError(error);}}
}
