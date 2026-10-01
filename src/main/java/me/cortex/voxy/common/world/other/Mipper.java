package me.cortex.voxy.common.world.other;

//Mipper for data
public class Mipper {
    //TODO: compute the opacity of the block then mip w.r.t those blocks
    // as distant horizons done


    //TODO: also pass in the level its mipping from, cause at lower levels you want to preserve block details
    // but at higher details you want more air



    //TODO: instead of opacity only, add a level to see if the visual bounding box allows for seeing through top down etc
    public static long mip(long I000, long I100, long I001, long I101,
                           long I010, long I110, long I011, long I111,
                          Mapper mapper) {
        //TODO: do a stable sort on all the entires, w.r.t the opacity and maybe light as a secondary???
        // then select the highest value
        // UPDATE, dumbass, the highest value _is_ the max/min



        int max = -1;

        //TODO: mip with respect to all the variables, what that means is take whatever has the highest count and return that
        //TODO: also average out the light level and set that as the new light level
        //For now just take the most top corner

        //TODO: i think it needs to compute the _max_ light level, since e.g. if a point is bright irl
        // you can see it from really really damn far away.
        // it could be a heavily weighted average with a huge preference to the top most lighting value
        if (!Mapper.isAir(I111)) {
            max = (mapper.getBlockStateOpacity(I111)<<4)|0b111;
        }
        if (!Mapper.isAir(I110)) {
            max = Math.max((mapper.getBlockStateOpacity(I110)<<4)|0b110, max);
        }
        if (!Mapper.isAir(I011)) {
            max = Math.max((mapper.getBlockStateOpacity(I011)<<4)|0b011, max);
        }
        if (!Mapper.isAir(I010)) {
            max = Math.max((mapper.getBlockStateOpacity(I010)<<4)|0b010, max);
        }
        if (!Mapper.isAir(I101)) {
            max = Math.max((mapper.getBlockStateOpacity(I101)<<4)|0b101, max);
        }
        if (!Mapper.isAir(I100)) {
            max = Math.max((mapper.getBlockStateOpacity(I100)<<4)|0b100, max);
        }
        if (!Mapper.isAir(I001)) {
            max = Math.max((mapper.getBlockStateOpacity(I001)<<4)|0b001, max);
        }
        if (!Mapper.isAir(I000)) {
            max = Math.max((mapper.getBlockStateOpacity(I000)<<4), max);
        }

        if (max != -1) {
            int childIndex = max&0b111;
            long selected = switch (childIndex) {
                case 0 -> I000;
                case 1 -> I001;
                case 2 -> I010;
                case 3 -> I011;
                case 4 -> I100;
                case 5 -> I101;
                case 6 -> I110;
                case 7 -> I111;
                default -> throw new IllegalStateException("Unexpected value: " + childIndex);
            };
            long result = Mapper.withMippedChild(selected, childIndex, isMixed(I000, I100, I001, I101, I010, I110, I011, I111));
            result = Mapper.withBlockBiome(result, Mapper.getBlockId(result),
                    selectRepresentativeBiome(selected, I000, I100, I001, I101, I010, I110, I011, I111));
            return Mapper.withLight(result, averageLight(I000, I100, I001, I101, I010, I110, I011, I111));
        } else {
            return Mapper.withLight(I111, averageLight(I000, I100, I001, I101, I010, I110, I011, I111));
        }
    }

    private static int averageLight(long I000, long I100, long I001, long I101,
                                    long I010, long I110, long I011, long I111) {
        int blockLight = (Mapper.getLightId(I000) & 0xF0) + (Mapper.getLightId(I001) & 0xF0) + (Mapper.getLightId(I010) & 0xF0) + (Mapper.getLightId(I011) & 0xF0) +
                (Mapper.getLightId(I100) & 0xF0) + (Mapper.getLightId(I101) & 0xF0) + (Mapper.getLightId(I110) & 0xF0) + (Mapper.getLightId(I111) & 0xF0);
        int skyLight = (Mapper.getLightId(I000) & 0x0F) + (Mapper.getLightId(I001) & 0x0F) + (Mapper.getLightId(I010) & 0x0F) + (Mapper.getLightId(I011) & 0x0F) +
                (Mapper.getLightId(I100) & 0x0F) + (Mapper.getLightId(I101) & 0x0F) + (Mapper.getLightId(I110) & 0x0F) + (Mapper.getLightId(I111) & 0x0F);
        blockLight = (blockLight / 8) & 0xF0;
        skyLight = (skyLight + 7) / 8;
        return blockLight | skyLight;
    }

    private static int selectRepresentativeBiome(long selected,
                                                 long I000, long I100, long I001, long I101,
                                                 long I010, long I110, long I011, long I111) {
        int selectedBiome = Mapper.getBiomeId(selected);
        long best = packBiomeCandidate(selectedBiome, countBiome(selectedBiome, I000, I100, I001, I101, I010, I110, I011, I111));

        best = chooseBiome(best, selectedBiome, I000, I000, I100, I001, I101, I010, I110, I011, I111);
        best = chooseBiome(best, selectedBiome, I100, I000, I100, I001, I101, I010, I110, I011, I111);
        best = chooseBiome(best, selectedBiome, I001, I000, I100, I001, I101, I010, I110, I011, I111);
        best = chooseBiome(best, selectedBiome, I101, I000, I100, I001, I101, I010, I110, I011, I111);
        best = chooseBiome(best, selectedBiome, I010, I000, I100, I001, I101, I010, I110, I011, I111);
        best = chooseBiome(best, selectedBiome, I110, I000, I100, I001, I101, I010, I110, I011, I111);
        best = chooseBiome(best, selectedBiome, I011, I000, I100, I001, I101, I010, I110, I011, I111);
        best = chooseBiome(best, selectedBiome, I111, I000, I100, I001, I101, I010, I110, I011, I111);
        return unpackBiome(best);
    }

    private static long chooseBiome(long best, int selectedBiome, long candidate,
                                    long I000, long I100, long I001, long I101,
                                    long I010, long I110, long I011, long I111) {
        if (Mapper.isAir(candidate)) {
            return best;
        }
        int bestBiome = unpackBiome(best);
        int candidateBiome = Mapper.getBiomeId(candidate);
        if (candidateBiome == bestBiome) {
            return best;
        }
        int bestCount = unpackBiomeCount(best);
        int candidateCount = countBiome(candidateBiome, I000, I100, I001, I101, I010, I110, I011, I111);
        if (candidateCount > bestCount) {
            return packBiomeCandidate(candidateBiome, candidateCount);
        }
        if (candidateCount == bestCount && candidateBiome == selectedBiome) {
            return packBiomeCandidate(selectedBiome, candidateCount);
        }
        return best;
    }

    private static long packBiomeCandidate(int biome, int count) {
        return ((long) count << 32) | Integer.toUnsignedLong(biome);
    }

    private static int unpackBiome(long candidate) {
        return (int) candidate;
    }

    private static int unpackBiomeCount(long candidate) {
        return (int) (candidate >>> 32);
    }

    private static int countBiome(int biome, long I000, long I100, long I001, long I101,
                                  long I010, long I110, long I011, long I111) {
        int count = 0;
        count += (!Mapper.isAir(I000) && Mapper.getBiomeId(I000) == biome) ? 1 : 0;
        count += (!Mapper.isAir(I100) && Mapper.getBiomeId(I100) == biome) ? 1 : 0;
        count += (!Mapper.isAir(I001) && Mapper.getBiomeId(I001) == biome) ? 1 : 0;
        count += (!Mapper.isAir(I101) && Mapper.getBiomeId(I101) == biome) ? 1 : 0;
        count += (!Mapper.isAir(I010) && Mapper.getBiomeId(I010) == biome) ? 1 : 0;
        count += (!Mapper.isAir(I110) && Mapper.getBiomeId(I110) == biome) ? 1 : 0;
        count += (!Mapper.isAir(I011) && Mapper.getBiomeId(I011) == biome) ? 1 : 0;
        count += (!Mapper.isAir(I111) && Mapper.getBiomeId(I111) == biome) ? 1 : 0;
        return count;
    }

    private static boolean isMixed(long I000, long I100, long I001, long I101,
                                   long I010, long I110, long I011, long I111) {
        int first = Mapper.getBlockId(I000);
        int firstBiome = Mapper.getBiomeId(I000);
        return Mapper.getBlockId(I100) != first || Mapper.getBiomeId(I100) != firstBiome ||
                Mapper.getBlockId(I001) != first || Mapper.getBiomeId(I001) != firstBiome ||
                Mapper.getBlockId(I101) != first || Mapper.getBiomeId(I101) != firstBiome ||
                Mapper.getBlockId(I010) != first || Mapper.getBiomeId(I010) != firstBiome ||
                Mapper.getBlockId(I110) != first || Mapper.getBiomeId(I110) != firstBiome ||
                Mapper.getBlockId(I011) != first || Mapper.getBiomeId(I011) != firstBiome ||
                Mapper.getBlockId(I111) != first || Mapper.getBiomeId(I111) != firstBiome;
    }
}
