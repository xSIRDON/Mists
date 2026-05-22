package io.github.xsirdon.mists.worldgen;

import io.github.xsirdon.mists.Mists;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.noise.SimplexNoiseSampler;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.OreConfiguredFeatures;
import net.minecraft.world.gen.feature.TreeConfiguredFeatures;
import net.minecraft.world.gen.feature.VegetationConfiguredFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Builds a natural-looking island whose foundation BLENDS into the surrounding
 * ocean floor via a sloped underwater base, not a column.
 *
 * <p>Conceptually the island is a height-field defined by a continuous density
 * profile. For any (x, z), the profile evaluates to a topY relative to sea level:
 *
 * <pre>
 *   center:                topY = seaLevel + maxHeight        (hill)
 *   surfaceRadius:         topY = seaLevel                    (beach / waterline)
 *   beyond surfaceRadius:  topY drops linearly toward -maxDepth (underwater slope)
 *   underwaterRadius:      topY = seaLevel - maxDepth         (slope reaches floor)
 *   beyond underwaterRadius: skipped — natural seafloor untouched
 * </pre>
 *
 * <p>The profile produces an island whose underwater footprint extends roughly
 * <code>2 * surfaceRadius</code> outward, forming a gradual cone that meets the
 * ambient seafloor. Visually the island has roots; the player can swim out and
 * see sand sloping down into rock and then natural ocean floor.
 *
 * <p>Block layers (per column):
 * <pre>
 *   y = topY (when topY &gt; seaLevel+1):  GRASS_BLOCK
 *   y = topY (when topY &lt;= seaLevel+1): SAND  (beach cap or underwater cap)
 *   y in (seaLevel, topY-1]:             DIRT
 *   y in (topY-4, seaLevel]:             SAND  (waterline + underwater topsoil)
 *   y &lt;= topY-4 and y &lt; seaLevel-2:   STONE  (foundation, with 3D cave carving)
 * </pre>
 *
 * <p>Caves carve through deep stone via {@link SimplexNoiseSampler}. Trees,
 * flora, and ore are dispatched via vanilla {@code ConfiguredFeature.generate}
 * so they look identical to organically-generated content.
 */
public final class NaturalIslandBuilder {

    /** Cave-noise threshold. Higher = fewer caves. */
    private static final double CAVE_THRESHOLD = 0.55;

    /** Maximum stone-foundation depth below sea level. The underwater slope
     *  tapers down to this depth at the underwater extent. */
    private static final int MAX_DEPTH_BELOW_SEA = 12;

    public static Result build(ServerWorld world, double cx, double cz, double surfaceRadius,
                                int maxHeight, double edgeBias, long shapeSeed, long noiseSeed) {
        int seaLevel = world.getSeaLevel();
        // Underwater extent: 2x the surface radius. The foundation extends outward
        // this much as it tapers down to the natural seafloor.
        double underwaterRadius = surfaceRadius * 2.0;

        SimplexNoiseSampler caveNoise =
            new SimplexNoiseSampler(new CheckedRandom(noiseSeed ^ 0xC4_5E_C4_5EL));

        int reach = (int) Math.ceil(underwaterRadius) + 4;
        int xFrom = (int) Math.floor(cx) - reach;
        int xTo   = (int) Math.floor(cx) + reach;
        int zFrom = (int) Math.floor(cz) - reach;
        int zTo   = (int) Math.floor(cz) + reach;

        List<int[]> grassTops = new ArrayList<>();
        List<int[]> coreTops  = new ArrayList<>();
        int centerTopY = seaLevel + 2;

        // ─── Main terrain pass — every column that might host island material ──
        for (int x = xFrom; x <= xTo; x++) {
            for (int z = zFrom; z <= zTo; z++) {
                int topY = profileTopY(x, z, cx, cz, surfaceRadius, underwaterRadius,
                                       maxHeight, MAX_DEPTH_BELOW_SEA, noiseSeed, seaLevel);
                if (topY == Integer.MIN_VALUE) continue;

                int floor = findOceanFloor(world, x, z, seaLevel);
                if (topY <= floor) continue; // below natural floor — leave alone

                if (x == (int) cx && z == (int) cz) centerTopY = Math.max(seaLevel + 1, topY);

                buildColumn(world, x, z, floor, topY, seaLevel, noiseSeed, caveNoise);

                if (topY > seaLevel + 1) {
                    grassTops.add(new int[]{ x, z, topY });
                    if (topY >= seaLevel + 2) coreTops.add(new int[]{ x, z, topY });
                }
            }
        }

        // ─── Vanilla configured-feature dispatch (ore + flora + trees) ───────
        Random rng = new Random(noiseSeed ^ 0x0E_BA_0E_BAL);
        placeOres(world, (int) cx, (int) cz, (int) surfaceRadius, seaLevel, rng);
        placeVanillaTrees(world, coreTops, Math.max(2, coreTops.size() / 50), 5, rng);
        placeVanillaGrassPatches(world, grassTops, Math.max(2, grassTops.size() / 30), rng);
        placeVanillaFlowerPatches(world, grassTops, Math.max(1, grassTops.size() / 80), rng);

        Mists.LOG.info("Mists: built blended-foundation island at ({}, {}) — surfaceR={} underwaterR={} grassCells={}",
            (int) cx, (int) cz, (int) surfaceRadius, (int) underwaterRadius, grassTops.size());

        return new Result(grassTops, coreTops, centerTopY, seaLevel);
    }

    public record Result(List<int[]> grassTops, List<int[]> coreTops, int centerTopY, int seaLevel) {}

    // ─────────────────────────────────────────────────────────────────────────────
    // Density profile — the key change for v0.13
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Continuous height profile producing a sloped foundation that blends into
     * the ocean floor.
     *
     * <ul>
     *   <li>dist in [0, surfaceRadius]: smoothstep falloff from {@code seaLevel + maxHeight} to {@code seaLevel}</li>
     *   <li>dist in (surfaceRadius, underwaterRadius]: curved taper from {@code seaLevel} down to {@code seaLevel - maxDepth}</li>
     *   <li>dist beyond underwaterRadius: returns {@link Integer#MIN_VALUE} → caller skips, natural seafloor stays</li>
     * </ul>
     *
     * @return absolute world Y of the topmost block, or MIN_VALUE if out of extent
     */
    public static int profileTopY(int x, int z, double cx, double cz,
                                   double surfaceRadius, double underwaterRadius,
                                   int maxHeight, int maxDepth, long noiseSeed, int seaLevel) {
        double ddx = x - cx, ddz = z - cz;
        double dist = Math.sqrt(ddx * ddx + ddz * ddz);

        if (dist > underwaterRadius + 1) return Integer.MIN_VALUE;

        // Two-octave coherent noise — varies the shoreline and underwater slope.
        double coarse = sampleOctave(x, z, Math.max(20.0, surfaceRadius), noiseSeed);
        double fine   = sampleOctave(x, z, Math.max( 7.0, surfaceRadius * 0.32), noiseSeed ^ 0x9E3779B1L);
        double noiseAdjust = coarse * 1.3 + fine * 0.5;

        double base;
        if (dist <= surfaceRadius) {
            double t = 1.0 - (dist / surfaceRadius);
            double smoothed = t * t * (3.0 - 2.0 * t); // smoothstep
            base = smoothed * maxHeight;
        } else {
            double t = (dist - surfaceRadius) / (underwaterRadius - surfaceRadius);
            // Curved taper — gentler near shore, steeper toward seafloor.
            double curved = t * t;
            base = -curved * maxDepth;
        }

        // Dampen noise near the shore so the waterline doesn't become a jagged stairs.
        double dampening = (dist <= surfaceRadius) ? 0.7 : 0.4;
        double profile = base + noiseAdjust * dampening;

        return seaLevel + (int) Math.round(profile);
    }

    /**
     * Legacy entry point used by callers that pre-date v0.13. Maps the old
     * single-radius signature onto the new piecewise profile by treating the
     * passed radius as the surface radius and using 2x for the underwater extent.
     *
     * @deprecated prefer {@link #profileTopY} which takes both radii explicitly
     */
    @Deprecated
    public static int computeTopY(int x, int z, double cx, double cz, double radius,
                                   int maxHeight, double edgeBias, int seaLevel, long noiseSeed) {
        return profileTopY(x, z, cx, cz, radius, radius * 2.0,
                           maxHeight, MAX_DEPTH_BELOW_SEA, noiseSeed, seaLevel);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Column construction
    // ─────────────────────────────────────────────────────────────────────────────

    private static void buildColumn(ServerWorld world, int x, int z,
                                    int floor, int topY, int seaLevel, long noiseSeed,
                                    SimplexNoiseSampler caveNoise) {
        boolean isUnderwaterOnly = topY <= seaLevel;
        boolean isBeach = topY <= seaLevel + 1 && !isUnderwaterOnly;

        for (int y = floor + 1; y <= topY; y++) {
            BlockState block = pickBlock(y, topY, seaLevel, x, z, noiseSeed, caveNoise);
            setIfReplaceable(world, x, y, z, block);
        }

        // Defensive: clear any natural land that might exist above our placed top
        // (e.g. if the locate-biome fallback dropped us at a coastline).
        for (int y = topY + 1; y <= topY + 8; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState bs = world.getBlockState(p);
            if (!bs.isAir() && !bs.isOf(Blocks.WATER)) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
            }
        }
    }

    private static BlockState pickBlock(int y, int topY, int seaLevel,
                                         int x, int z, long noiseSeed,
                                         SimplexNoiseSampler caveNoise) {
        if (y == topY) {
            if (topY > seaLevel + 1) return Blocks.GRASS_BLOCK.getDefaultState();
            return Blocks.SAND.getDefaultState();
        }
        if (y > seaLevel) {
            return Blocks.DIRT.getDefaultState();
        }
        // y <= seaLevel — underwater material
        if (y > topY - 4) {
            return Blocks.SAND.getDefaultState();
        }
        // Deep stone foundation with cave carving + occasional gravel pockets.
        if (y < seaLevel - 2 && isCave(caveNoise, x, y, z)) {
            return Blocks.CAVE_AIR.getDefaultState();
        }
        if (y < seaLevel - 1 && coherentBool(x, y, z, noiseSeed ^ 0xCAFEBABEL, 0.18)) {
            return Blocks.GRAVEL.getDefaultState();
        }
        return Blocks.STONE.getDefaultState();
    }

    private static boolean isCave(SimplexNoiseSampler noise, int x, int y, int z) {
        double n = noise.sample(x * 0.06, y * 0.12, z * 0.06);
        return n > CAVE_THRESHOLD;
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
    // Vanilla configured-feature dispatch
    // ─────────────────────────────────────────────────────────────────────────────

    private static void placeOres(ServerWorld world, int cx, int cz, int radius,
                                   int seaLevel, Random rng) {
        int coalAttempts = 3 + rng.nextInt(3);
        int ironAttempts = 1 + rng.nextInt(2);

        for (int i = 0; i < coalAttempts; i++) {
            int dx = rng.nextInt(radius * 2) - radius;
            int dz = rng.nextInt(radius * 2) - radius;
            int y = seaLevel - 4 - rng.nextInt(8);
            tryGenerateFeature(world, OreConfiguredFeatures.ORE_COAL,
                new BlockPos(cx + dx, y, cz + dz), rng);
        }
        for (int i = 0; i < ironAttempts; i++) {
            int dx = rng.nextInt(radius * 2) - radius;
            int dz = rng.nextInt(radius * 2) - radius;
            int y = seaLevel - 6 - rng.nextInt(6);
            tryGenerateFeature(world, OreConfiguredFeatures.ORE_IRON,
                new BlockPos(cx + dx, y, cz + dz), rng);
        }
    }

    private static void placeVanillaTrees(ServerWorld world, List<int[]> cells,
                                           int target, int minSpacing, Random rng) {
        if (cells.isEmpty() || target <= 0) return;
        int minSpacingSq = minSpacing * minSpacing;
        List<int[]> placed = new ArrayList<>();
        int attempts = 0, count = 0;
        while (count < target && attempts < target * 30) {
            attempts++;
            int[] c = cells.get(rng.nextInt(cells.size()));
            int tx = c[0], tz = c[1], ty = c[2];
            boolean tooClose = false;
            for (int[] pp : placed) {
                int ddx = pp[0] - tx, ddz = pp[1] - tz;
                if (ddx * ddx + ddz * ddz < minSpacingSq) { tooClose = true; break; }
            }
            if (tooClose) continue;
            BlockPos treeBase = new BlockPos(tx, ty + 1, tz);
            if (tryGenerateFeature(world, TreeConfiguredFeatures.OAK, treeBase, rng)) {
                placed.add(c);
                count++;
            } else {
                placed.add(c);
            }
        }
    }

    private static void placeVanillaGrassPatches(ServerWorld world, List<int[]> cells,
                                                  int patches, Random rng) {
        if (cells.isEmpty() || patches <= 0) return;
        for (int i = 0; i < patches; i++) {
            int[] c = cells.get(rng.nextInt(cells.size()));
            BlockPos pos = new BlockPos(c[0], c[2] + 1, c[1]);
            tryGenerateFeature(world, VegetationConfiguredFeatures.PATCH_GRASS, pos, rng);
        }
    }

    private static void placeVanillaFlowerPatches(ServerWorld world, List<int[]> cells,
                                                   int patches, Random rng) {
        if (cells.isEmpty() || patches <= 0) return;
        for (int i = 0; i < patches; i++) {
            int[] c = cells.get(rng.nextInt(cells.size()));
            BlockPos pos = new BlockPos(c[0], c[2] + 1, c[1]);
            RegistryKey<ConfiguredFeature<?, ?>> key = (i % 2 == 0)
                ? VegetationConfiguredFeatures.FLOWER_DEFAULT
                : VegetationConfiguredFeatures.FLOWER_PLAIN;
            tryGenerateFeature(world, key, pos, rng);
        }
    }

    private static boolean tryGenerateFeature(ServerWorld world,
                                               RegistryKey<ConfiguredFeature<?, ?>> key,
                                               BlockPos pos, Random javaRng) {
        Optional<RegistryEntry.Reference<ConfiguredFeature<?, ?>>> ref =
            world.getRegistryManager().get(RegistryKeys.CONFIGURED_FEATURE).getEntry(key);
        if (ref.isEmpty()) {
            Mists.LOG.warn("Mists: vanilla feature {} not in registry", key.getValue());
            return false;
        }
        net.minecraft.util.math.random.Random mcRng = new CheckedRandom(javaRng.nextLong());
        return ref.get().value().generate(
            world,
            world.getChunkManager().getChunkGenerator(),
            mcRng,
            pos);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

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

    public static double gridHash(int x, int z, long seed) {
        long h = seed;
        h = h * 6364136223846793005L + (long) x * 1442695040888963407L;
        h = h * 6364136223846793005L + (long) z * 1442695040888963407L;
        h ^= h >>> 33;
        return ((h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL);
    }

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
        for (int y = seaLevel - 1; y >= seaLevel - 18; y--) {
            BlockState bs = world.getBlockState(new BlockPos(x, y, z));
            if (!bs.isAir() && !bs.isOf(Blocks.WATER)) {
                return y;
            }
        }
        return seaLevel - 18;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Deprecated bridges
    // ─────────────────────────────────────────────────────────────────────────────

    @Deprecated
    public static void scatterOaks(ServerWorld world, List<int[]> coreCells, int target,
                                    int minSpacing, Random rng) {
        placeVanillaTrees(world, coreCells, target, minSpacing, rng);
    }

    @Deprecated
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
