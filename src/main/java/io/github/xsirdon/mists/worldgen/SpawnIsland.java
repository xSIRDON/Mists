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
 * Builds a small, vanilla-looking spawn island that blends into an existing ocean.
 *
 * <p>v0.9 rewrite: previous versions used per-cell random noise for cell heights,
 * which produced a chaotic grass/dirt checkerboard. This version uses two-octave
 * coherent value noise — neighbouring cells now have similar heights, so the
 * surface forms a smooth, natural hill instead of stepwise hopscotch.
 *
 * <p>Block stack matches vanilla mushroom-field / beach-island generation:
 * <pre>
 *   y = topY              grass (interior) / sand (waterline beach)
 *   y = topY-1 .. topY-3  dirt (above water) / sand (at/below water)
 *   y = topY-4 .. floor+1 stone (deep body of the island)
 *   y &lt;= floor              natural ocean floor — untouched
 * </pre>
 *
 * <p>Underwater extension: cells inside the island shape whose height profile drops
 * below sea level still place a gentle sand slope from the natural floor up to
 * just below the waterline, so the island has visible roots when viewed from
 * underwater rather than appearing to float.
 *
 * <p>Trees are denser and taller, with rounded 5×5 canopies that match vanilla
 * oak placement.
 */
public final class SpawnIsland {

    public static final double SPAWN_ISLAND_RADIUS = 28.0;   // ~4 chunks across

    /** Maximum island height above sea level for the central core. */
    private static final int MAX_HEIGHT_ABOVE_SEA = 5;

    /** How deep the underwater foundation may extend (block probe depth). */
    private static final int UNDERWATER_FOUNDATION_DEPTH = 14;

    /** Legacy constants used by ring islands and PlayerJoinClamp. Vanilla sea level 63 assumed. */
    public static final int SPAWN_Y = 65;
    public static final int BASE_Y  = 60;

    public static void build(ServerWorld world, double cx, double cz, long worldSeed) {
        int seaLevel = world.getSeaLevel();
        IslandShape shape = new IslandShape(cx, cz, SPAWN_ISLAND_RADIUS, worldSeed);

        // Iterate a square fully enclosing the island shape (+ a small buffer).
        int xFrom = (int) Math.floor(cx - SPAWN_ISLAND_RADIUS - 4);
        int xTo   = (int) Math.ceil (cx + SPAWN_ISLAND_RADIUS + 4);
        int zFrom = (int) Math.floor(cz - SPAWN_ISLAND_RADIUS - 4);
        int zTo   = (int) Math.ceil (cz + SPAWN_ISLAND_RADIUS + 4);

        List<int[]> grassTops = new ArrayList<>();   // {x, z, topY} of every grass cell
        List<int[]> coreTops  = new ArrayList<>();   // grass cells suitable for tree placement
        int spawnTopY = seaLevel + 2; // updated when we hit the center cell

        for (int x = xFrom; x <= xTo; x++) {
            for (int z = zFrom; z <= zTo; z++) {
                if (!shape.contains(x, z)) continue;

                int topY = computeTopY(x, z, cx, cz, seaLevel, worldSeed);
                int floor = findOceanFloor(world, x, z, seaLevel);

                // Cells where the profile drops below the natural floor are skipped
                // entirely (natural ocean stays natural).
                if (topY <= floor) continue;

                if (x == (int) cx && z == (int) cz) spawnTopY = Math.max(seaLevel + 1, topY);

                buildColumn(world, x, z, floor, topY, seaLevel, worldSeed);

                // Track surfaces for tree placement and decoration.
                if (topY > seaLevel + 1) {
                    grassTops.add(new int[]{ x, z, topY });
                    // Core = far enough above waterline that a tree won't look weird.
                    if (topY >= seaLevel + 2) coreTops.add(new int[]{ x, z, topY });
                }
            }
        }

        // Anchor the world spawn to the actual built top at the centre.
        BlockPos spawnBlock = new BlockPos((int) cx, spawnTopY + 1, (int) cz);
        world.setSpawnPos(spawnBlock, 0f);

        placeTrees(world, coreTops, worldSeed);
        decorate(world, grassTops, worldSeed);

        Mists.LOG.info("Mists: spawn island built at ({}, ~{}, {}) sea={}",
            (int) cx, spawnTopY, (int) cz, seaLevel);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Per-column construction (vanilla-style block stack)
    // ─────────────────────────────────────────────────────────────────────────────

    private static void buildColumn(ServerWorld world, int x, int z,
                                    int floor, int topY, int seaLevel, long worldSeed) {

        boolean isBeach = topY <= seaLevel + 1;
        // Where stone ends and the topsoil begins. Vanilla uses ~3 blocks of dirt
        // beneath grass on beach islands.
        int topsoilStart = topY - 3;

        // ── Foundation: stone fills from floor+1 up to topsoilStart-1 ──
        int stoneTop = Math.min(topsoilStart - 1, topY - 1);
        int stoneBottom = Math.max(floor + 1, seaLevel - UNDERWATER_FOUNDATION_DEPTH);
        for (int y = stoneBottom; y <= stoneTop; y++) {
            if (y > topY) break;
            // Below water: occasionally use gravel for variety. Above water: stone.
            if (y < seaLevel - 1) {
                boolean gravel = coherentBool(x, y, z, worldSeed ^ 0xCAFEBABEL, 0.18);
                setIfReplaceable(world, x, y, z, gravel
                    ? Blocks.GRAVEL.getDefaultState()
                    : Blocks.STONE.getDefaultState());
            } else {
                setIfReplaceable(world, x, y, z, Blocks.STONE.getDefaultState());
            }
        }

        // ── Topsoil: dirt (above water) or sand (at/below water) ──
        int topsoilLow = Math.max(topsoilStart, floor + 1);
        for (int y = topsoilLow; y <= topY - 1; y++) {
            BlockState mid;
            if (y <= seaLevel) {
                // Underwater portion of topsoil reads as sand (matches vanilla beach skirt).
                mid = Blocks.SAND.getDefaultState();
            } else {
                mid = Blocks.DIRT.getDefaultState();
            }
            setIfReplaceable(world, x, y, z, mid);
        }

        // ── Cap ──
        BlockState cap;
        if (isBeach) {
            cap = Blocks.SAND.getDefaultState();
        } else {
            cap = Blocks.GRASS_BLOCK.getDefaultState();
        }
        // setBlockState directly on the cap, even if there was something there.
        world.setBlockState(new BlockPos(x, topY, z), cap, 2);

        // ── Clear any natural land sitting above the island top ──
        // (In true open ocean this is a no-op — water/air above sea level. Defensive
        // for cases where the openness scoring didn't find perfect ocean.)
        int clearTop = topY + 8;
        for (int y = topY + 1; y <= clearTop; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState bs = world.getBlockState(p);
            if (!bs.isAir() && !bs.isOf(Blocks.WATER)) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
            }
        }
    }

    /** Replace only if the existing block is water or air. Natural deep stone stays put. */
    private static void setIfReplaceable(ServerWorld world, int x, int y, int z, BlockState desired) {
        BlockPos p = new BlockPos(x, y, z);
        BlockState bs = world.getBlockState(p);
        if (bs.isAir() || bs.isOf(Blocks.WATER) || bs.equals(desired)) {
            if (!bs.equals(desired)) {
                world.setBlockState(p, desired, 2);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Coherent noise for heights (the actual fix for the checkerboard)
    // ─────────────────────────────────────────────────────────────────────────────

    private static int computeTopY(int x, int z, double cx, double cz, int seaLevel, long worldSeed) {
        double ddx = x - cx, ddz = z - cz;
        double dist = Math.sqrt(ddx * ddx + ddz * ddz);
        double normalized = Math.min(1.0, dist / SPAWN_ISLAND_RADIUS);

        // Smoothstep falloff: high at centre, soft taper to zero at the edge.
        double s = 1.0 - normalized;
        double falloff = s * s * (3.0 - 2.0 * s);

        // Two-octave coherent noise: coarse rolling hills + fine block-scale variation.
        double coarse = sampleOctave(x, z, 28.0, worldSeed);                // amplitude scaled below
        double fine   = sampleOctave(x, z,  9.0, worldSeed ^ 0x9E3779B1L);

        // Heights: centre at MAX (5), edge near 0, with ±1.5 blocks coherent variation.
        double base = falloff * MAX_HEIGHT_ABOVE_SEA;
        double variation = coarse * 1.4 + fine * 0.6;

        // Edge bias: push edges slightly underwater so the beach blends naturally.
        double edgeBias = -1.5 * (1.0 - falloff);

        double h = base + variation + edgeBias;
        return seaLevel + (int) Math.round(h);
    }

    /** Bilinear value noise. Returns approximately [-1, 1]. Smooth across cells. */
    private static double sampleOctave(double x, double z, double scale, long seed) {
        double sx = x / scale, sz = z / scale;
        int xi = (int) Math.floor(sx);
        int zi = (int) Math.floor(sz);
        double tx = sx - xi, tz = sz - zi;
        double a = gridHash(xi,     zi,     seed);
        double b = gridHash(xi + 1, zi,     seed);
        double c = gridHash(xi,     zi + 1, seed);
        double d = gridHash(xi + 1, zi + 1, seed);
        double ux = smoothstep(tx);
        double uz = smoothstep(tz);
        double lerp1 = a + ux * (b - a);
        double lerp2 = c + ux * (d - c);
        return (lerp1 + uz * (lerp2 - lerp1)) * 2.0 - 1.0;
    }

    /** Deterministic [0, 1] hash per integer grid cell. */
    private static double gridHash(int x, int z, long seed) {
        long h = seed;
        h = h * 6364136223846793005L + (long) x * 1442695040888963407L;
        h = h * 6364136223846793005L + (long) z * 1442695040888963407L;
        h ^= h >>> 33;
        return ((h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL);
    }

    /** Boolean noise at probability {@code p} that is spatially coherent. */
    private static boolean coherentBool(int x, int y, int z, long seed, double p) {
        long h = seed;
        h = h * 6364136223846793005L + (long) x * 1442695040888963407L;
        h = h * 6364136223846793005L + (long) y * 1442695040888963407L;
        h = h * 6364136223846793005L + (long) z * 1442695040888963407L;
        h ^= h >>> 33;
        return ((h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL) < p;
    }

    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Ocean-floor probe
    // ─────────────────────────────────────────────────────────────────────────────

    private static int findOceanFloor(ServerWorld world, int x, int z, int seaLevel) {
        for (int y = seaLevel - 1; y >= seaLevel - UNDERWATER_FOUNDATION_DEPTH - 4; y--) {
            BlockState bs = world.getBlockState(new BlockPos(x, y, z));
            if (!bs.isAir() && !bs.isOf(Blocks.WATER)) {
                return y;
            }
        }
        return seaLevel - UNDERWATER_FOUNDATION_DEPTH;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Tree placement (denser, vanilla-shaped oaks)
    // ─────────────────────────────────────────────────────────────────────────────

    private static void placeTrees(ServerWorld world, List<int[]> coreCells, long worldSeed) {
        if (coreCells.isEmpty()) return;
        Random treeRng = new Random(worldSeed ^ 0x7E_5_E5L);
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
                if (ddx * ddx + ddz * ddz < 36) { tooClose = true; break; } // 6-block min spacing
            }
            if (tooClose) continue;
            placeOakTree(world, tx, ty + 1, tz, treeRng);
            placedAt.add(new int[]{ tx, tz });
            placed++;
        }
    }

    /**
     * Vanilla-shaped oak tree: 5–7 block trunk, two layers of 5×5 round canopy,
     * one layer of 3×3, and a single-block crown with a cross pattern. Matches
     * the look of {@code TreeConfiguredFeatures.OAK} closely enough without
     * the complexity of dispatching to the registered feature.
     */
    private static void placeOakTree(ServerWorld world, int x, int yBase, int z, Random rng) {
        int trunkHeight = 5 + rng.nextInt(3); // 5, 6, 7

        // Trunk
        for (int i = 0; i < trunkHeight; i++) {
            world.setBlockState(new BlockPos(x, yBase + i, z), Blocks.OAK_LOG.getDefaultState(), 2);
        }

        int trunkTop = yBase + trunkHeight - 1;
        // Canopy layout, from bottom to top:
        //   trunkTop-1: 5×5 rounded
        //   trunkTop:   5×5 rounded
        //   trunkTop+1: 3×3 full
        //   trunkTop+2: cross (5 blocks: centre + N/S/E/W)
        placeLeafLayer(world, x, trunkTop - 1, z, 2, true);
        placeLeafLayer(world, x, trunkTop,     z, 2, true);
        placeLeafLayer(world, x, trunkTop + 1, z, 1, false);
        placeLeafCross(world, x, trunkTop + 2, z);
    }

    private static void placeLeafLayer(ServerWorld world, int cx, int y, int cz, int radius, boolean rounded) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (rounded && Math.abs(dx) == radius && Math.abs(dz) == radius) continue;
                BlockPos p = new BlockPos(cx + dx, y, cz + dz);
                BlockState bs = world.getBlockState(p);
                if (bs.isAir() || bs.isOf(Blocks.OAK_LEAVES)) {
                    world.setBlockState(p, Blocks.OAK_LEAVES.getDefaultState(), 2);
                }
            }
        }
    }

    private static void placeLeafCross(ServerWorld world, int cx, int y, int cz) {
        int[][] offsets = { {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        for (int[] o : offsets) {
            BlockPos p = new BlockPos(cx + o[0], y, cz + o[1]);
            if (world.getBlockState(p).isAir()) {
                world.setBlockState(p, Blocks.OAK_LEAVES.getDefaultState(), 2);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Decoration: flora that follows the actual grass heights
    // ─────────────────────────────────────────────────────────────────────────────

    private static void decorate(ServerWorld world, List<int[]> grassTops, long worldSeed) {
        if (grassTops.isEmpty()) return;
        Random rng = new Random(worldSeed ^ 0xDEC0DEL);

        Block tallGrass = IslandDecoration.tallGrassBlock();
        BlockState tallGrassState = tallGrass.getDefaultState();
        BlockState dandelion = Blocks.DANDELION.getDefaultState();
        BlockState poppy = Blocks.POPPY.getDefaultState();

        // Scatter: pick random grass cells, place a plant directly on top of their actual y.
        // Crucially, we use the per-cell topY from grassTops (not a guessed constant) so
        // plants never float over the water.
        int tufts = 6 + rng.nextInt(7);          // 6–12 tall grass
        int dandelions = 1 + rng.nextInt(4);     // 1–4
        int poppies = rng.nextInt(3);            // 0–2

        scatterFlora(world, grassTops, rng, tufts, tallGrassState, 1);
        scatterFlora(world, grassTops, rng, dandelions, dandelion, 4);
        scatterFlora(world, grassTops, rng, poppies, poppy, 4);
    }

    private static void scatterFlora(ServerWorld world, List<int[]> cells, Random rng,
                                     int count, BlockState plant, int minSpacing) {
        if (cells.isEmpty() || count <= 0) return;
        List<int[]> placed = new ArrayList<>();
        int attempts = 0;
        int placedCount = 0;
        int maxAttempts = count * 20;
        int minSpacingSq = minSpacing * minSpacing;
        while (placedCount < count && attempts < maxAttempts) {
            attempts++;
            int[] c = cells.get(rng.nextInt(cells.size()));
            boolean tooClose = false;
            for (int[] pp : placed) {
                int ddx = pp[0] - c[0], ddz = pp[1] - c[1];
                if (ddx * ddx + ddz * ddz < minSpacingSq) { tooClose = true; break; }
            }
            if (tooClose) continue;
            // The grass top at this cell is c[2]; plant goes at c[2]+1.
            BlockPos plantPos = new BlockPos(c[0], c[2] + 1, c[1]);
            if (world.getBlockState(plantPos).isAir() &&
                world.getBlockState(plantPos.down()).isOf(Blocks.GRASS_BLOCK)) {
                world.setBlockState(plantPos, plant, 2);
                placed.add(c);
                placedCount++;
            }
        }
    }

    private SpawnIsland() {}
}
