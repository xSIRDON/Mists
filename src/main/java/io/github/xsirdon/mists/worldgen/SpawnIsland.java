package io.github.xsirdon.mists.worldgen;

import io.github.xsirdon.mists.Mists;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class SpawnIsland {

    public static final double SPAWN_ISLAND_RADIUS = 28.0;   // ~4 chunks across
    public static final int    SPAWN_Y = 65;                  // top of island
    public static final int    BASE_Y  = 60;

    public static void build(ServerWorld world, double cx, double cz, long worldSeed) {
        IslandShape shape = new IslandShape(cx, cz, SPAWN_ISLAND_RADIUS, worldSeed);
        int xFrom = (int) Math.floor(cx - 45);
        int xTo   = (int) Math.ceil (cx + 45);
        int zFrom = (int) Math.floor(cz - 45);
        int zTo   = (int) Math.ceil (cz + 45);

        double beachThreshold = SPAWN_ISLAND_RADIUS * 0.85;
        double hillThreshold  = SPAWN_ISLAND_RADIUS * 0.4;
        List<int[]> hillCells = new ArrayList<>();

        for (int x = xFrom; x <= xTo; x++) {
            for (int z = zFrom; z <= zTo; z++) {
                if (shape.contains(x, z)) {
                    double ddx = x - cx, ddz = z - cz;
                    double dist = Math.sqrt(ddx * ddx + ddz * ddz);

                    // Fill column with dirt up to SPAWN_Y - 1
                    for (int y = BASE_Y; y <= SPAWN_Y - 1; y++) {
                        world.setBlockState(new BlockPos(x, y, z), Blocks.DIRT.getDefaultState(), 2);
                    }

                    if (dist > beachThreshold) {
                        // Sand beach band on the outer ring.
                        world.setBlockState(new BlockPos(x, SPAWN_Y, z), Blocks.SAND.getDefaultState(), 2);
                    } else {
                        world.setBlockState(new BlockPos(x, SPAWN_Y, z), Blocks.GRASS_BLOCK.getDefaultState(), 2);

                        if (dist < hillThreshold) {
                            // Add 1 extra grass block above SPAWN_Y, then a second with 30% probability.
                            world.setBlockState(new BlockPos(x, SPAWN_Y + 1, z),
                                Blocks.GRASS_BLOCK.getDefaultState(), 2);
                            long cellSeed = worldSeed ^ (((long) x << 32) | (z & 0xFFFFFFFFL));
                            Random cellRng = new Random(cellSeed);
                            if (cellRng.nextDouble() < 0.30) {
                                world.setBlockState(new BlockPos(x, SPAWN_Y + 2, z),
                                    Blocks.GRASS_BLOCK.getDefaultState(), 2);
                            }
                            hillCells.add(new int[]{ x, z });
                        }
                    }
                } else {
                    OceanCarver.carveColumnToOcean(world, x, z);
                }
            }
        }

        // Tree placement: 4-6 oaks scattered within the central grass area.
        Random treeRng = new Random(worldSeed ^ 0x7E5_5L);
        int treeCount = 4 + treeRng.nextInt(3); // 4, 5, or 6
        if (!hillCells.isEmpty()) {
            int attempts = 0;
            int placed = 0;
            List<int[]> placedAt = new ArrayList<>();
            while (placed < treeCount && attempts < treeCount * 20) {
                attempts++;
                int[] cell = hillCells.get(treeRng.nextInt(hillCells.size()));
                int tx = cell[0], tz = cell[1];
                // Min 4-block spacing from already placed trees.
                boolean tooClose = false;
                for (int[] pp : placedAt) {
                    int dxp = pp[0] - tx, dzp = pp[1] - tz;
                    if (dxp * dxp + dzp * dzp < 16) { tooClose = true; break; }
                }
                if (tooClose) continue;
                int topY = topGrassY(world, tx, tz);
                placeTree(world, tx, topY + 1, tz);
                placedAt.add(new int[]{ tx, tz });
                placed++;
            }
        }

        Mists.LOG.info("Mists: spawn island built at ({}, {})", (int) cx, (int) cz);
    }

    private static int topGrassY(ServerWorld world, int x, int z) {
        for (int y = SPAWN_Y + 3; y >= SPAWN_Y; y--) {
            if (world.getBlockState(new BlockPos(x, y, z)).isOf(Blocks.GRASS_BLOCK)) {
                return y;
            }
        }
        return SPAWN_Y;
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
