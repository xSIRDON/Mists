package io.github.xsirdon.mists.worldgen;

import io.github.xsirdon.mists.Mists;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds a small, natural-looking spawn island that BLENDS into an existing ocean.
 *
 * <p>This is a v0.8 rewrite. Previous versions carved a synthetic ocean disc around the
 * spawn point and pasted a dirt-and-grass island into the middle, which gave a visible
 * "took a chunk out of the land" effect. We now require the caller to have located a
 * genuinely open ocean (see {@link IslandPlacer#findOpenOcean}) and do no carving at all —
 * the natural ocean stays untouched.
 *
 * <p>Layer stack (per cell inside the island shape):
 * <pre>
 *   y = topY              grass (interior) or sand (beach band)
 *   y = topY-1 .. seaLevel+1   dirt
 *   y = seaLevel          sand   (waterline beach)
 *   y = seaLevel-1 .. oceanFloor+1   sand (underwater shelf)
 *   y &lt;= oceanFloor          natural ocean floor — untouched
 * </pre>
 *
 * <p>The island height is determined by a smooth profile (taller near centre, lower at
 * the edges) plus per-cell noise. Edges naturally taper to the waterline and become
 * beach cells. The underwater shelf reaches down to the existing ocean floor so the
 * island looks like it has roots, not like it was dropped on the water.
 */
public final class SpawnIsland {

    public static final double SPAWN_ISLAND_RADIUS = 28.0;   // ~4 chunks across

    /** Maximum island height above sea level for the central core. */
    private static final int MAX_HEIGHT_ABOVE_SEA = 4;

    /** How deep the underwater sand shelf may extend (search depth). */
    private static final int UNDERWATER_SHELF_PROBE = 12;

    /** Legacy constants. Internally the v0.8 build path uses dynamic sea level; these are kept
     *  for code that still references them (ring-island placement, the player-join rescue
     *  fallback). They assume vanilla sea level 63 and are safe approximations. */
    public static final int SPAWN_Y = 65;
    public static final int BASE_Y  = 60;

    /**
     * @param world     server world the island is being built into
     * @param cx        island centre X (must be in open ocean — caller's responsibility)
     * @param cz        island centre Z
     * @param worldSeed world seed (used for deterministic per-cell variation)
     */
    public static void build(ServerWorld world, double cx, double cz, long worldSeed) {
        int seaLevel = world.getSeaLevel();
        IslandShape shape = new IslandShape(cx, cz, SPAWN_ISLAND_RADIUS, worldSeed);

        int xFrom = (int) Math.floor(cx - SPAWN_ISLAND_RADIUS - 4);
        int xTo   = (int) Math.ceil (cx + SPAWN_ISLAND_RADIUS + 4);
        int zFrom = (int) Math.floor(cz - SPAWN_ISLAND_RADIUS - 4);
        int zTo   = (int) Math.ceil (cz + SPAWN_ISLAND_RADIUS + 4);

        List<int[]> grassCells = new ArrayList<>();
        List<int[]> coreCells  = new ArrayList<>();
        int spawnTopY = seaLevel + MAX_HEIGHT_ABOVE_SEA; // updated to actual top at (cx, cz)

        for (int x = xFrom; x <= xTo; x++) {
            for (int z = zFrom; z <= zTo; z++) {
                if (!shape.contains(x, z)) continue;

                int topY = computeTopY(x, z, cx, cz, seaLevel, worldSeed);
                if (topY < seaLevel) continue; // island falls below water here, treat as natural ocean
                if (x == (int) cx && z == (int) cz) spawnTopY = topY;

                boolean isBeach = topY <= seaLevel; // top sits at the waterline

                // Underwater sand shelf — find the natural floor and fill upward with sand.
                int floor = findOceanFloor(world, x, z, seaLevel);
                for (int y = floor + 1; y < seaLevel; y++) {
                    setIfDifferent(world, x, y, z, Blocks.SAND.getDefaultState());
                }

                // Sand at the waterline.
                setIfDifferent(world, x, seaLevel, z, Blocks.SAND.getDefaultState());

                // Dirt body above water (skipped on beach cells where topY == seaLevel).
                for (int y = seaLevel + 1; y < topY; y++) {
                    setIfDifferent(world, x, y, z, Blocks.DIRT.getDefaultState());
                }

                // Surface cap.
                BlockState cap = isBeach ? Blocks.SAND.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState();
                setIfDifferent(world, x, topY, z, cap);

                // Clear any natural air-above (no-op in true ocean; defensive if the
                // caller mis-located on coastline land).
                for (int y = topY + 1; y <= seaLevel + MAX_HEIGHT_ABOVE_SEA + 8; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!world.getBlockState(p).isAir()) {
                        world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                    }
                }

                if (!isBeach) {
                    grassCells.add(new int[]{ x, z, topY });
                    if (topY >= seaLevel + 2) coreCells.add(new int[]{ x, z, topY });
                }
            }
        }

        // Force the world spawn block onto a known-safe grass square at the island centre.
        // (IslandPlacer also calls setSpawnPos, but anchoring the y here matches the
        // actual built top so vanilla "find safe spawn" doesn't search outward.)
        BlockPos spawnPos = new BlockPos((int) cx, spawnTopY + 1, (int) cz);
        world.setSpawnPos(spawnPos, 0f);

        // Trees: 4–6 scattered in the core grass area, min 4-block spacing.
        placeTrees(world, coreCells, worldSeed);

        // Decoration: tall grass, flowers, dirt patches, optional pond.
        decorate(world, (int) cx, (int) cz, worldSeed, seaLevel);

        Mists.LOG.info("Mists: spawn island built at ({}, ~{}, {}) with sea level {}",
            (int) cx, spawnTopY, (int) cz, seaLevel);
    }

    /**
     * Smooth height profile: centre rises to MAX_HEIGHT_ABOVE_SEA, edges taper to the waterline.
     * Adds seeded per-cell noise so the surface looks organic instead of like a parabolic bowl.
     */
    private static int computeTopY(int x, int z, double cx, double cz, int seaLevel, long worldSeed) {
        double ddx = x - cx, ddz = z - cz;
        double dist = Math.sqrt(ddx * ddx + ddz * ddz);
        double normalized = Math.min(1.0, dist / SPAWN_ISLAND_RADIUS);

        // Smoothstep falloff: high at centre, gradual taper to zero at the edge.
        double s = 1.0 - normalized;
        double smoothed = s * s * (3.0 - 2.0 * s);

        // Base height in blocks above sea level.
        double base = smoothed * MAX_HEIGHT_ABOVE_SEA;

        // Per-cell deterministic noise: ±1.5 blocks of bump variation.
        long cellSeed = worldSeed ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0xBF58476D1CE4E5B9L);
        Random cellRng = new Random(cellSeed);
        double noise = (cellRng.nextDouble() - 0.5) * 3.0;

        double h = base + noise;
        // Clamp: never below sea level (that would be ocean) and never above MAX+2.
        if (h < -0.5) return seaLevel - 1; // below water, treat as natural ocean
        int height = (int) Math.round(Math.max(0, Math.min(MAX_HEIGHT_ABOVE_SEA + 1, h)));
        return seaLevel + height;
    }

    /**
     * Walk down from {@code seaLevel - 1} looking for the first non-water, non-air block.
     * If we don't find one within {@link #UNDERWATER_SHELF_PROBE} blocks, return a
     * reasonable default to avoid digging arbitrarily deep.
     */
    private static int findOceanFloor(ServerWorld world, int x, int z, int seaLevel) {
        for (int y = seaLevel - 1; y >= seaLevel - UNDERWATER_SHELF_PROBE; y--) {
            BlockState bs = world.getBlockState(new BlockPos(x, y, z));
            if (!bs.isAir() && !bs.isOf(Blocks.WATER)) {
                return y;
            }
        }
        return seaLevel - UNDERWATER_SHELF_PROBE;
    }

    /** Avoid noisy block updates by only writing when the target differs. */
    private static void setIfDifferent(ServerWorld world, int x, int y, int z, BlockState desired) {
        BlockPos p = new BlockPos(x, y, z);
        if (!world.getBlockState(p).equals(desired)) {
            world.setBlockState(p, desired, 2);
        }
    }

    private static void placeTrees(ServerWorld world, List<int[]> coreCells, long worldSeed) {
        if (coreCells.isEmpty()) return;
        Random treeRng = new Random(worldSeed ^ 0x7E_E5L);
        int target = 4 + treeRng.nextInt(3); // 4–6
        int attempts = 0;
        int placed = 0;
        List<int[]> placedAt = new ArrayList<>();
        while (placed < target && attempts < target * 30) {
            attempts++;
            int[] c = coreCells.get(treeRng.nextInt(coreCells.size()));
            int tx = c[0], tz = c[1], ty = c[2];
            boolean tooClose = false;
            for (int[] pp : placedAt) {
                int ddx = pp[0] - tx, ddz = pp[1] - tz;
                if (ddx * ddx + ddz * ddz < 25) { tooClose = true; break; } // 5-block min spacing
            }
            if (tooClose) continue;
            placeOakTree(world, tx, ty + 1, tz, treeRng);
            placedAt.add(new int[]{ tx, tz });
            placed++;
        }
    }

    private static void placeOakTree(ServerWorld world, int x, int yBase, int z, Random rng) {
        int height = 4 + rng.nextInt(2); // 4-5 trunk
        for (int i = 0; i < height; i++) {
            world.setBlockState(new BlockPos(x, yBase + i, z), Blocks.OAK_LOG.getDefaultState(), 2);
        }
        int leafTop = yBase + height;
        for (int dy = -1; dy <= 1; dy++) {
            int radius = (dy == 1) ? 1 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0 && dy <= 0) continue;
                    int dist2 = dx * dx + dz * dz + dy * dy;
                    if (dist2 > radius * radius + 1) continue;
                    BlockPos p = new BlockPos(x + dx, leafTop + dy, z + dz);
                    if (world.getBlockState(p).isAir()) {
                        world.setBlockState(p, Blocks.OAK_LEAVES.getDefaultState(), 2);
                    }
                }
            }
        }
    }

    /**
     * Post-terrain decoration pass: optional pond, tall grass, flowers, dirt patches.
     * Seeded deterministically from {@code worldSeed ^ (cx * 31 + cz)} so a given seed
     * yields the same scenery on world reloads.
     */
    private static void decorate(ServerWorld world, int cx, int cz, long worldSeed, int seaLevel) {
        Random rng = new Random(worldSeed ^ ((long) (cx * 31 + cz)));

        int searchRadius = (int) Math.ceil(SPAWN_ISLAND_RADIUS) + 2;
        List<int[]> grass = IslandDecoration.collectGrassSurface(
            world, cx, cz, searchRadius, seaLevel + 1);
        // The new height profile means grass tops can be at varying y; also try a few
        // levels up so we catch hilltop grass.
        for (int extraY = 1; extraY <= MAX_HEIGHT_ABOVE_SEA + 1; extraY++) {
            grass.addAll(IslandDecoration.collectGrassSurface(world, cx, cz, searchRadius, seaLevel + extraY));
        }
        if (grass.isEmpty()) return;

        // Tall grass tufts (4-8).
        Block tallGrass = IslandDecoration.tallGrassBlock();
        int tufts = 4 + rng.nextInt(5);
        IslandDecoration.scatterPlants(world, grass, rng, tufts, tallGrass, seaLevel + 1, 1);

        // Dandelions (1-3) + poppies (0-2).
        int dandelions = 1 + rng.nextInt(3);
        IslandDecoration.scatterPlants(world, grass, rng, dandelions, Blocks.DANDELION, seaLevel + 1, 4);
        int poppies = rng.nextInt(3);
        IslandDecoration.scatterPlants(world, grass, rng, poppies, Blocks.POPPY, seaLevel + 1, 4);
    }

    private SpawnIsland() {}
}
