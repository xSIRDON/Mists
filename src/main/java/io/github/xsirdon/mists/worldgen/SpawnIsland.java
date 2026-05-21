package io.github.xsirdon.mists.worldgen;

import io.github.xsirdon.mists.Mists;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class SpawnIsland {

    public static final double SPAWN_ISLAND_RADIUS = 28.0;   // ~4 chunks across
    public static final int    SPAWN_Y = 65;                  // top of island
    public static final int    BASE_Y  = OceanCarver.SEA_LEVEL - 3;

    public static void build(ServerWorld world, long worldSeed) {
        IslandShape shape = new IslandShape(0, 0, SPAWN_ISLAND_RADIUS, worldSeed);
        for (int x = -45; x <= 45; x++) {
            for (int z = -45; z <= 45; z++) {
                if (shape.contains(x, z)) {
                    // Solid stone base, dirt above, grass on top.
                    for (int y = BASE_Y; y <= SPAWN_Y - 1; y++) {
                        world.setBlockState(new BlockPos(x, y, z), Blocks.DIRT.getDefaultState(), 2);
                    }
                    world.setBlockState(new BlockPos(x, SPAWN_Y, z), Blocks.GRASS_BLOCK.getDefaultState(), 2);
                } else {
                    OceanCarver.carveColumnToOcean(world, x, z);
                }
            }
        }
        // Place a few oak trees at deterministic offsets (no animals).
        placeTree(world, -6, SPAWN_Y + 1,  3);
        placeTree(world,  4, SPAWN_Y + 1, -7);
        placeTree(world, 11, SPAWN_Y + 1,  2);
        // Force spawn to a known dry position on top of the island.
        BlockPos spawn = new BlockPos(0, SPAWN_Y + 1, 0);
        world.setSpawnPos(spawn, 0f);
        Mists.LOG.info("Mists: spawn island built at {}", spawn);
    }

    private static void placeTree(ServerWorld world, int x, int y, int z) {
        // Trunk
        for (int i = 0; i < 5; i++)
            world.setBlockState(new BlockPos(x, y + i, z), Blocks.OAK_LOG.getDefaultState(), 2);
        // Leaves (3x3x3 canopy)
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 3; dy <= 5; dy++) {
                    BlockPos p = new BlockPos(x + dx, y + dy, z + dz);
                    if (world.getBlockState(p).isAir() && Math.abs(dx) + Math.abs(dz) + Math.abs(dy - 4) <= 4)
                        world.setBlockState(p, Blocks.OAK_LEAVES.getDefaultState(), 2);
                }
    }

    private SpawnIsland() {}
}
