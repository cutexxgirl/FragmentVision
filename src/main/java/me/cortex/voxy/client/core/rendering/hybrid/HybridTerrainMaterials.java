package me.cortex.voxy.client.core.rendering.hybrid;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Set;

/** Deliberately narrow WIP allowlist. Unknown/modded/constructed materials keep their voxel mesh. */
public final class HybridTerrainMaterials {
    private static final Set<Block> NATURAL = Set.of(Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE,
            Blocks.ANDESITE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.CALCITE, Blocks.DIRT,
            Blocks.COARSE_DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM,
            Blocks.MOSS_BLOCK, Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL,
            Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.SNOW_BLOCK, Blocks.SNOW,
            Blocks.POWDER_SNOW, Blocks.PACKED_ICE, Blocks.TERRACOTTA);
    public static boolean natural(BlockState state) {
        return NATURAL.contains(state.getBlock()) && state.getFluidState().isEmpty();
    }
    private HybridTerrainMaterials() {}
}
