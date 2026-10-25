import me.cortex.voxy.client.core.rendering.lod.bake.*;

/** Simulation-only leaf states and saved lighting must not freeze a visual surface. */
public final class SurfaceDomainsCheck {
    public static void main(String[] args){
        for(boolean distinctSpecies:new boolean[]{false,true}){
            var base=LodComparisonCheck.fixture(1);
            var s=new VoxelSnapshot(base.originX(),base.originY(),base.originZ(),base.sizeX(),base.sizeY(),base.sizeZ(),
                new String[]{"minecraft:air","minecraft:oak_leaves[distance=1,persistent=false]",distinctSpecies?"minecraft:birch_leaves[distance=2,persistent=false]":"minecraft:oak_leaves[distance=2,persistent=true]"},
                new String[]{"minecraft:plains","minecraft:plains","minecraft:plains"},base.blocks(),base.light(),base.natural());
            int sx=s.sizeX(),sy=s.sizeY(),sz=s.sizeZ();
            for(int z=0;z<sz;z++)for(int x=0;x<sx;x++)for(int y=0;y<sy;y++){
                int i=s.index(x,y,z);if(s.blocks()[i]>0)s.blocks()[i]=x<23?1:2;
                s.light()[i]=(byte)(((x+y+z)%16)<<4);
            }
            var mesh=ConformingSurface.extract(s,7,7,32,GreedyQuadMesher.build(s,7,7,32).selected());
            int interior=0,locked=0;
            for(int v=0;v<mesh.vertices();v++){
                float x=mesh.positions()[v*3],z=mesh.positions()[v*3+2];
                if(x>7&&x<39&&z>7&&z<39){interior++;if(mesh.locked()[v])locked++;}
            }
            if(interior==0||distinctSpecies&&locked==0||!distinctSpecies&&locked!=0)throw new AssertionError("Visual-domain lock policy "+distinctSpecies+" locked="+locked);
        }
        var s=LodComparisonCheck.fixture(1);var baked=TileBake.build(s,7,7,32,true);
        for(int l=0;l<3;l++)if(baked.choices()[l].isMesh()){
            if(baked.choices()[l].triangles()>baked.exact().selected().triangles()*.75||baked.choices()[l].payloadBytes()>baked.exact().selected().payloadBytes()*2||!baked.errors()[l].withinLimit())throw new AssertionError("Triangle-priority gates");
        }
        System.out.println("Visual surface domains and explicit triangle/memory trade: passed");
    }
}
