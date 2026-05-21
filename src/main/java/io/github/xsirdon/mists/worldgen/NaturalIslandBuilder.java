package io.github.xsirdon.mists.worldgen;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Shared natural-island generation used by both the spawn island and the
 * tier ring islands. Centralises the v0.9 coherent-noise heightmap,
 * vanilla-style block stack, and oak placement so both island types look
 * consistent.
 *
 * <p>Vanilla-style block stack per column:
 * <pre>
 *   y = topY              grass (interior) / sand (waterline beach)
 *   y = topY-1 .. topY-3  dirt (above water) / sand (at/below water)
 *   y = topY-4 .. floor+1 stone with occasional gravel
 *   y &lt;= floor              natural ocean floor — untouched
 * </pre>
 *
 * <p>Height comes from two-octave coherent value noise so neighbouring cells
 * share similar heights — the surface forms a smooth hill instead of the
 * chaotic per-cell hopscotch that earlier versions had.
 */
public final class NaturalIslandBuilder {

    /** How deep the underwater foundation may extend (block probe depth). */
    private static final int UNDERWATER_FOUNDATION_DEPTH = 14;

    /**
     * Build a natural-looking island centred at (cx, cz) with the given outer radius.
     *
     * @param world         server world
     * @param cx            island centre X
     * @param cz            island centre Z
     * @param radius        outer radius (the IslandShape boundary)
     * @param maxHeight     maximum height above sea level at the centre
     * @param edgeBias      how much to push the perimeter underwater (positive number; ~1.5 for natural beaches)
     * @param shapeSeed     seed used by the IslandShape mask
     * @param noiseSeed     seed used by the height noise (typically same as shapeSeed)
     * @return  list of {x, z, topY} for every grass cell placed (useful for tree/flora scattering)
     */
    public static Result build(ServerWorld world, double cx, double cz, double radius,
                                int maxHeight, double edgeBias, long shapeSeed, long noiseSeed) {
        int seaLevel = world.getSeaLevel();
        IslandShape shape = new IslandShape(cx, cz, radius, shapeSeed);

        int xFrom = (int) Math.floor(cx - radius - 4);
        int xTo   = (int) Math.ceil (cx + radius + 4);
        int zFrom = (int) Math.floor(cz - radius - 4);
        int zTo   = (int) Math.ceil (cz + radius + 4);

        List<int[]> grassTops = new ArrayList<>();
        List<int[]> coreTops  = new ArrayList<>();
        int centerTopY = seaLevel + 2; // updated when we hit the centre cell

        for (int x = xFrom; x <= xTo; x++) {
            for (int z = zFrom; z <= zTo; z++) {
                if (!shape.contains(x, z)) continue;

                int topY = computeTopY(x, z, cx, cz, radius, maxHeight, edgeBias, seaLevel, noiseSeed);
                int floor = findOceanFloor(world, x, z, seaLevel);
                if (topY <= floor) continue;

                if (x == (int) cx && z == (int) cz) centerTopY = Math.max(seaLevel + 1, topY);

                buildColumn(world, x, z, floor, topY, seaLevel, noiseSeed);

                if (topY > seaLevel + 1) {
                    grassTops.add(new int[]{ x, z, topY });
                    if (topY >= seaLevel + 2) coreTops.add(new int[]{ x, z, topY });
                }
            }
        }

        return new Result(grassTops, coreTops, centerTopY, seaLevel);
    }

    public record Result(List<int[]> grassTops, List<int[]> coreTops, int centerTopY, int seaLevel) {}

    // ─────────────────────────────────────────────────────────────────────────────
    // Per-column construction (vanilla-style block stack)
    // ─────────────────────────────────────────────────────────────────────────────

    private static void buildColumn(ServerWorld world, int x, int z,
                                    int floor, int topY, int seaLevel, long noiseSeed) {
        boolean isBeach = topY <= seaLevel + 1;
        int topsoilStart = topY - 3;

        // Foundation: stone (with gravel sprinkle below water) from stoneBottom .. stoneTop.
        int stoneTop = Math.min(topsoilStart - 1, topY - 1);
        int stoneBottom = Math.max(floor + 1, seaLevel - UNDERWATER_FOUNDATION_DEPTH);
        for (int y = stoneBottom; y <= stoneTop && y <= topY; y++) {
            BlockState block;
            if (y < seaLevel - 1 && coherentBool(x, y, z, noiseSeed ^ 0xCAFEBABEL, 0.18)) {
                block = Blocks.GRAVEL.getDefaultState();
            } else {
                block = Blocks.STONE.getDefaultState();
            }
            setIfReplaceable(world, x, y, z, block);
        }

        // Topsoil: dirt (above water) or sand (at/below water).
        int topsoilLow = Math.max(topsoilStart, floor + 1);
        for (int y = topsoilLow; y <= topY - 1; y++) {
            BlockState mid = (y <= seaLevel)
                ? Blocks.SAND.getDefaultState()
                : Blocks.DIRT.getDefaultState();
            setIfReplaceable(world, x, y, z, mid);
        }

        // Cap.
        BlockState cap = isBeach ? Blocks.SAND.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState();
        world.setBlockState(new BlockPos(x, topY, z), cap, 2);

        // Clear any natural land above (defensive against imperfect open-ocean picks).
        int clearTop = topY + 8;
        for (int y = topY + 1; y <= clearTop; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState bs = world.getBlockState(p);
            if (!bs.isAir() && !bs.isOf(Blocks.WATER)) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
            }
        }
    }

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
    // Coherent noise heightmap
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Compute the top y for the cell at (x, z) using a coherent height profile.
     *
     * <p>Profile = smoothstep falloff (centre tall, edge low) + two-octave coherent
     * noise (coarse + fine variation that's spatially smooth across neighbouring cells).
     */
    public static int computeTopY(int x, int z, double cx, double cz, double radius,
                                   int maxHeight, double edgeBias, int seaLevel, long noiseSeed) {
        double ddx = x - cx, ddz = z - cz;
        double dist = Math.sqrt(ddx * ddx + ddz * ddz);
        double normalized = Math.min(1.0, dist / radius);

        double s = 1.0 - normalized;
        double falloff = s * s * (3.0 - 2.0 * s);

        // Two octaves of coherent value noise. Coarse rolling hills + fine detail.
        // Scale roughly proportional to island radius so a tiny island has a single
        // noise blob and a huge island has multiple rolling hills.
        double coarseScale = Math.max(20.0, radius * 1.0);
        double fineScale   = Math.max( 7.0, radius * 0.32);
        double coarse = sampleOctave(x, z, coarseScale, noiseSeed);
        double fine   = sampleOctave(x, z, fineScale,   noiseSeed ^ 0x9E3779B1L);

        double base = falloff * maxHeight;
        double variation = coarse * 1.4 + fine * 0.6;
        double bias = -edgeBias * (1.0 - falloff);

        return seaLevel + (int) Math.round(base + variation + bias);
    }

    /** Bilinear value noise. Returns approximately [-1, 1]. */
    public static double sampleOctave(double x, double z, double scale, long seed) {
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
    public static double gridHash(int x, int z, long seed) {
        long h = seed;
        h = h * 6364136223846793005L + (long) x * 1442695040888963407L;
        h = h * 6364136223846793005L + (long) z * 1442695040888963407L;
        h ^= h >>> 33;
        return ((h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL);
    }

    /** Boolean noise at probability {@code p}, spatially coherent across nearby blocks. */
    public static boolean coherentBool(int x, int y, int z, long seed, double p) {
        long h = seed;
        h = h * 6364136223846793005L + (long) x * 1442695040888963407L;
        h = h * 6364136223846793005L + (long) y * 1442695040888963407L;
        h = h * 6364136223846793005L + (long) z * 1442695040888963407L;
        h ^= h >>> 33;
        return ((h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL) < p;
    }

    public static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    public static int findOceanFloor(ServerWorld world, int x, int z, int seaLevel) {
        for (int y = seaLevel - 1; y >= seaLevel - UNDERWATER_FOUNDATION_DEPTH - 4; y--) {
            BlockState bs = world.getBlockState(new BlockPos(x, y, z));
            if (!bs.isAir() && !bs.isOf(Blocks.WATER)) {
                return y;
            }
        }
        return seaLevel - UNDERWATER_FOUNDATION_DEPTH;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Trees (vanilla-shaped oaks)
    // ─────────────────────────────────────────────────────────────────────────────

    /** Scatter oaks across the provided core grass cells with min-spacing enforcement. */
    public static void scatterOaks(ServerWorld world, List<int[]> coreCells, int target,
                                    int minSpacing, Random rng) {
        if (coreCells.isEmpty() || target <= 0) return;
        int minSpacingSq = minSpacing * minSpacing;
        List<int[]> placedAt = new ArrayList<>();
        int attempts = 0, placed = 0;
        while (placed < target && attempts < target * 30) {
            attempts++;
            int[] c = coreCells.get(rng.nextInt(coreCells.size()));
            int tx = c[0], tz = c[1], ty = c[2];
            boolean tooClose = false;
            for (int[] pp : placedAt) {
                int ddx = pp[0] - tx, ddz = pp[1] - tz;
                if (ddx * ddx + ddz * ddz < minSpacingSq) { tooClose = true; break; }
            }
            if (tooClose) continue;
            placeOakTree(world, tx, ty + 1, tz, rng);
            placedAt.add(new int[]{ tx, tz });
            placed++;
        }
    }

    /**
     * Vanilla-shaped oak tree: 5–7 block trunk; 5×5 rounded canopy on the two
     * lower layers; 3×3 above; cross crown on top. Matches the silhouette of
     * vanilla {@code TreeConfiguredFeatures.OAK}.
     */
    public static void placeOakTree(ServerWorld world, int x, int yBase, int z, Random rng) {
        int trunkHeight = 5 + rng.nextInt(3); // 5, 6, 7
        for (int i = 0; i < trunkHeight; i++) {
            world.setBlockState(new BlockPos(x, yBase + i, z), Blocks.OAK_LOG.getDefaultState(), 2);
        }
        int trunkTop = yBase + trunkHeight - 1;
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
    // Flora scatter that follows actual grass heights
    // ─────────────────────────────────────────────────────────────────────────────

    public static void scatterFlora(ServerWorld world, List<int[]> grassTops, Random rng,
                                     int count, BlockState plant, int minSpacing) {
        if (grassTops.isEmpty() || count <= 0) return;
        int minSpacingSq = minSpacing * minSpacing;
        List<int[]> placed = new ArrayList<>();
        int attempts = 0, placedCount = 0;
        int maxAttempts = count * 20;
        while (placedCount < count && attempts < maxAttempts) {
            attempts++;
            int[] c = grassTops.get(rng.nextInt(grassTops.size()));
            boolean tooClose = false;
            for (int[] pp : placed) {
                int ddx = pp[0] - c[0], ddz = pp[1] - c[1];
                if (ddx * ddx + ddz * ddz < minSpacingSq) { tooClose = true; break; }
            }
            if (tooClose) continue;
            BlockPos plantPos = new BlockPos(c[0], c[2] + 1, c[1]);
            if (world.getBlockState(plantPos).isAir() &&
                world.getBlockState(plantPos.down()).isOf(Blocks.GRASS_BLOCK)) {
                world.setBlockState(plantPos, plant, 2);
                placed.add(c);
                placedCount++;
            }
        }
    }

    private NaturalIslandBuilder() {}
}
