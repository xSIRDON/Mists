package io.github.xsirdon.mists.worldgen;

import io.github.xsirdon.mists.Mists;
import net.minecraft.block.Block;
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

    /** Radius of the visible ocean around the spawn island. Carve happens out to this radius
     *  in a CIRCLE (not a square) so the player sees a round body of water, not a rectangle. */
    public static final int OCEAN_RING_RADIUS = 80;

    /** Top of the air-clear column. Any natural terrain at or below SKY_CLEAR_Y is replaced
     *  with air on cells inside the island, so spawn isn't buried under a mountain. */
    public static final int SKY_CLEAR_Y = 120;

    public static void build(ServerWorld world, double cx, double cz, long worldSeed) {
        IslandShape shape = new IslandShape(cx, cz, SPAWN_ISLAND_RADIUS, worldSeed);
        // Iterate a square enclosing the ocean disc, but only act on cells inside the disc.
        int xFrom = (int) Math.floor(cx - OCEAN_RING_RADIUS);
        int xTo   = (int) Math.ceil (cx + OCEAN_RING_RADIUS);
        int zFrom = (int) Math.floor(cz - OCEAN_RING_RADIUS);
        int zTo   = (int) Math.ceil (cz + OCEAN_RING_RADIUS);

        double beachThreshold = SPAWN_ISLAND_RADIUS * 0.85;
        double hillThreshold  = SPAWN_ISLAND_RADIUS * 0.4;
        long oceanR2 = (long) OCEAN_RING_RADIUS * OCEAN_RING_RADIUS;
        List<int[]> hillCells = new ArrayList<>();

        for (int x = xFrom; x <= xTo; x++) {
            for (int z = zFrom; z <= zTo; z++) {
                double ddx = x - cx, ddz = z - cz;
                double d2 = ddx * ddx + ddz * ddz;
                if (d2 > oceanR2) continue; // outside the round ocean ring — leave natural terrain

                if (shape.contains(x, z)) {
                    double dist = Math.sqrt(d2);

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

                    // Clear any natural terrain ABOVE the island so spawn isn't buried under
                    // a mountain when the locate-biome fallback put us inland.
                    for (int y = SPAWN_Y + 3; y <= SKY_CLEAR_Y; y++) {
                        world.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState(), 2);
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

        decorate(world, (int) cx, (int) cz, worldSeed);

        Mists.LOG.info("Mists: spawn island built at ({}, {})", (int) cx, (int) cz);
    }

    /**
     * Post-terrain decoration pass: pond, tall grass, flowers, dirt patches.
     * Seeded deterministically from {@code worldSeed ^ (cx * 31 + cz)} so a given seed
     * yields the same scenery on world reloads.
     */
    private static void decorate(ServerWorld world, int cx, int cz, long worldSeed) {
        Random rng = new Random(worldSeed ^ ((long) (cx * 31 + cz)));

        // Collect available grass cells (the pond placement and dirt patches consume from this).
        List<int[]> grass = IslandDecoration.collectGrassSurface(
            world, cx, cz, (int) Math.ceil(SPAWN_ISLAND_RADIUS) + 2, SPAWN_Y);
        if (grass.isEmpty()) return;

        // 1. Single freshwater pond — deterministic position inside the core (≤ R*0.35).
        double coreR = SPAWN_ISLAND_RADIUS * 0.35;
        for (int attempt = 0; attempt < 16; attempt++) {
            double a = rng.nextDouble() * Math.PI * 2;
            double r = rng.nextDouble() * coreR;
            int px = cx + (int) Math.round(Math.cos(a) * r);
            int pz = cz + (int) Math.round(Math.sin(a) * r);
            if (world.getBlockState(new BlockPos(px, SPAWN_Y, pz)).isOf(Blocks.GRASS_BLOCK)) {
                IslandDecoration.carvePond(world, px, pz, SPAWN_Y);
                break;
            }
        }

        // 2. Dirt patches (3-6 of them, 2x2 each).
        int patchCount = 3 + rng.nextInt(4); // 3..6
        for (int i = 0; i < patchCount; i++) {
            int[] c = grass.get(rng.nextInt(grass.size()));
            IslandDecoration.dirtPatch(world, c[0], c[1], SPAWN_Y);
        }

        // Recollect grass cells — pond + dirt patches have shrunk the surface.
        grass = IslandDecoration.collectGrassSurface(
            world, cx, cz, (int) Math.ceil(SPAWN_ISLAND_RADIUS) + 2, SPAWN_Y);

        // 3. Tall grass tufts (4-8).
        Block tallGrass = IslandDecoration.tallGrassBlock();
        int tufts = 4 + rng.nextInt(5); // 4..8
        IslandDecoration.scatterPlants(world, grass, rng, tufts, tallGrass, SPAWN_Y, 1);

        // 4. Dandelions (1-3) + poppies (0-2).
        int dandelions = 1 + rng.nextInt(3); // 1..3
        IslandDecoration.scatterPlants(world, grass, rng, dandelions, Blocks.DANDELION, SPAWN_Y, 4);
        int poppies = rng.nextInt(3); // 0..2
        IslandDecoration.scatterPlants(world, grass, rng, poppies, Blocks.POPPY, SPAWN_Y, 4);
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
