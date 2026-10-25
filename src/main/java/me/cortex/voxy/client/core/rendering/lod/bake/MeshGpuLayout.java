package me.cortex.voxy.client.core.rendering.lod.bake;

/** 8-byte shared vertex: x15/y17/z15 (1/256 block, offset -1) + oct8 normal.
 * Refuse unsupported coordinates instead of silently quantizing beyond file precision. */
public final class MeshGpuLayout {
    private MeshGpuLayout(){}
    public static long pack(float x,float y,float z,float nx,float ny,float nz){
        int ix=coordinate(x,32767),iy=coordinate(y,131071),iz=coordinate(z,32767);
        float sum=Math.abs(nx)+Math.abs(ny)+Math.abs(nz);if(!Float.isFinite(sum)||sum==0)throw new IllegalArgumentException("Invalid normal");
        nx/=sum;ny/=sum;nz/=sum;
        if(nz<0){float ox=nx;nx=(1-Math.abs(ny))*(ox>=0?1:-1);ny=(1-Math.abs(ox))*(ny>=0?1:-1);}
        int a=Math.round((nx*.5f+.5f)*255),b=Math.round((ny*.5f+.5f)*255);
        return ix|((long)iy<<15)|((long)iz<<32)|((long)a<<47)|((long)b<<55);
    }
    private static int coordinate(float v,int max){int q=Math.round((v+1)*256);if(!Float.isFinite(v)||q<0||q>max||Math.abs(q/256f-1-v)>.00001f)throw new IllegalArgumentException("Vertex outside exact GPU coordinate range");return q;}
    public static float[] position(long p){return new float[]{(p&32767)/256f-1,((p>>>15)&131071)/256f-1,((p>>>32)&32767)/256f-1};}
}
