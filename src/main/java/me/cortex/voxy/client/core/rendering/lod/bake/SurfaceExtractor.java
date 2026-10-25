package me.cortex.voxy.client.core.rendering.lod.bake;

import java.util.*;

/** Unit-resolution volumetric boundary, then bounded author-masked smoothing.
 * A shared halo is evaluated before tile ownership. The canonical tile grid stays
 * exact during smoothing and simplification so packed and smooth neighbours agree.
 */
public final class SurfaceExtractor {
    public static final int SMOOTH_PASSES = 4;
    public static final int REQUIRED_HALO = SMOOTH_PASSES + 3;
    private static final int MAX_VERTICES = 32_768, MAX_FACES = 32_768;
    private static final int[][] N = {{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private static final int[][][] C = {
            {{0,0,0},{0,0,1},{0,1,1},{0,1,0}},{{1,0,1},{1,0,0},{1,1,0},{1,1,1}},
            {{0,0,1},{0,0,0},{1,0,0},{1,0,1}},{{0,1,0},{0,1,1},{1,1,1},{1,1,0}},
            {{1,0,0},{0,0,0},{0,1,0},{1,1,0}},{{0,0,1},{1,0,1},{1,1,1},{0,1,1}}};
    private static final class Vertex {
        int x,y,z;
        double dx,dy,dz;
        boolean fixed, border;
        final Set<Integer> neighbours = new HashSet<>();
        Vertex(int x,int y,int z) { this.x=x;this.y=y;this.z=z; }
    }
    private record Face(int[] vertices,int data,boolean owned) {}

    public static SurfaceMesh extract(VoxelSnapshot source, int minX, int minZ, int width, boolean smooth) {
        if (width < 1 || minX < REQUIRED_HALO || minZ < REQUIRED_HALO
                || minX+width+REQUIRED_HALO > source.sizeX() || minZ+width+REQUIRED_HALO > source.sizeZ())
            throw new IllegalArgumentException("Tile requires a complete smoothing halo");
        var vertices = new ArrayList<Vertex>();
        var ids = new HashMap<Integer,Integer>();
        var faces = new ArrayList<Face>();
        // Only this tile's halo is touched. Peak extraction memory does not scale with map area.
        int x0=minX-REQUIRED_HALO, x1=minX+width+REQUIRED_HALO;
        int z0=minZ-REQUIRED_HALO, z1=minZ+width+REQUIRED_HALO;
        for (int y=0;y<source.sizeY();y++) for (int z=z0;z<z1;z++) for (int x=x0;x<x1;x++) {
            int p=source.index(x,y,z), material=source.blocks()[p];
            if (material<=0) continue;
            for (int f=0;f<6;f++) {
                int nx=x+N[f][0],ny=y+N[f][1],nz=z+N[f][2];
                // Unknown is not air. Never invent a wall/cap at an incomplete cache boundary.
                if (source.block(nx,ny,nz)!=0) continue;
                int[] corners=new int[4];
                for (int k=0;k<4;k++) {
                    int vx=x+C[f][k][0],vy=y+C[f][k][1],vz=z+C[f][k][2];
                    int key=vx|(vz<<10)|(vy<<20);
                    Integer id=ids.get(key);
                    if (id==null) {
                        if (vertices.size()>=MAX_VERTICES) throw new IllegalStateException("Proof tile vertex budget exceeded");
                        id=vertices.size();ids.put(key,id);
                        Vertex v=new Vertex(vx,vy,vz);
                        v.border=vx==minX||vx==minX+width||vz==minZ||vz==minZ+width;
                        // Every neighbour, including a packed exact page, uses this exact
                        // lattice contour. Apply the same grid to halo vertices as well.
                        v.fixed=!smooth||Math.floorMod(vx-minX,width)==0||Math.floorMod(vz-minZ,width)==0;
                        // Author protection includes buried neighbours, unknown samples and snapshot edges.
                        for (int dz=-1;dz<=0;dz++) for (int dy=-1;dy<=0;dy++) for (int dx=-1;dx<=0;dx++) {
                            int b=source.block(vx+dx,vy+dy,vz+dz);
                            if (b<0 || (b>0 && source.natural()[source.index(vx+dx,vy+dy,vz+dz)]==0)) v.fixed=true;
                        }
                        vertices.add(v);
                    }
                    corners[k]=id;
                }
                for(int k=0;k<4;k++) {
                    vertices.get(corners[k]).neighbours.add(corners[(k+1)&3]);
                    vertices.get(corners[k]).neighbours.add(corners[(k+3)&3]);
                }
                if (faces.size()>=MAX_FACES) throw new IllegalStateException("Proof tile face budget exceeded");
                int data=(material<<11)|(f<<8)|(source.light()[source.index(nx,ny,nz)]&255);
                faces.add(new Face(corners,data,x>=minX&&x<minX+width&&z>=minZ&&z<minZ+width));
            }
        }
        // Sort in coordinate order, independent of hash codes and tile-local vertex numbering.
        int[][] neighbours=new int[vertices.size()][];
        Comparator<Integer> order=Comparator.comparingInt((Integer id)->vertices.get(id).y)
                .thenComparingInt(id->vertices.get(id).z).thenComparingInt(id->vertices.get(id).x);
        for(int i=0;i<vertices.size();i++) neighbours[i]=vertices.get(i).neighbours.stream().sorted(order).mapToInt(Integer::intValue).toArray();
        for(int pass=0;pass<SMOOTH_PASSES;pass++) {
            double[] offsets=new double[vertices.size()*3];
            for(int i=0;i<vertices.size();i++) {
                Vertex v=vertices.get(i);
                double dx=v.dx,dy=v.dy,dz=v.dz;
                if(!v.fixed&&neighbours[i].length>0) {
                    double ax=0,ay=0,az=0;
                    for(int id:neighbours[i]) {
                        Vertex w=vertices.get(id);
                        ax+=(w.x-v.x)+w.dx;ay+=(w.y-v.y)+w.dy;az+=(w.z-v.z)+w.dz;
                    }
                    double count=neighbours[i].length;
                    dx=dx*.5+ax/count*.5;dy=dy*.5+ay/count*.5;dz=dz*.5+az/count*.5;
                    double length=Math.sqrt(dx*dx+dy*dy+dz*dz);
                    double limit=.85-Math.sqrt(3)/512; // reserve the final quantization error
                    if(length>limit) { dx*=limit/length;dy*=limit/length;dz*=limit/length; }
                }
                offsets[i*3]=dx;offsets[i*3+1]=dy;offsets[i*3+2]=dz;
            }
            for(int i=0;i<vertices.size();i++) {
                Vertex v=vertices.get(i);v.dx=offsets[i*3];v.dy=offsets[i*3+1];v.dz=offsets[i*3+2];
            }
        }
        // One global 1/256 lattice. Keeping source normals at locked borders also prevents a shading seam.
        float[] allPositions=new float[vertices.size()*3],allNormals=new float[allPositions.length];
        for(int i=0;i<vertices.size();i++) {
            Vertex v=vertices.get(i);
            allPositions[i*3]=v.x+quant(v.dx);allPositions[i*3+1]=v.y+quant(v.dy);allPositions[i*3+2]=v.z+quant(v.dz);
        }
        for(Face f:faces) {
            checkOrientation(allPositions,f.vertices[0],f.vertices[1],f.vertices[2],(f.data>>>8)&7);
            checkOrientation(allPositions,f.vertices[0],f.vertices[2],f.vertices[3],(f.data>>>8)&7);
            addNormal(allPositions,allNormals,f.vertices[0],f.vertices[1],f.vertices[2]);
            addNormal(allPositions,allNormals,f.vertices[0],f.vertices[2],f.vertices[3]);
        }
        int[] remap=new int[vertices.size()];Arrays.fill(remap,-1);
        int count=0,faceCount=0;
        for(Face f:faces) if(f.owned) { faceCount++;for(int id:f.vertices) if(remap[id]<0) remap[id]=count++; }
        float[] positions=new float[count*3],normals=new float[count*3];boolean[] locked=new boolean[count];
        for(int i=0;i<vertices.size();i++) if(remap[i]>=0) {
            int to=remap[i];System.arraycopy(allPositions,i*3,positions,to*3,3);
            double length=Math.sqrt(allNormals[i*3]*allNormals[i*3]+allNormals[i*3+1]*allNormals[i*3+1]+allNormals[i*3+2]*allNormals[i*3+2]);
            for(int k=0;k<3;k++) normals[to*3+k]=length>1e-12?(float)(allNormals[i*3+k]/length):(k==1?1:0);
            Vertex v=vertices.get(i);locked[to]=v.border||v.fixed;
        }
        int[] indices=new int[faceCount*6],data=new int[faceCount*2];int cursor=0;
        for(Face f:faces) if(f.owned) {
            int a=remap[f.vertices[0]],b=remap[f.vertices[1]],c=remap[f.vertices[2]],d=remap[f.vertices[3]];
            indices[cursor*3]=a;indices[cursor*3+1]=b;indices[cursor*3+2]=c;data[cursor++]=f.data;
            indices[cursor*3]=a;indices[cursor*3+1]=c;indices[cursor*3+2]=d;data[cursor++]=f.data;
        }
        return new SurfaceMesh(positions,normals,indices,data,locked);
    }

    private static float quant(double value) { return Math.round(value*256)/256f; }
    private static void checkOrientation(float[] p,int a,int b,int c,int face) {
        double x=p[b*3]-p[a*3],y=p[b*3+1]-p[a*3+1],z=p[b*3+2]-p[a*3+2];
        double u=p[c*3]-p[a*3],v=p[c*3+1]-p[a*3+1],w=p[c*3+2]-p[a*3+2];
        if((y*w-z*v)*N[face][0]+(z*u-x*w)*N[face][1]+(x*v-y*u)*N[face][2]<=1e-8)
            throw new IllegalStateException("Proof smoothing reversed a source face; keep the tile exact or revise its author mask");
    }
    private static void addNormal(float[] p,float[] n,int a,int b,int c) {
        double x=p[b*3]-p[a*3],y=p[b*3+1]-p[a*3+1],z=p[b*3+2]-p[a*3+2];
        double u=p[c*3]-p[a*3],v=p[c*3+1]-p[a*3+1],w=p[c*3+2]-p[a*3+2];
        float nx=(float)(y*w-z*v),ny=(float)(z*u-x*w),nz=(float)(x*v-y*u);
        for(int id:new int[]{a,b,c}) { n[id*3]+=nx;n[id*3+1]+=ny;n[id*3+2]+=nz; }
    }
    private SurfaceExtractor() {}
}
