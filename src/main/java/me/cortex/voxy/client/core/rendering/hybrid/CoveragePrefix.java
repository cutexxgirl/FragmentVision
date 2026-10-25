package me.cortex.voxy.client.core.rendering.hybrid;

/** Exact integer summed-area table: only rectangles with every cell covered may be culled. */
public final class CoveragePrefix {
    private final int size;
    private final byte[] covered;
    private final int[] sums;
    private boolean dirty=true;
    public CoveragePrefix(int size) {
        if(size<=0||size>4096)throw new IllegalArgumentException("coverage size");
        this.size=size;covered=new byte[size*size];sums=new int[(size+1)*(size+1)];
    }
    public void put(int x,int z,int width,int height,float[] values) {
        if(x<0||z<0||width<0||height<0||x+width>size||z+height>size||values.length!=width*height)
            throw new IllegalArgumentException("coverage bounds");
        for(int row=0;row<height;row++)for(int col=0;col<width;col++)
            covered[(z+row)*size+x+col]=(byte)(values[row*width+col]>.5f?1:0);
        dirty=true;
    }
    public boolean dirty(){return dirty;}
    /** Borrowed read-only buffer, valid until the next update. */
    public int[] rebuild() {
        if(dirty){
            int stride=size+1;
            for(int z=0;z<size;z++){
                int row=0;
                for(int x=0;x<size;x++){row+=covered[z*size+x];sums[(z+1)*stride+x+1]=sums[z*stride+x+1]+row;}
            }
            dirty=false;
        }
        return sums;
    }
    public boolean covered(int x0,int z0,int x1,int z1){
        if(x0<0||z0<0||x1>=size||z1>=size||x1<x0||z1<z0)return false;
        int[] p=rebuild();int s=size+1;
        return p[(z1+1)*s+x1+1]-p[z0*s+x1+1]-p[(z1+1)*s+x0]+p[z0*s+x0]==(x1-x0+1)*(z1-z0+1);
    }
}
