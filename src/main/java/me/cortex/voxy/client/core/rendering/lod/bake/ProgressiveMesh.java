package me.cortex.voxy.client.core.rendering.lod.bake;

import java.util.*;

/** Ordered, dependent edge collapses with stable source IDs. Immutable owned input.
 * Ordinary histories certify single-operation local orientation. Collar histories
 * additionally guard every original source face through the final parent morph.
 * Swept self-intersection and
 * surface-distance certification are separate gates before runtime publication.
 */
public final class ProgressiveMesh {
    public record Step(int kept,int removed,float x,float y,float z,float nx,float ny,float nz,boolean locked) {}
    private final SurfaceMesh source;
    private final List<Step> steps;
    public ProgressiveMesh(SurfaceMesh source,List<Step> steps) {
        this.source=copy(source);this.steps=List.copyOf(steps);
        int[] roots=new int[source.vertices()];for(int i=0;i<roots.length;i++)roots[i]=i;
        for(Step s:steps) {
            if(s.kept()<0||s.removed()<0||s.kept()>=roots.length||s.removed()>=roots.length||s.kept()==s.removed()||
                    roots[s.kept()]!=s.kept()||roots[s.removed()]!=s.removed())throw new IllegalArgumentException("Invalid collapse history");
            for(float v:new float[]{s.x(),s.y(),s.z(),s.nx(),s.ny(),s.nz()})if(!Float.isFinite(v))throw new IllegalArgumentException("Nonfinite collapse");
            roots[s.removed()]=s.kept();
        }
    }
    public List<Step> steps(){return steps;}
    public SurfaceMesh source(){return copy(source);}
    public SurfaceMesh level(int prefix){return compact(state(prefix));}
    private record State(float[] p,float[] n,int[] indices,int[] attributes,boolean[] locked) {}
    private State state(int prefix) {
        if(prefix<0||prefix>steps.size())throw new IllegalArgumentException("Invalid prefix");
        float[] p=source.positions().clone(),n=source.normals().clone();boolean[] locked=source.locked().clone();
        int[] roots=new int[source.vertices()];for(int i=0;i<roots.length;i++)roots[i]=i;
        for(int i=0;i<prefix;i++) {
            Step s=steps.get(i);roots[s.removed()]=s.kept();
            p[s.kept()*3]=s.x();p[s.kept()*3+1]=s.y();p[s.kept()*3+2]=s.z();
            n[s.kept()*3]=s.nx();n[s.kept()*3+1]=s.ny();n[s.kept()*3+2]=s.nz();locked[s.kept()]=s.locked();
        }
        int[] ix=new int[source.indices().length],attr=new int[source.triangles()];int count=0;
        for(int t=0;t<source.triangles();t++) {
            int a=root(roots,source.indices()[t*3]),b=root(roots,source.indices()[t*3+1]),c=root(roots,source.indices()[t*3+2]);
            if(a==b||b==c||a==c)continue;
            ix[count*3]=a;ix[count*3+1]=b;ix[count*3+2]=c;attr[count++]=source.faceData()[t];
        }
        return new State(p,n,Arrays.copyOf(ix,count*3),Arrays.copyOf(attr,count),locked);
    }
    /** Diagnostic fine topology during exactly one dependent operation. At t=1,
     * expected zero-area triangles remain until the atomic index switch to level(prefix+1).
     * A GPU implementation should upload only active records, not this whole copied mesh.
     */
    public SurfaceMesh transition(int prefix,double t) {
        if(prefix<0||prefix>=steps.size()||!Double.isFinite(t)||t<0||t>1)throw new IllegalArgumentException("Invalid transition");
        State state=state(prefix);Step step=steps.get(prefix);
        for(int id:new int[]{step.kept(),step.removed()}) {
            int v=id*3;float[] target={step.x(),step.y(),step.z()},normal={step.nx(),step.ny(),step.nz()};
            for(int c=0;c<3;c++){state.p[v+c]=(float)(state.p[v+c]+(target[c]-state.p[v+c])*t);state.n[v+c]=(float)(state.n[v+c]+(normal[c]-state.n[v+c])*t);}
        }
        return new SurfaceMesh(state.p,state.n,state.indices,state.attributes,state.locked);
    }
    /** Parent positions/normals on the original fine indices, including the faces
     * that collapse to zero area. At the endpoint its surviving faces equal level(prefix). */
    public SurfaceMesh parentOnFineTopology(int prefix){
        if(prefix<0||prefix>steps.size())throw new IllegalArgumentException("Invalid prefix");
        float[] p=source.positions().clone(),n=source.normals().clone();int[] roots=new int[source.vertices()];for(int i=0;i<roots.length;i++)roots[i]=i;
        for(int i=0;i<prefix;i++){var s=steps.get(i);roots[s.removed()]=s.kept();p[s.kept()*3]=s.x();p[s.kept()*3+1]=s.y();p[s.kept()*3+2]=s.z();n[s.kept()*3]=s.nx();n[s.kept()*3+1]=s.ny();n[s.kept()*3+2]=s.nz();}
        float[] mapped=new float[p.length],normals=new float[n.length];for(int i=0;i<roots.length;i++){int id=root(roots,i);System.arraycopy(p,id*3,mapped,i*3,3);System.arraycopy(n,id*3,normals,i*3,3);}
        return new SurfaceMesh(mapped,normals,source.indices().clone(),source.faceData().clone(),source.locked().clone());
    }
    /** Three linked prefixes; their errors/cost must still be accepted independently. */
    public int[] threePrefixes(){return new int[]{steps.size()/3,steps.size()*2/3,steps.size()};}
    private static int root(int[] roots,int v){int r=v;while(roots[r]!=r)r=roots[r];while(roots[v]!=v){int next=roots[v];roots[v]=r;v=next;}return r;}
    private static SurfaceMesh compact(State s) {
        int[] remap=new int[s.locked.length];Arrays.fill(remap,-1);int count=0;
        for(int id:s.indices)if(remap[id]<0)remap[id]=count++;
        float[] p=new float[count*3],n=new float[count*3];boolean[] locked=new boolean[count];
        for(int i=0;i<remap.length;i++)if(remap[i]>=0){int to=remap[i];System.arraycopy(s.p,i*3,p,to*3,3);System.arraycopy(s.n,i*3,n,to*3,3);locked[to]=s.locked[i];}
        int[] ix=s.indices.clone();for(int i=0;i<ix.length;i++)ix[i]=remap[ix[i]];
        return new SurfaceMesh(p,n,ix,s.attributes.clone(),locked);
    }
    private static SurfaceMesh copy(SurfaceMesh s){return new SurfaceMesh(s.positions().clone(),s.normals().clone(),s.indices().clone(),s.faceData().clone(),s.locked().clone());}
}
