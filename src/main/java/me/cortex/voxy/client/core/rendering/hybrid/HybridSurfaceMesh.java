package me.cortex.voxy.client.core.rendering.hybrid;

import java.util.Arrays;

/** Cached exterior ground, including ground below trees. Not a multi-layer/cave mesher. */
public final class HybridSurfaceMesh {
    // position, normal, biome tint, six flat palette indices and six interpolated weights
    public static final int FLOATS_PER_VERTEX = 23;
    @FunctionalInterface public interface MaterialLookup { int layer(float x,float y,float z); }
    public record Grid(int points, int step, float[] height, int[] layer, int[] baseLayer, int[] tint, int[] light, boolean[] valid, MaterialLookup lookup) {
        public Grid(int points,int step,float[] height,int[] layer,int[] baseLayer,int[] tint,int[] light,boolean[] valid){
            this(points,step,height,layer,baseLayer,tint,light,valid,null);
        }
        public Grid {
            int count=points*points;
            if(points<3||step<1||height.length!=count||layer.length!=count||baseLayer.length!=count
                    ||light.length!=count||tint.length!=count||valid.length!=count)throw new IllegalArgumentException("Invalid surface grid");
        }
    }
    public record Mesh(float[] vertices, float[] coverage, int cells, int protectedCells) {}
    public static Mesh build(Grid g) {
        int n=g.points,cells=n-1;
        float[] height=g.height.clone(),coverage=new float[cells*cells];
        for(int z=1;z<n-1;z++)for(int x=1;x<n-1;x++){
            int p=z*n+x;if(!g.valid[p])continue;
            float sum=g.height[p]*2,total=2;
            for(int d:new int[]{-1,1,-n,n})if(g.valid[p+d]){
                float w=1/(1+Math.abs(g.height[p+d]-g.height[p]));sum+=g.height[p+d]*w;total+=w;
            }
            height[p]+=Math.clamp(sum/total-height[p],-.6f,.6f);
        }
        int protectedCells=0;
        for(int z=0;z<cells;z++)for(int x=0;x<cells;x++){
            boolean safe=true;
            // Only a one-sample halo for valid derivative normals, not an 8-block hole around every tree.
            for(int dz=-1;dz<=2&&safe;dz++)for(int dx=-1;dx<=2;dx++){
                int xx=x+dx,zz=z+dz;
                if(xx<0||zz<0||xx>=n||zz>=n||!g.valid[zz*n+xx]){safe=false;break;}
            }
            coverage[z*cells+x]=safe?1:0;if(!safe)protectedCells++;
        }
        float[][] normal=new float[n*n][];
        for(int z=1;z<n-1;z++)for(int x=1;x<n-1;x++){
            int q=z*n+x;
            float dx=(height[q+1]-height[q-1])/(g.step*2f),dz=(height[q+n]-height[q-n])/(g.step*2f);
            float len=(float)Math.sqrt(dx*dx+1+dz*dz);normal[q]=new float[]{-dx/len,1/len,-dz/len};
        }
        var out=new Floats();
        for(int z=0;z<cells;z++)for(int x=0;x<cells;x++){
            if(coverage[z*cells+x]==0)continue;
            int p=z*n+x;
            if(((x+z)&1)==0){triangle(out,g,height,normal,p,p+n,p+n+1);triangle(out,g,height,normal,p,p+n+1,p+1);}
            else {triangle(out,g,height,normal,p,p+n,p+1);triangle(out,g,height,normal,p+1,p+n,p+n+1);}
            // Short subsurface-colored closure at genuine cache/water/native boundaries only.
            if(coverage[z*cells+x-1]==0)skirt(out,g,height,p+n,p,-1,0);
            if(coverage[z*cells+x+1]==0)skirt(out,g,height,p+1,p+n+1,1,0);
            if(coverage[(z-1)*cells+x]==0)skirt(out,g,height,p,p+1,0,-1);
            if(coverage[(z+1)*cells+x]==0)skirt(out,g,height,p+n+1,p+n,0,1);
        }
        return new Mesh(out.array(),coverage,cells,protectedCells);
    }
    private record Point(float x,float y,float z,float[] normal,int tint,int light,int top,int base){}
    private static Point point(Grid g,float[] h,float[][] normals,int q){
        return new Point((q%g.points)*g.step,h[q],(q/g.points)*g.step,normals[q],g.tint[q],g.light[q],g.layer[q],g.baseLayer[q]);
    }
    private static void triangle(Floats out,Grid g,float[] h,float[][] normals,int a,int b,int c){
        subdivide(out,g,point(g,h,normals,a),point(g,h,normals,b),point(g,h,normals,c),0);
    }
    private static float length2(Point a,Point b){float x=a.x-b.x,y=a.y-b.y,z=a.z-b.z;return x*x+y*y+z*z;}
    private static int averageColor(int a,int b){return ((((a>>16)&255)+((b>>16)&255))/2<<16)|((((a>>8)&255)+((b>>8)&255))/2<<8)|(((a&255)+(b&255))/2);}
    private static Point midpoint(Point a,Point b){
        float[] n={a.normal[0]+b.normal[0],a.normal[1]+b.normal[1],a.normal[2]+b.normal[2]};
        float len=(float)Math.sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);for(int i=0;i<3;i++)n[i]/=len;
        int light=(((a.light&15)+(b.light&15))/2)|(((((a.light>>4)&15)+((b.light>>4)&15))/2)<<4);
        return new Point((a.x+b.x)/2,(a.y+b.y)/2,(a.z+b.z)/2,n,averageColor(a.tint,b.tint),light,a.y>=b.y?a.top:b.top,a.base);
    }
    private static void subdivide(Floats out,Grid g,Point a,Point b,Point c,int depth){
        float ab=length2(a,b),bc=length2(b,c),ca=length2(c,a);
        // Long cliff triangles need material samples at their actual height, not only at the ridge.
        // Split on the same edge positions; no displacement is added by this refinement.
        if(depth<5&&Math.max(ab,Math.max(bc,ca))>16){
            if(ab>=bc&&ab>=ca){Point m=midpoint(a,b);subdivide(out,g,a,m,c,depth+1);subdivide(out,g,m,b,c,depth+1);}
            else if(bc>=ca){Point m=midpoint(b,c);subdivide(out,g,a,b,m,depth+1);subdivide(out,g,a,m,c,depth+1);}
            else {Point m=midpoint(c,a);subdivide(out,g,a,b,m,depth+1);subdivide(out,g,m,b,c,depth+1);}
            return;
        }
        Point[] points={a,b,c};int[] palette=new int[6],base=new int[3];Arrays.fill(palette,-1);int count=0;
        for(int i=0;i<3;i++){
            Point p=points[i];base[i]=p.base;
            if(g.lookup!=null&&p.normal[1]<.9f){
                for(float distance:new float[]{.25f,1,2,3}){
                    int material=g.lookup.layer(p.x-p.normal[0]*distance,p.y-p.normal[1]*distance,p.z-p.normal[2]*distance);
                    if(material>=0){base[i]=material;break;}
                }
            }
            for(int layer:new int[]{p.top,base[i]}){
                boolean found=false;for(int k=0;k<count;k++)if(palette[k]==layer)found=true;
                if(!found)palette[count++]=layer;
            }
        }
        for(int i=0;i<3;i++){
            Point p=points[i];float[] weights=new float[6];
            float top=Math.clamp((p.normal[1]-.52f)/.38f,0,1);top=top*top*(3-2*top);
            for(int k=0;k<count;k++){
                if(palette[k]==p.top)weights[k]+=top;
                if(palette[k]==base[i])weights[k]+=1-top;
            }
            vertex(out,p.x,p.y,p.z,p.normal,p.tint,p.light,palette,weights);
        }
    }
    private static void vertex(Floats out,float x,float y,float z,float[] n,int tint,int light,int[] palette,float[] weights){
        // Retain baked directional face shading, but do not tint snow/rock by biome color.
        out.add(x,y,z,n[0],n[1],n[2],((tint>>16)&255)/255f,((tint>>8)&255)/255f,(tint&255)/255f);
        for(int k=0;k<6;k++)out.add(Math.max(0,palette[k]));
        for(float w:weights)out.add(w);
        out.add((light&15)/15f,((light>>4)&15)/15f);
    }
    private static void skirt(Floats out,Grid g,float[] h,int a,int b,float nx,float nz){
        int[] palette={g.baseLayer[a],g.baseLayer[b],0,0,0,0};
        for(int corner:new int[]{0,1,2,0,2,3}){
            int q=corner<2?a:b;float y=h[q]-((corner==1||corner==2)?2:0);
            float[] weights=new float[6];weights[q==a?0:1]=1;
            vertex(out,(q%g.points)*g.step-nx*.02f,y,(q/g.points)*g.step-nz*.02f,new float[]{nx,0,nz},g.tint[q],g.light[q],palette,weights);
        }
    }
    private static final class Floats {
        float[] data=new float[4096];int size;
        void add(float... v){if(size+v.length>data.length)data=Arrays.copyOf(data,Math.max(data.length*2,size+v.length));System.arraycopy(v,0,data,size,v.length);size+=v.length;}
        float[] array(){return Arrays.copyOf(data,size);}
    }
    private HybridSurfaceMesh(){}
}
