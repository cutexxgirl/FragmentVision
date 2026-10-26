package me.cortex.voxy.client.core.rendering.lod.bake;

import java.util.*;

/** Remove small vegetation before visibility extraction, never merely discard its fragments.
 * Leaves, wood and structures remain. Saved inputs stay immutable; aquatic plants leave water.
 */
public final class LodInputPolicy {
    private static final Set<String> SMALL=Set.of(("short_grass tall_grass fern large_fern dead_bush " +
        "dandelion poppy blue_orchid allium azure_bluet red_tulip orange_tulip white_tulip pink_tulip " +
        "oxeye_daisy cornflower lily_of_the_valley wither_rose sunflower lilac rose_bush peony " +
        "torchflower pitcher_plant pink_petals brown_mushroom red_mushroom seagrass tall_seagrass kelp kelp_plant bubble_column lily_pad sugar_cane vine glow_lichen sculk_vein moss_carpet hanging_roots spore_blossom azalea flowering_azalea sweet_berry_bush bamboo bamboo_sapling oak_sapling spruce_sapling birch_sapling jungle_sapling acacia_sapling dark_oak_sapling cherry_sapling mangrove_propagule wheat carrots potatoes beetroots nether_wart cocoa pumpkin_stem attached_pumpkin_stem melon_stem attached_melon_stem twisting_vines twisting_vines_plant weeping_vines weeping_vines_plant").split(" "));
    private static final Set<String> TERRAIN=Set.of(("stone granite diorite andesite deepslate tuff calcite dirt coarse_dirt " +
        "grass_block podzol mycelium moss_block sand red_sand gravel sandstone red_sandstone snow_block terracotta").split(" "));
    public static String name(String state){int bracket=state.indexOf('[');return bracket<0?state:state.substring(0,bracket);}
    public static boolean small(String state){String n=name(state);return n.startsWith("minecraft:")&&SMALL.contains(n.substring(10));}
    public static boolean terrain(String state){String n=name(state);return n.startsWith("minecraft:")&&TERRAIN.contains(n.substring(10));}
    public static VoxelSnapshot clean(VoxelSnapshot s){
        var states=new ArrayList<>(Arrays.asList(s.states()));var biomes=new ArrayList<>(Arrays.asList(s.biomes()));
        int[] remap=new int[s.states().length];boolean changed=false;
        for(int i=0;i<remap.length;i++){
            remap[i]=i;if(!small(s.states()[i]))continue;changed=true;
            String n=name(s.states()[i]);boolean wet=n.endsWith("seagrass")||n.equals("minecraft:kelp")||n.equals("minecraft:kelp_plant")||n.equals("minecraft:bubble_column")||s.states()[i].contains("waterlogged=true");
            remap[i]=0;
            if(wet){int id=-1;for(int j=0;j<states.size();j++)if(states.get(j).equals("minecraft:water[level=0]")&&biomes.get(j).equals(s.biomes()[i])){id=j;break;}
                if(id<0){if(states.size()>=65536)throw new IllegalArgumentException("Water palette budget");id=states.size();states.add("minecraft:water[level=0]");biomes.add(s.biomes()[i]);}remap[i]=id;}
        }
        if(!changed)return s;
        int[] blocks=s.blocks().clone();byte[] natural=s.natural().clone();
        for(int i=0;i<blocks.length;i++)if(blocks[i]>0&&remap[blocks[i]]!=blocks[i]){blocks[i]=remap[blocks[i]];natural[i]=0;}
        return new VoxelSnapshot(s.originX(),s.originY(),s.originZ(),s.sizeX(),s.sizeY(),s.sizeZ(),states.toArray(String[]::new),biomes.toArray(String[]::new),blocks,s.light(),natural);
    }
    /** A continuous ground surface beneath foliage and fluids, rather than thousands of
     * holes cut by individual leaf blocks. Structures remain and continue to pin interfaces. */
    public static byte[] projectableSnow(VoxelSnapshot s){
        boolean[] snow=new boolean[s.states().length],ground=new boolean[s.states().length];for(int i=0;i<snow.length;i++){snow[i]=TerrainShapes.snow(s.states()[i]);ground[i]=terrain(s.states()[i]);}
        byte[] mask=new byte[s.blocks().length];int stride=s.sizeX()*s.sizeZ();
        for(int i=stride;i<mask.length;i++){int id=s.blocks()[i],below=s.blocks()[i-stride];if(id>0&&snow[id]&&below>0&&ground[below]&&s.natural()[i-stride]!=0)mask[i]=1;}
        return mask;
    }
    public static VoxelSnapshot terrainContext(VoxelSnapshot s){return terrainContext(s,projectableSnow(s));}
    public static VoxelSnapshot terrainContext(VoxelSnapshot s,byte[] snow){
        boolean[] transparent=new boolean[s.states().length];
        for(int i=1;i<transparent.length;i++){String n=name(s.states()[i]);transparent[i]=n.endsWith("_leaves")||n.equals("minecraft:water");}
        int[] blocks=s.blocks().clone();byte[] natural=s.natural().clone();
        for(int i=0;i<blocks.length;i++)if(blocks[i]>0&&(transparent[blocks[i]]||snow[i]!=0)){blocks[i]=0;natural[i]=0;}
        return new VoxelSnapshot(s.originX(),s.originY(),s.originZ(),s.sizeX(),s.sizeY(),s.sizeZ(),s.states(),s.biomes(),blocks,s.light(),natural);
    }
    private LodInputPolicy(){}
}
