package me.cortex.voxy.client.core.rendering.hybrid;

import java.util.Arrays;

/** CPU-only exterior-surface pilot. No seed generation, GL calls, or mutations of the world cache. */
public final class HybridSurfaceMesh {
    public static final int FLOATS_PER_VERTEX = 11;
    public record Grid(int points, int step, float[] height, int[] layer, int[] tint, boolean[] valid) {
        public Grid {
            int count = points * points;
            if (points < 3 || step < 1 || height.length != count || layer.length != count
                    || tint.length != count || valid.length != count) throw new IllegalArgumentException("Invalid surface grid");
        }
    }
    public record Mesh(float[] vertices, float[] coverage, int cells, int protectedCells) {}

    public static Mesh build(Grid grid) {
        int n = grid.points, cells = n - 1;
        float[] height = grid.height.clone(), coverage = new float[cells*cells];
        // A bounded bilateral adjustment removes small sampling kinks without rounding a ridge by many blocks.
        for (int z=1;z<n-1;z++) for (int x=1;x<n-1;x++) {
            int p=z*n+x;
            if (!grid.valid[p]) continue;
            float sum=grid.height[p]*2, total=2;
            for (int d:new int[]{-1,1,-n,n}) if (grid.valid[p+d]) {
                float w=1/(1+Math.abs(grid.height[p+d]-grid.height[p]));
                sum+=grid.height[p+d]*w;total+=w;
            }
            height[p]+=Math.clamp(sum/total-height[p],-1.5f,1.5f);
        }
        int protectedCells=0;
        for (int z=0;z<cells;z++) for(int x=0;x<cells;x++) {
            boolean safe=true;
            // Keep an eight-block margin from trees, water, unknown materials, and missing samples.
            for(int dz=-2;dz<=3&&safe;dz++) for(int dx=-2;dx<=3;dx++) {
                int xx=x+dx,zz=z+dz;
                if(xx<0||zz<0||xx>=n||zz>=n||!grid.valid[zz*n+xx]) {safe=false;break;}
            }
            coverage[z*cells+x]=safe?1:0;
            if(!safe)protectedCells++;
        }
        var out=new Floats();
        for(int z=0;z<cells;z++) for(int x=0;x<cells;x++) {
            if(coverage[z*cells+x]==0)continue;
            int p=z*n+x;
            int[] corners={p,p+1,p+n,p+n+1};
            int layer=grid.layer[p];int best=0;
            for(int a:corners){int count=0;for(int b:corners)if(grid.layer[a]==grid.layer[b])count++;if(count>best){best=count;layer=grid.layer[a];}}
            int[] order=((x+z)&1)==0?new int[]{0,2,3,0,3,1}:new int[]{0,2,1,1,2,3};
            for(int corner:order) {
                int xx=x+(corner&1), zz=z+(corner>>1), q=zz*n+xx;
                float dx=(height[q+1]-height[q-1])/(grid.step*2f);
                float dz=(height[q+n]-height[q-n])/(grid.step*2f);
                float len=(float)Math.sqrt(dx*dx+1+dz*dz);
                float nx=-dx/len,ny=1/len,nz=-dz/len;
                int tint=grid.tint[q];
                // Match Sodium/Vision's retained baked directional face shade, continuously across slopes.
                float shade=.6f*nx*nx+ny*ny+.8f*nz*nz;
                out.add(xx*grid.step,height[q],zz*grid.step,nx,ny,nz,
                        ((tint>>16)&255)/255f*shade,((tint>>8)&255)/255f*shade,(tint&255)/255f*shade,
                        layer,0); // material class filled from the catalog during upload
            }
            // Opaque skirts seal height differences against the retained voxel mesh.
            // Slightly inset so the exact same coverage lookup includes the skirt itself.
            float x0=x*grid.step+.02f,z0=z*grid.step+.02f,x1=(x+1)*grid.step-.02f,z1=(z+1)*grid.step-.02f;
            if(x==0||coverage[z*cells+x-1]==0)skirt(out,x0,z1,x0,z0,height[p+n],height[p],-1,0,layer,grid.tint[p]);
            if(x==cells-1||coverage[z*cells+x+1]==0)skirt(out,x1,z0,x1,z1,height[p+1],height[p+n+1],1,0,layer,grid.tint[p]);
            if(z==0||coverage[(z-1)*cells+x]==0)skirt(out,x0,z0,x1,z0,height[p],height[p+1],0,-1,layer,grid.tint[p]);
            if(z==cells-1||coverage[(z+1)*cells+x]==0)skirt(out,x1,z1,x0,z1,height[p+n+1],height[p+n],0,1,layer,grid.tint[p]);
        }
        return new Mesh(out.array(),coverage,cells,protectedCells);
    }
    private static void skirt(Floats out,float x0,float z0,float x1,float z1,float h0,float h1,
                              float nx,float nz,int layer,int tint){
        float shade=nx!=0?.6f:.8f;
        float[][] v={{x0,h0,z0},{x0,h0-8,z0},{x1,h1-8,z1},{x0,h0,z0},{x1,h1-8,z1},{x1,h1,z1}};
        for(float[] p:v)out.add(p[0],p[1],p[2],nx,0,nz,((tint>>16)&255)/255f*shade,
                ((tint>>8)&255)/255f*shade,(tint&255)/255f*shade,layer,0);
    }
    private static final class Floats {
        float[] data=new float[4096];int size;
        void add(float... values){if(size+values.length>data.length)data=Arrays.copyOf(data,Math.max(data.length*2,size+values.length));System.arraycopy(values,0,data,size,values.length);size+=values.length;}
        float[] array(){return Arrays.copyOf(data,size);}
    }
    private HybridSurfaceMesh() {}
}
