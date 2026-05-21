package io.github.xsirdon.mists.worldgen;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class OceanCarver {

    public static final int SEA_LEVEL = 63;
    public static final int CARVE_FLOOR = 50;

    public static void carveColumnToOcean(ServerWorld world, int x, int z) {
        for (int y = world.getTopY() - 1; y >= CARVE_FLOOR; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (y > SEA_LEVEL) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
            } else {
                world.setBlockState(p, Blocks.WATER.getDefaultState(), 2);
            }
        }
    }

    private OceanCarver() {}
}
