package me.cortex.voxy.client.core.rendering.hybrid;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;

/** Progressive volumetric surface prototype. Each shared corner has one morph trajectory.
 * Faces retain discrete materials; no heightfield, colour splatting or downward skirts. */
public final class HybridVoxelMesh {
    public static final int STRIDE=20;
    private static final int MAX_VERTICES=65536,MAX_FACES=98304;
    public static final class BudgetExceeded extends IllegalStateException {
        public BudgetExceeded(){super("Tile exceeds bounded meshing budget");}
    }
    public record Grid(int points,int step,int minY,int height,byte[] material,byte[] light,
                       int[] tint,boolean[] valid,int worldX,int worldZ) {}
    public record Mesh(float[] positions,int[] vertices,int[] indices,int[] coarseIndices,int[] farIndices,
                       float coarseError,float farError,float[] coverage,int cells,int protectedCells,int sharedVertices) {
        public long bytes(){return (long)(positions.length+vertices.length+indices.length+coarseIndices.length+farIndices.length+coverage.length)*4;}
    }
    private static final int[][][] CORNERS={
            {{0,0,0},{0,0,1},{0,1,1},{0,1,0}},{{1,0,1},{1,0,0},{1,1,0},{1,1,1}},
            {{0,0,1},{0,0,0},{1,0,0},{1,0,1}},{{0,1,0},{0,1,1},{1,1,1},{1,1,0}},
            {{1,0,0},{0,0,0},{0,1,0},{1,1,0}},{{0,0,1},{1,0,1},{1,1,1},{0,1,1}}};
    private static final int[][] NORMAL={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private static final class Vertex {
        float x,y,z,sx,sy,sz,nx,ny,nz;int neighbours,material=-1,signature;boolean boundary,mixed,seam;
        float[] smooth,parent4,parent8;
        Vertex(float x,float y,float z){this.x=x;this.y=y;this.z=z;}
    }
    private static final class Cluster {float x,y,z;int count;void add(float[] p){x+=p[0];y+=p[1];z+=p[2];count++;}float[] centre(){return new float[]{x/count,y/count,z/count};}}
    public static Mesh build(Grid g){return build(g,0,g.points-1);}
    /** The halo participates in smoothing, but only core faces are emitted. */
    public static Mesh build(Grid g,int coreMin,int coreMax){
        int n=g.points,cells=n-1,area=n*n;
        if(coreMin<0||coreMax>cells||coreMin>=coreMax)throw new IllegalArgumentException("Invalid core bounds");
        if(n>1023||g.height>1023||g.material.length!=area*g.height||g.light.length!=g.material.length)throw new IllegalArgumentException("Invalid volume");
        float[] coverage=new float[cells*cells];int protectedCount=0;
        for(int z=0;z<cells;z++)for(int x=0;x<cells;x++){
            boolean valid=true;
            for(int dz=-1;dz<=2&&valid;dz++)for(int dx=-1;dx<=2;dx++){
                int xx=x+dx,zz=z+dz;if(xx<0||zz<0||xx>=n||zz>=n||!g.valid[zz*n+xx]){valid=false;break;}
            }
            coverage[z*cells+x]=valid?1:0;if(!valid)protectedCount++;
        }
        if(protectedCount==coverage.length){int core=coreMax-coreMin;return new Mesh(new float[0],new int[0],new int[0],new int[0],new int[0],0,0,new float[core*core],core,core*core,0);}
        var ids=new Int2IntOpenHashMap();ids.defaultReturnValue(-1);
        var verts=new ArrayList<Vertex>();var faces=new it.unimi.dsi.fastutil.ints.IntArrayList();
        BitSet exterior=exteriorAir(g);
        int[] delta={-1,1,-area,area,-n,n};
        for(int y=1;y<g.height-1;y++)for(int z=1;z<cells-1;z++)for(int x=1;x<cells-1;x++){
            if(x==1&&z==1&&Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            if(coverage[z*cells+x]==0)continue;
            int p=y*area+z*n+x,m=g.material[p]&255;if(m==0||m==255)continue;
            for(int face=0;face<6;face++){
                int neighbour=p+delta[face];if(!exterior.get(neighbour))continue;
                int[] quad=new int[4];
                for(int k=0;k<4;k++){
                    int[] c=CORNERS[face][k];int vx=x+c[0],vy=y+c[1],vz=z+c[2];
                    int key=vx|(vz<<10)|(vy<<20),id=ids.get(key);
                    if(id<0){if(verts.size()>=MAX_VERTICES)throw new BudgetExceeded();id=verts.size();ids.put(key,id);verts.add(new Vertex(vx*g.step,vy+g.minY,vz*g.step));}
                    quad[k]=id;Vertex v=verts.get(id);
                    v.seam=vx==coreMin||vx==coreMax||vz==coreMin||vz==coreMax;
                    v.signature|=1<<(m-1);
                    if(v.material<0)v.material=m;else if(v.material!=m)v.mixed=true;
                    for(int dz=-1;dz<=0;dz++)for(int dx=-1;dx<=0;dx++)
                        if(coverage[(vz+dz)*cells+vx+dx]==0)v.boundary=true;
                    float weight=face==2||face==3?g.step*g.step:g.step;
                    v.nx+=NORMAL[face][0]*weight;v.ny+=NORMAL[face][1]*weight;v.nz+=NORMAL[face][2]*weight;
                }
                for(int k=0;k<4;k++){
                    Vertex v=verts.get(quad[k]),a=verts.get(quad[(k+1)&3]),b=verts.get(quad[(k+3)&3]);
                    v.sx+=a.x+b.x;v.sy+=a.y+b.y;v.sz+=a.z+b.z;v.neighbours+=2;
                }
                if(faces.size()/8>=MAX_FACES)throw new BudgetExceeded();
                for(int id:quad)faces.add(id);
                faces.add(m-1);faces.add(g.light[neighbour]&255);faces.add(g.tint[z*n+x]);faces.add(face);
            }
        }
        ids.clear();ids.trim();exterior=null;
        smoothSurface(verts,faces);
        cluster(verts,g,4,false);preventFoldovers(verts,faces,false);
        cluster(verts,g,8,true);preventFoldovers(verts,faces,true);
        // Discard halo output and compact source vertices before creating GPU streams.
        var kept=new it.unimi.dsi.fastutil.ints.IntArrayList();
        int[] remap=new int[verts.size()];Arrays.fill(remap,-1);int used=0;
        for(int i=0;i<faces.size();i+=8){
            float cx=0,cz=0;
            for(int k=0;k<4;k++){Vertex v=verts.get(faces.getInt(i+k));cx+=v.x/g.step;cz+=v.z/g.step;}
            cx*=.25f;cz*=.25f;
            // A side face lies on a core edge: determine ownership from its normal.
            int face=faces.getInt(i+7);cx-=NORMAL[face][0]*.25f;cz-=NORMAL[face][2]*.25f;
            if(cx<coreMin||cz<coreMin||cx>=coreMax||cz>=coreMax)continue;
            kept.add(i);
            for(int k=0;k<4;k++){int id=faces.getInt(i+k);if(remap[id]<0)remap[id]=used++;}
        }
        int faceCount=kept.size();float coarseError=0,farError=0;
        float[] positions=new float[Math.multiplyExact(used,STRIDE)];
        for(int j=0;j<verts.size();j++)if(remap[j]>=0){
            Vertex v=verts.get(j);int p=remap[j]*STRIDE;
            positions[p]=v.x;positions[p+1]=v.y;positions[p+2]=v.z;positions[p+3]=v.boundary?1:0;
            System.arraycopy(v.smooth,0,positions,p+4,3);System.arraycopy(v.parent4,0,positions,p+8,3);System.arraycopy(v.parent8,0,positions,p+12,3);
            positions[p+16]=v.nx;positions[p+17]=v.ny;positions[p+18]=v.nz;
            coarseError=Math.max(coarseError,distance(v.smooth,v.parent4));farError=Math.max(farError,distance(v.parent4,v.parent8));
        }
        int[] attributes=new int[Math.multiplyExact(faceCount,16)],indices=new int[Math.multiplyExact(faceCount,6)];
        for(int f=0;f<faceCount;f++){
            int q=f*4,j=f*6,i=kept.getInt(f);
            indices[j]=q;indices[j+1]=q+1;indices[j+2]=q+2;indices[j+3]=q;indices[j+4]=q+2;indices[j+5]=q+3;
            for(int k=0;k<4;k++){
                int a=f*16+k*4;attributes[a]=remap[faces.getInt(i+k)];attributes[a+1]=faces.getInt(i+4);
                attributes[a+2]=faces.getInt(i+6);attributes[a+3]=faces.getInt(i+7)|(faces.getInt(i+5)<<3);
            }
        }
        int coreCells=coreMax-coreMin;float[] coreCoverage=new float[coreCells*coreCells];protectedCount=0;
        for(int z=0;z<coreCells;z++)for(int x=0;x<coreCells;x++){
            float value=coverage[(z+coreMin)*cells+x+coreMin];coreCoverage[z*coreCells+x]=value;if(value==0)protectedCount++;
        }
        return new Mesh(positions,attributes,indices,compactIndices(positions,attributes,indices,8),
                compactIndices(positions,attributes,indices,12),coarseError,farError,coreCoverage,coreCells,protectedCount,used);
    }
    private static float distance(float[] a,float[] b){float x=a[0]-b[0],y=a[1]-b[1],z=a[2]-b[2];return (float)Math.sqrt(x*x+y*y+z*z);}
    private static int[] compactIndices(float[] positions,int[] attributes,int[] indices,int target){
        var output=new it.unimi.dsi.fastutil.ints.IntArrayList();
        for(int i=0;i<indices.length;i+=3){
            int a=attributes[indices[i]*4]*STRIDE+target,b=attributes[indices[i+1]*4]*STRIDE+target,c=attributes[indices[i+2]*4]*STRIDE+target;
            float ux=positions[b]-positions[a],uy=positions[b+1]-positions[a+1],uz=positions[b+2]-positions[a+2];
            float vx=positions[c]-positions[a],vy=positions[c+1]-positions[a+1],vz=positions[c+2]-positions[a+2];
            float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
            boolean removable=target==12?nx*nx+ny*ny+nz*nz<1e-10f:
                    sameTrajectory(positions,a,b)||sameTrajectory(positions,b,c)||sameTrajectory(positions,a,c);
            if(removable)continue;
            output.add(indices[i]);output.add(indices[i+1]);output.add(indices[i+2]);
        }
        return output.toIntArray();
    }
    private static boolean sameTrajectory(float[] positions,int a,int b){
        for(int i=0;i<3;i++)if(positions[a+i]!=positions[b+i]||positions[a+4+i]!=positions[b+4+i])return false;
        return true;
    }
    private static BitSet exteriorAir(Grid g){
        // A bounded flood fill omits sealed underground cavities. Open cave mouths,
        // overhangs and tunnel ceilings remain part of the exterior volume surface.
        int n=g.points,area=n*n,h=g.height;
        var seen=new BitSet(g.material.length);
        var queue=new it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue();
        for(int y=0;y<h;y++)for(int z=0;z<n;z++)for(int x=0;x<n;x++){
            if(x!=0&&x!=n-1&&z!=0&&z!=n-1&&y!=0&&y!=h-1)continue;
            int p=y*area+z*n+x;if(g.material[p]==0){seen.set(p);queue.enqueue(p);}
        }
        int visited=0;
        while(!queue.isEmpty()){
            if((visited++&65535)==0&&Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            int p=queue.dequeueInt(),y=p/area,z=(p%area)/n,x=p%n;
            if(x>0)visitAir(g,p-1,seen,queue);if(x+1<n)visitAir(g,p+1,seen,queue);
            if(z>0)visitAir(g,p-n,seen,queue);if(z+1<n)visitAir(g,p+n,seen,queue);
            if(y>0)visitAir(g,p-area,seen,queue);if(y+1<h)visitAir(g,p+area,seen,queue);
        }
        return seen;
    }
    private static void visitAir(Grid g,int p,BitSet seen,it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue queue){
        if(g.material[p]==0&&!seen.get(p)){seen.set(p);queue.enqueue(p);}
    }
    private static void smoothSurface(ArrayList<Vertex> vertices,it.unimi.dsi.fastutil.ints.IntArrayList faces){
        for(Vertex v:vertices)v.smooth=new float[]{v.x,v.y,v.z};
        // Relax a connected surface, rather than independently beveling each voxel.
        // The fixed displacement budget protects narrow ledges and tunnel ceilings.
        for(int pass=0;pass<5;pass++){
            for(Vertex v:vertices){v.sx=0;v.sy=0;v.sz=0;}
            for(int i=0;i<faces.size();i+=8)for(int k=0;k<4;k++){
                Vertex v=vertices.get(faces.getInt(i+k));
                float[] a=vertices.get(faces.getInt(i+((k+1)&3))).smooth,b=vertices.get(faces.getInt(i+((k+3)&3))).smooth;
                v.sx+=a[0]+b[0];v.sy+=a[1]+b[1];v.sz+=a[2]+b[2];
            }
            for(Vertex v:vertices)if(!v.boundary){
                float dx=v.smooth[0]+.65f*(v.sx/v.neighbours-v.smooth[0])-v.x;
                float dy=v.smooth[1]+.65f*(v.sy/v.neighbours-v.smooth[1])-v.y;
                float dz=v.smooth[2]+.65f*(v.sz/v.neighbours-v.smooth[2])-v.z;
                float length=(float)Math.sqrt(dx*dx+dy*dy+dz*dz),limit=length>1.25f?1.25f/length:1;
                v.smooth[0]=v.x+dx*limit;v.smooth[1]=v.y+dy*limit;v.smooth[2]=v.z+dz*limit;
            }
        }
        // Lighting must describe the relaxed surface, not its original cubical faces.
        for(Vertex v:vertices){v.nx=0;v.ny=0;v.nz=0;}
        for(int i=0;i<faces.size();i+=8)for(int tri=0;tri<2;tri++){
            Vertex a=vertices.get(faces.getInt(i)),b=vertices.get(faces.getInt(i+(tri==0?1:2))),c=vertices.get(faces.getInt(i+(tri==0?2:3)));
            float ux=b.smooth[0]-a.smooth[0],uy=b.smooth[1]-a.smooth[1],uz=b.smooth[2]-a.smooth[2];
            float vx=c.smooth[0]-a.smooth[0],vy=c.smooth[1]-a.smooth[1],vz=c.smooth[2]-a.smooth[2];
            float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
            for(Vertex v:new Vertex[]{a,b,c}){v.nx+=nx;v.ny+=ny;v.nz+=nz;}
        }
        for(Vertex v:vertices){
            float length=(float)Math.sqrt(v.nx*v.nx+v.ny*v.ny+v.nz*v.nz);
            if(length<1e-5f){v.nx=0;v.ny=1;v.nz=0;}else{v.nx/=length;v.ny/=length;v.nz/=length;}
        }
    }
    private static void preventFoldovers(ArrayList<Vertex> vertices,it.unimi.dsi.fastutil.ints.IntArrayList faces,boolean far){
        // A cluster may collapse a face to zero area, but may not turn it inside out.
        for(int pass=0;pass<8;pass++){
            boolean changed=false;
            for(int i=0;i<faces.size();i+=8)for(int tri=0;tri<2;tri++){
                Vertex a=vertices.get(faces.getInt(i)),b=vertices.get(faces.getInt(i+(tri==0?1:2))),c=vertices.get(faces.getInt(i+(tri==0?2:3)));
                float[] aa=far?a.parent8:a.parent4,bb=far?b.parent8:b.parent4,cc=far?c.parent8:c.parent4;
                float ux=bb[0]-aa[0],uy=bb[1]-aa[1],uz=bb[2]-aa[2],vx=cc[0]-aa[0],vy=cc[1]-aa[1],vz=cc[2]-aa[2];
                int[] normal=NORMAL[faces.getInt(i+7)];
                if((uy*vz-uz*vy)*normal[0]+(uz*vx-ux*vz)*normal[1]+(ux*vy-uy*vx)*normal[2]<-.0001f){
                    for(Vertex v:new Vertex[]{a,b,c}){
                        float[] fallback=far?v.parent4:v.smooth,old=far?v.parent8:v.parent4;
                        if(old!=fallback){if(far)v.parent8=fallback;else v.parent4=fallback;changed=true;}
                    }
                }
            }
            if(!changed)return;
        }
    }
    private static long clusterKey(Vertex v,Grid g,int size){
        int x=Math.floorDiv((int)v.x+g.worldX,size),y=Math.floorDiv((int)v.y,size),z=Math.floorDiv((int)v.z+g.worldZ,size);
        int axis=Math.abs(v.nx)>Math.abs(v.ny)?0:1;if(Math.abs(v.nz)>(axis==0?Math.abs(v.nx):Math.abs(v.ny)))axis=2;
        int cone=axis*2+((axis==0?v.nx:axis==1?v.ny:v.nz)>0?1:0);
        // World-aligned keys prevent a recentered snapshot from moving the parent grid.
        return ((long)(x&0x7ffff)<<45)|((long)(z&0x7ffff)<<26)|((long)(y&0x3ff)<<16)|cone;
    }
    private static void cluster(ArrayList<Vertex> vertices,Grid g,int size,boolean second){
        var groups=new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<Long2ObjectOpenHashMap<Cluster>>();
        for(Vertex v:vertices)if(!v.boundary&&!v.seam){
            var clusters=groups.computeIfAbsent(v.signature,unused->new Long2ObjectOpenHashMap<Cluster>());
            long key=clusterKey(v,g,size);Cluster c=clusters.get(key);if(c==null){c=new Cluster();clusters.put(key,c);}c.add(second?v.parent4:v.smooth);
        }
        for(Vertex v:vertices){
            float[] target=v.smooth;
            if(!v.boundary&&!v.seam){target=groups.get(v.signature).get(clusterKey(v,g,size)).centre();
                float dx=target[0]-v.smooth[0],dy=target[1]-v.smooth[1],dz=target[2]-v.smooth[2];
                if(dx*dx+dy*dy+dz*dz>size*size*.36f)target=second?v.parent4:v.smooth;
            }
            if(second)v.parent8=target;else v.parent4=target;
        }
    }
    private HybridVoxelMesh(){}
}
