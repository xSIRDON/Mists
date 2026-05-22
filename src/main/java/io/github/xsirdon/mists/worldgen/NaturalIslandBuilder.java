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
 * Builds a natural-looking island using Minecraft's own noise samplers and
 * vanilla configured features.
 *
 * <p>Generation pipeline (per island, from the bottom up):
 *
 * <ol>
 *   <li><b>Heightmap</b> — two-octave value noise + smoothstep distance falloff
 *       gives a coherent hill profile across the island footprint.</li>
 *   <li><b>Stone foundation</b> — column-by-column from the natural ocean floor
 *       up to {@code topY - 3}. Each interior stone cell is 3D-noise-tested for
 *       cave carving (small underwater caves form where the noise is high).</li>
 *   <li><b>Topsoil</b> — sand below the waterline, dirt above. Grass/sand cap
 *       at {@code topY} chosen by waterline proximity.</li>
 *   <li><b>Ores</b> — vanilla {@code ORE_COAL} and {@code ORE_IRON} configured
 *       features dispatched at random positions within the stone column. Same
 *       feature Minecraft uses for natural overworld ore generation.</li>
 *   <li><b>Surface flora</b> — vanilla {@code PATCH_GRASS_PLAIN},
 *       {@code PATCH_DANDELION}, {@code PATCH_POPPY} configured features run
 *       on the grass surface. Real Minecraft flora patches, not hand-placed
 *       single blocks.</li>
 *   <li><b>Trees</b> — vanilla {@code OAK} configured feature run at scattered
 *       positions with minimum-spacing rules. Produces real vanilla-shaped
 *       oak trees with proper trunk variance, canopy noise, and root behavior.</li>
 * </ol>
 *
 * <p>Caves are limited to the stone foundation (below {@code topY - 3}) so the
 * surface never has random craters punched through it. Ore and flora dispatch
 * use vanilla feature.generate calls which integrate with Minecraft's chunk
 * tracking, so the placed features participate in light updates, are saved
 * properly, and look identical to organically-generated content.
 */
public final class NaturalIslandBuilder {

    /** How deep the underwater foundation may extend (block probe depth). */
    private static final int UNDERWATER_FOUNDATION_DEPTH = 14;

    /** Cave-noise threshold. Higher = fewer caves. */
    private static final double CAVE_THRESHOLD = 0.55;

    public static Result build(ServerWorld world, double cx, double cz, double radius,
                                int maxHeight, double edgeBias, long shapeSeed, long noiseSeed) {
        int seaLevel = world.getSeaLevel();
        IslandShape shape = new IslandShape(cx, cz, radius, shapeSeed);

        // 3D cave noise — Minecraft's own simplex sampler so the result looks the
        // same as natural carver-style cavities.
        SimplexNoiseSampler caveNoise = new SimplexNoiseSampler(new CheckedRandom(noiseSeed ^ 0xC4_5E_C4_5EL));

        int xFrom = (int) Math.floor(cx - radius - 4);
        int xTo   = (int) Math.ceil (cx + radius + 4);
        int zFrom = (int) Math.floor(cz - radius - 4);
        int zTo   = (int) Math.ceil (cz + radius + 4);

        List<int[]> grassTops = new ArrayList<>();
        List<int[]> coreTops  = new ArrayList<>();
        int centerTopY = seaLevel + 2;

        // ─── Step 1-3: heightmap + stone (with caves) + topsoil + cap ─────────
        for (int x = xFrom; x <= xTo; x++) {
            for (int z = zFrom; z <= zTo; z++) {
                if (!shape.contains(x, z)) continue;

                int topY = computeTopY(x, z, cx, cz, radius, maxHeight, edgeBias, seaLevel, noiseSeed);
                int floor = findOceanFloor(world, x, z, seaLevel);
                if (topY <= floor) continue;

                if (x == (int) cx && z == (int) cz) centerTopY = Math.max(seaLevel + 1, topY);

                buildColumn(world, x, z, floor, topY, seaLevel, noiseSeed, caveNoise);

                if (topY > seaLevel + 1) {
                    grassTops.add(new int[]{ x, z, topY });
                    if (topY >= seaLevel + 2) coreTops.add(new int[]{ x, z, topY });
                }
            }
        }

        // ─── Step 4: ores via vanilla feature.generate ────────────────────────
        Random rng = new Random(noiseSeed ^ 0x0E_BA_0E_BAL);
        placeOres(world, (int) cx, (int) cz, (int) radius, seaLevel, rng);

        // ─── Step 5 & 6: surface features via vanilla feature.generate ────────
        // (Tree count proportional to grass area; flora proportional to grass area.)
        placeVanillaTrees(world, coreTops, Math.max(2, coreTops.size() / 50), 5, rng);
        placeVanillaGrassPatches(world, grassTops, Math.max(2, grassTops.size() / 30), rng);
        placeVanillaFlowerPatches(world, grassTops, Math.max(1, grassTops.size() / 80), rng);

        return new Result(grassTops, coreTops, centerTopY, seaLevel);
    }

    public record Result(List<int[]> grassTops, List<int[]> coreTops, int centerTopY, int seaLevel) {}

    // ─────────────────────────────────────────────────────────────────────────────
    // Per-column construction with 3D cave carving
    // ─────────────────────────────────────────────────────────────────────────────

    private static void buildColumn(ServerWorld world, int x, int z,
                                    int floor, int topY, int seaLevel, long noiseSeed,
                                    SimplexNoiseSampler caveNoise) {
        boolean isBeach = topY <= seaLevel + 1;
        int topsoilStart = topY - 3;

        // Stone foundation — every cell tested for cave carving. Surface 4 blocks
        // (topY-3 .. topY) are NEVER carved so the surface stays intact.
        int stoneTop = Math.min(topsoilStart - 1, topY - 1);
        int stoneBottom = Math.max(floor + 1, seaLevel - UNDERWATER_FOUNDATION_DEPTH);
        for (int y = stoneBottom; y <= stoneTop && y <= topY; y++) {
            // 3D cave check — leave any deep stone column open as a small cave
            // wherever the simplex noise is high.
            boolean carve = isCave(caveNoise, x, y, z) && y < topY - 4 && y < seaLevel + 1;
            BlockState block;
            if (carve) {
                // Inside a cave below the surface — air. Vanilla would also do
                // lava lakes deep down, but we keep this conservative.
                block = Blocks.CAVE_AIR.getDefaultState();
            } else if (y < seaLevel - 1 && coherentBool(x, y, z, noiseSeed ^ 0xCAFEBABEL, 0.18)) {
                block = Blocks.GRAVEL.getDefaultState();
            } else {
                block = Blocks.STONE.getDefaultState();
            }
            setIfReplaceable(world, x, y, z, block);
        }

        // Topsoil (dirt above water, sand at/below water).
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

        // Clear any natural land above (defensive).
        int clearTop = topY + 8;
        for (int y = topY + 1; y <= clearTop; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState bs = world.getBlockState(p);
            if (!bs.isAir() && !bs.isOf(Blocks.WATER)) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
            }
        }
    }

    /** True if the 3D cave noise at (x, y, z) crosses the carving threshold. */
    private static boolean isCave(SimplexNoiseSampler noise, int x, int y, int z) {
        // Coarse cave channels stretched horizontally so caves feel tunnel-like.
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

    /** Place vanilla ore configured features within the island's stone column. */
    private static void placeOres(ServerWorld world, int cx, int cz, int radius,
                                   int seaLevel, Random rng) {
        // Modest counts so the island isn't a mine — just enough to be interesting.
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

    /** Place vanilla {@code TreeConfiguredFeatures.OAK} with minimum spacing. */
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
                // Couldn't place a vanilla tree here (sapling-would-survive failed).
                // Move on; the cell list has plenty of candidates.
                placed.add(c); // mark as tried so we don't infinite-loop on it
            }
        }
    }

    /** Place vanilla tall-grass patches. */
    private static void placeVanillaGrassPatches(ServerWorld world, List<int[]> cells,
                                                  int patches, Random rng) {
        if (cells.isEmpty() || patches <= 0) return;
        for (int i = 0; i < patches; i++) {
            int[] c = cells.get(rng.nextInt(cells.size()));
            BlockPos pos = new BlockPos(c[0], c[2] + 1, c[1]);
            tryGenerateFeature(world, VegetationConfiguredFeatures.PATCH_GRASS, pos, rng);
        }
    }

    /** Place vanilla dandelion + poppy patches. */
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

    /** Run a vanilla configured feature at the given position. */
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
    // Heightmap (unchanged from v0.11)
    // ─────────────────────────────────────────────────────────────────────────────

    public static int computeTopY(int x, int z, double cx, double cz, double radius,
                                   int maxHeight, double edgeBias, int seaLevel, long noiseSeed) {
        double ddx = x - cx, ddz = z - cz;
        double dist = Math.sqrt(ddx * ddx + ddz * ddz);
        double normalized = Math.min(1.0, dist / radius);
        double s = 1.0 - normalized;
        double falloff = s * s * (3.0 - 2.0 * s);
        double coarseScale = Math.max(20.0, radius * 1.0);
        double fineScale   = Math.max( 7.0, radius * 0.32);
        double coarse = sampleOctave(x, z, coarseScale, noiseSeed);
        double fine   = sampleOctave(x, z, fineScale,   noiseSeed ^ 0x9E3779B1L);
        double base = falloff * maxHeight;
        double variation = coarse * 1.4 + fine * 0.6;
        double bias = -edgeBias * (1.0 - falloff);
        return seaLevel + (int) Math.round(base + variation + bias);
    }

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
        for (int y = seaLevel - 1; y >= seaLevel - UNDERWATER_FOUNDATION_DEPTH - 4; y--) {
            BlockState bs = world.getBlockState(new BlockPos(x, y, z));
            if (!bs.isAir() && !bs.isOf(Blocks.WATER)) {
                return y;
            }
        }
        return seaLevel - UNDERWATER_FOUNDATION_DEPTH;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Legacy hand-placed methods (kept for compatibility with older callers)
    // ─────────────────────────────────────────────────────────────────────────────

    /** @deprecated The new {@link #build} path uses vanilla TREE features instead. */
    @Deprecated
    public static void scatterOaks(ServerWorld world, List<int[]> coreCells, int target,
                                    int minSpacing, Random rng) {
        placeVanillaTrees(world, coreCells, target, minSpacing, rng);
    }

    /** @deprecated The new {@link #build} path uses vanilla flora features. */
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
