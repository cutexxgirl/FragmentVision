package me.cortex.voxy.client.core.rendering.lod.bake;

import java.util.*;

/** Bounded offline bevel of a welded terrain boundary before edge collapse.
 * Shared contours, material seams and protected neighbours stay fixed. This does not
 * relax leaf/model geometry. Final selection still audits against the untouched source.
 */
public final class SurfaceRelaxer {
    public static SurfaceMesh relax(SurfaceMesh source,double radius){return relax(source,radius,256);}
    public static SurfaceMesh relax(SurfaceMesh source,double radius,int precision){
        if(precision!=16&&precision!=256)throw new IllegalArgumentException("Quantization");
        if(!(radius>0&&radius<=.45))throw new IllegalArgumentException("Relaxation radius");
        @SuppressWarnings("unchecked") Set<Integer>[] links=(Set<Integer>[])new Set<?>[source.vertices()];
        for(int i=0;i<links.length;i++)links[i]=new TreeSet<>();
        for(int t=0;t<source.indices().length;t+=3)for(int k=0;k<3;k++){
            int a=source.indices()[t+k],b=source.indices()[t+(k+1)%3];links[a].add(b);links[b].add(a);
        }
        float[] original=source.positions(),p=original.clone();
        for(int pass=0;pass<4;pass++){
            float[] next=p.clone();
            for(int v=0;v<links.length;v++)if(!source.locked()[v]&&!links[v].isEmpty()){
                double[] sum=new double[3];double weight=0;
                for(int n:links[v]){double dx=original[v*3]-original[n*3],dy=original[v*3+1]-original[n*3+1],dz=original[v*3+2]-original[n*3+2];double w=1/Math.max(1,Math.sqrt(dx*dx+dy*dy+dz*dz));weight+=w;for(int c=0;c<3;c++)sum[c]+=p[n*3+c]*w;}
                double[] move=new double[3];double length=0;
                for(int c=0;c<3;c++){move[c]=(p[v*3+c]+sum[c]/weight)*.5-original[v*3+c];length+=move[c]*move[c];}
                double scale=Math.min(1,radius/Math.max(1e-20,Math.sqrt(length)));
                for(int c=0;c<3;c++)next[v*3+c]=quant(original[v*3+c]+move[c]*scale,precision);
            }
            // Backtrack the whole Jacobi update rather than publishing inverted triangles.
            for(int attempt=0;attempt<8&&(!oriented(source,p,next)||!oriented(source,original,next));attempt++)for(int i=0;i<next.length;i++)next[i]=quant((p[i]+next[i])*.5,precision);
            if((!oriented(source,p,next)||!oriented(source,original,next)))break;p=next;
        }
        float[] normals=new float[p.length];
        for(int t=0;t<source.indices().length;t+=3){int a=source.indices()[t],b=source.indices()[t+1],c=source.indices()[t+2];double[] n=normal(p,a,b,c);for(int v:new int[]{a,b,c})for(int d=0;d<3;d++)normals[v*3+d]+=n[d];}
        for(int v=0;v<links.length;v++){
            double length=Math.sqrt(normals[v*3]*normals[v*3]+normals[v*3+1]*normals[v*3+1]+normals[v*3+2]*normals[v*3+2]);
            for(int c=0;c<3;c++)normals[v*3+c]=source.locked()[v]?source.normals()[v*3+c]:length>1e-12?(float)(normals[v*3+c]/length):source.normals()[v*3+c];
        }
        return new SurfaceMesh(p,normals,source.indices(),source.faceData(),source.locked());
    }
    private static float quant(double v,int precision){return Math.round(v*precision)/(float)precision;}
    private static double[] normal(float[] p,int a,int b,int c){double x=p[b*3]-p[a*3],y=p[b*3+1]-p[a*3+1],z=p[b*3+2]-p[a*3+2],u=p[c*3]-p[a*3],v=p[c*3+1]-p[a*3+1],w=p[c*3+2]-p[a*3+2];return new double[]{y*w-z*v,z*u-x*w,x*v-y*u};}
    private static boolean oriented(SurfaceMesh source,float[] previous,float[] candidate){
        for(int t=0;t<source.indices().length;t+=3){int a=source.indices()[t],b=source.indices()[t+1],c=source.indices()[t+2];double[] n=normal(previous,a,b,c),m=normal(candidate,a,b,c);double dot=0,nn=0,mm=0;for(int d=0;d<3;d++){dot+=n[d]*m[d];nn+=n[d]*n[d];mm+=m[d]*m[d];}if(mm<1e-12||dot<.5*Math.sqrt(nn*mm))return false;}return true;
    }
    private SurfaceRelaxer(){}
}
