import me.cortex.voxy.client.core.rendering.lod.bake.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class ProgressiveFileCheck {
    static void require(boolean ok){if(!ok)throw new AssertionError();}
    static void refused(byte[] bytes,int budget)throws Exception{try{ProgressiveMeshFile.decode(bytes,budget);throw new AssertionError("Accepted invalid history");}catch(IOException expected){}}
    static void same(SurfaceMesh a,SurfaceMesh b){require(Arrays.equals(a.positions(),b.positions())&&Arrays.equals(a.normals(),b.normals())&&Arrays.equals(a.indices(),b.indices())&&Arrays.equals(a.faceData(),b.faceData())&&Arrays.equals(a.locked(),b.locked()));}
    public static void main(String[] args)throws Exception {
        var mesh=new SurfaceMesh(new float[]{0,0,0,1,0,0,0,0,1,1,0,1},new float[]{0,1,0,0,1,0,0,1,0,0,1,0},new int[]{0,2,1,1,2,3},new int[]{17,31},new boolean[]{true,false,true,false});
        var history=new ProgressiveMesh(mesh,List.of(new ProgressiveMesh.Step(1,3,1,0,.5f,0,1,0,false)));
        byte[] bytes=ProgressiveMeshFile.encode(history);var restored=ProgressiveMeshFile.decode(bytes,bytes.length);
        require(restored.steps().equals(history.steps()));require(Arrays.equals(bytes,ProgressiveMeshFile.encode(restored)));
        for(int i=0;i<=history.steps().size();i++)same(history.level(i),restored.level(i));
        for(double t:new double[]{0,.25,.5,.75,1})same(history.transition(0,t),restored.transition(0,t));
        for(int length:new int[]{0,12,47,bytes.length-1})refused(Arrays.copyOf(bytes,length),bytes.length);
        refused(bytes,bytes.length-1);
        for(int at:new int[]{0,8,12,16,bytes.length-1}){byte[] bad=bytes.clone();bad[at]^=1;refused(bad,bytes.length);}
        // Malformed but correctly checksummed counts cannot trigger huge allocations.
        byte[] bad=bytes.clone();ByteBuffer.wrap(bad).putInt(48,Integer.MAX_VALUE);
        System.arraycopy(MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(bad,48,bad.length)),0,bad,16,32);refused(bad,bytes.length);
        Path dir=Files.createTempDirectory("fv-history-check-");Path file=dir.resolve("history.fvprog");
        try{ProgressiveMeshFile.write(file,history);ProgressiveMeshFile.write(file,restored);same(history.level(1),ProgressiveMeshFile.read(file,bytes.length).level(1));try(var files=Files.list(dir)){require(files.count()==1);}}
        finally{Files.deleteIfExists(file);Files.deleteIfExists(dir);}
        System.out.println("ProgressiveFileCheck passed: exact levels/morph replay, deterministic bytes, checksum/version/count/budget rejection, atomic replacement; "+bytes.length+" bytes");
    }
}
