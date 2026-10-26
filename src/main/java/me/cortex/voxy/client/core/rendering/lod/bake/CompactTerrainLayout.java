package me.cortex.voxy.client.core.rendering.lod.bake;

import java.util.*;

/** Published 32-block terrain ABI: 6-byte vertices, exact 1/16 lattice, oct7 normals,
 * restart strips, dictionary-coded primitive attributes. Disk pages remain FVMSH003.
 * Geometry must be quantized BEFORE the independent error audit and QEM validation.
 */
public final class CompactTerrainLayout {
    public record Layout(short[] vertices,int[] indices,int indexBytes,int[] attributes,int dictionarySize,int attributeBits,int triangles){
        public long gpuBytes(){return vertices.length*2L+indices.length*(long)indexBytes+attributes.length*4L;}
    }
    public static Layout build(SurfaceMesh mesh){return build(mesh,0,0);}
    public static Layout build(SurfaceMesh mesh,int offsetX,int offsetZ){
        short[] packed=new short[mesh.vertices()*3];
        for(int i=0;i<mesh.vertices();i++){
            int[] c=new int[3];int[] max={1023,8191,1023};
            for(int a=0;a<3;a++){double value=(mesh.positions()[i*3+a]-(a==0?offsetX:a==2?offsetZ:0)+1)*16;c[a]=(int)Math.rint(value);if(value!=c[a]||c[a]<0||c[a]>max[a])throw new IllegalArgumentException("Compact terrain coordinate must be on the audited 1/16 lattice");}
            double x=mesh.normals()[i*3],y=mesh.normals()[i*3+1],z=mesh.normals()[i*3+2],sum=Math.abs(x)+Math.abs(y)+Math.abs(z);
            if(sum<1e-20){x=0;y=1;z=0;sum=1;}x/=sum;y/=sum;z/=sum;
            if(z<0){double old=x;x=(1-Math.abs(y))*(old>=0?1:-1);y=(1-Math.abs(old))*(y>=0?1:-1);}
            int nx=(int)Math.round((x*.5+.5)*127),ny=(int)Math.round((y*.5+.5)*127);
            long q=c[0]|((long)c[1]<<10)|((long)c[2]<<23)|((long)nx<<33)|((long)ny<<40);
            for(int k=0;k<3;k++)packed[i*3+k]=(short)(q>>>(k*16));
        }
        var edges=new HashMap<Long,ArrayList<Integer>>();int[] tris=mesh.indices();
        for(int t=0;t<mesh.triangles();t++)for(int k=0;k<3;k++)edges.computeIfAbsent(edge(tris[t*3+k],tris[t*3+(k+1)%3]),key->new ArrayList<>()).add(t);
        boolean[] used=new boolean[mesh.triangles()];var out=new ArrayList<Integer>();var attributes=new ArrayList<Integer>();
        for(int seed=0;seed<used.length;seed++)if(!used[seed]){
            Path best=null;
            for(int rotation=0;rotation<3;rotation++){
                var path=path(mesh,edges,used,seed,rotation);
                if(best==null||path.faces.size()>best.faces.size())best=path;
            }
            if(!out.isEmpty())out.add(-1);out.addAll(best.vertices);
            for(int face:best.faces){if(used[face])throw new IllegalStateException("Duplicate strip primitive");used[face]=true;attributes.add(mesh.faceData()[face]);}
        }
        var dictionary=new TreeSet<>(attributes);int[] values=dictionary.stream().mapToInt(Integer::intValue).toArray();
        if(values.length>65536)throw new IllegalArgumentException("Attribute dictionary budget");
        var ids=new HashMap<Integer,Integer>();for(int i=0;i<values.length;i++)ids.put(values[i],i);
        int bits=values.length<=256?8:16,per=32/bits;int[] words=Arrays.copyOf(values,values.length+(attributes.size()+per-1)/per);
        for(int i=0;i<attributes.size();i++)words[values.length+i/per]|=ids.get(attributes.get(i))<<((i%per)*bits);
        return new Layout(packed,out.stream().mapToInt(Integer::intValue).toArray(),mesh.vertices()<65536?2:4,words,values.length,bits,attributes.size());
    }
    private record Path(ArrayList<Integer> vertices,ArrayList<Integer> faces){}
    private static Path path(SurfaceMesh mesh,Map<Long,ArrayList<Integer>> edges,boolean[] used,int seed,int rotation){
        var vertices=new ArrayList<Integer>();var faces=new ArrayList<Integer>();var local=new HashSet<Integer>();
        for(int k=0;k<3;k++)vertices.add(mesh.indices()[seed*3+(rotation+k)%3]);faces.add(seed);local.add(seed);
        while(true){
            int length=vertices.size(),a=vertices.get(length-2),b=vertices.get(length-1),next=-1,corner=-1;
            // The next strip triangle has alternating winding. Match the source's cyclic order.
            if((faces.size()&1)!=0){int swap=a;a=b;b=swap;}
            var neighbours=edges.get(edge(a,b));
            if(neighbours!=null)for(int t:neighbours)if(!used[t]&&!local.contains(t))for(int k=0;k<3;k++)if(mesh.indices()[t*3+k]==a&&mesh.indices()[t*3+(k+1)%3]==b){next=t;corner=mesh.indices()[t*3+(k+2)%3];break;}
            if(next<0)break;vertices.add(corner);faces.add(next);local.add(next);
        }
        return new Path(vertices,faces);
    }
    private static long edge(int a,int b){return ((long)Math.min(a,b)<<32)|Math.max(a,b);}
    public static int attribute(Layout layout,int primitive){int per=32/layout.attributeBits,mask=layout.attributeBits==8?255:65535;int id=(layout.attributes[layout.dictionarySize+primitive/per]>>>((primitive%per)*layout.attributeBits))&mask;return layout.attributes[id];}
    private CompactTerrainLayout(){}
}
