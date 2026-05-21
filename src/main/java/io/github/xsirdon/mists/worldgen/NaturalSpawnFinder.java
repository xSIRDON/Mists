package io.github.xsirdon.mists.worldgen;

import com.mojang.datafixers.util.Pair;
import io.github.xsirdon.mists.Mists;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.function.Predicate;

/**
 * Finds a small, isolated, naturally-generated island in the world's seed.
 *
 * <p>v0.11.2 strategy: try increasingly broad fallbacks until something usable
 * is found.
 *
 * <ol>
 *   <li><b>MUSHROOM_FIELDS</b> — vanilla guarantees this biome only generates as
 *       isolated ocean islands. If found anywhere in 12000 blocks of origin, we
 *       use it. Aesthetic is mycelium + giant mushrooms, but it's a real island.</li>
 *   <li><b>STRICT habitable</b> — locateBiome on any habitable biome (plains,
 *       forest, beach, jungle, savanna, taiga). For each candidate, sample
 *       8 cardinal directions at radius <b>250</b> — require 7/8 to be ocean.
 *       Also check radius 80 (most must be ocean) and a small inner ring
 *       (mostly land). This catches small isolated islands but rejects
 *       continental land.</li>
 *   <li><b>LENIENT habitable</b> — relaxed thresholds for the "no perfect island
 *       in this seed" case. Outer ring 5/8 ocean.</li>
 *   <li><b>null</b> — caller falls back to artificial NaturalIslandBuilder.</li>
 * </ol>
 */
public final class NaturalSpawnFinder {

    private static final int MIN_TOP_ABOVE_SEA = 1;
    private static final int MAX_TOP_ABOVE_SEA = 14;

    /** Ring radii for the strict island test. */
    private static final int INNER_RADIUS = 25;   // most must be land
    private static final int MID_RADIUS   = 80;   // most must be ocean
    private static final int OUTER_RADIUS = 250;  // almost all must be ocean

    private static final int SAMPLES_PER_RING = 8;

    /** Offset start points for locateBiome sweeps. */
    private static final int[][] SEARCH_STARTS = {
        {     0,     0 },
        {  1000,     0 }, { -1000,     0 }, {     0,  1000 }, {     0, -1000 },
        {  1500,  1500 }, { -1500, -1500 }, {  1500, -1500 }, { -1500,  1500 },
        {  2500,     0 }, { -2500,     0 }, {     0,  2500 }, {     0, -2500 },
        {  3500,  3500 }, { -3500, -3500 }, {  3500, -3500 }, { -3500,  3500 }
    };

    public static final class Result {
        public final BlockPos pos;
        public final int score;
        public final RegistryEntry<Biome> biome;
        public final String strategy;
        Result(BlockPos pos, int score, RegistryEntry<Biome> biome, String strategy) {
            this.pos = pos; this.score = score; this.biome = biome; this.strategy = strategy;
        }
    }

    public static Result find(ServerWorld world) {
        long t0 = System.currentTimeMillis();
        ChunkGenerator chunkGen = world.getChunkManager().getChunkGenerator();
        NoiseConfig noiseConfig = world.getChunkManager().getNoiseConfig();
        int seaLevel = world.getSeaLevel();

        // Strategy 1: locate MUSHROOM_FIELDS — vanilla-guaranteed island biome.
        Result mush = findMushroomIsland(world, chunkGen, noiseConfig, seaLevel);
        if (mush != null) {
            log(mush, t0, "mushroom_fields");
            return mush;
        }

        // Strategy 2: strict scoring on habitable biomes.
        Result strict = findIslandStrict(world, chunkGen, noiseConfig, seaLevel,
            /*minOuterOcean*/ 7, /*minInnerLand*/ 6, /*minMidOcean*/ 5);
        if (strict != null) {
            log(strict, t0, "strict habitable");
            return strict;
        }

        // Strategy 3: lenient scoring.
        Result lenient = findIslandStrict(world, chunkGen, noiseConfig, seaLevel,
            /*minOuterOcean*/ 5, /*minInnerLand*/ 4, /*minMidOcean*/ 3);
        if (lenient != null) {
            log(lenient, t0, "lenient habitable");
            return lenient;
        }

        Mists.LOG.warn("Mists: NaturalSpawnFinder found no candidates after {}ms — falling back to artificial generation",
            System.currentTimeMillis() - t0);
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Strategy 1: mushroom fields
    // ─────────────────────────────────────────────────────────────────────────────

    private static Result findMushroomIsland(ServerWorld world, ChunkGenerator chunkGen,
                                              NoiseConfig noiseConfig, int seaLevel) {
        // Mushroom fields are rare, so search wide (up to 12000 blocks) with a coarse step.
        int[][] starts = {
            { 0, 0 }, { 4000, 0 }, { -4000, 0 }, { 0, 4000 }, { 0, -4000 },
            { 4000, 4000 }, { -4000, -4000 }
        };
        Predicate<RegistryEntry<Biome>> isMushroom = e -> e.matchesKey(BiomeKeys.MUSHROOM_FIELDS);
        BlockPos best = null;
        long bestDist = Long.MAX_VALUE;
        RegistryEntry<Biome> bestBiome = null;
        for (int[] off : starts) {
            BlockPos start = new BlockPos(off[0], seaLevel, off[1]);
            Pair<BlockPos, RegistryEntry<Biome>> r = world.locateBiome(isMushroom, start, 6000, 128, 64);
            if (r == null) continue;
            BlockPos pos = r.getFirst();
            long d = (long) pos.getX() * pos.getX() + (long) pos.getZ() * pos.getZ();
            if (d < bestDist) {
                best = pos;
                bestDist = d;
                bestBiome = r.getSecond();
            }
        }
        if (best == null) return null;
        int topY = chunkGen.getHeight(best.getX(), best.getZ(),
            Heightmap.Type.WORLD_SURFACE_WG, world, noiseConfig);
        // Mushroom fields are always above sea level. Sanity check anyway.
        if (topY < seaLevel + MIN_TOP_ABOVE_SEA) return null;
        return new Result(new BlockPos(best.getX(), topY, best.getZ()),
            /*score*/ 9999, bestBiome, "mushroom_fields");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Strategy 2 / 3: habitable biome with island verification
    // ─────────────────────────────────────────────────────────────────────────────

    private static Result findIslandStrict(ServerWorld world, ChunkGenerator chunkGen,
                                            NoiseConfig noiseConfig, int seaLevel,
                                            int minOuterOcean, int minInnerLand, int minMidOcean) {
        Predicate<RegistryEntry<Biome>> habitable = NaturalSpawnFinder::isHabitableBiome;
        Result best = null;

        for (int[] off : SEARCH_STARTS) {
            BlockPos start = new BlockPos(off[0], seaLevel, off[1]);
            Pair<BlockPos, RegistryEntry<Biome>> r = world.locateBiome(habitable, start, 1500, 64, 64);
            if (r == null) continue;

            BlockPos pos = r.getFirst();
            int topY = chunkGen.getHeight(pos.getX(), pos.getZ(),
                Heightmap.Type.WORLD_SURFACE_WG, world, noiseConfig);
            int aboveSea = topY - seaLevel;
            if (aboveSea < MIN_TOP_ABOVE_SEA || aboveSea > MAX_TOP_ABOVE_SEA) continue;

            int innerLand = countLand(chunkGen, noiseConfig, world, pos.getX(), pos.getZ(), INNER_RADIUS, seaLevel);
            int midOcean  = SAMPLES_PER_RING - countLand(chunkGen, noiseConfig, world, pos.getX(), pos.getZ(), MID_RADIUS, seaLevel);
            int outerOcean = SAMPLES_PER_RING - countLand(chunkGen, noiseConfig, world, pos.getX(), pos.getZ(), OUTER_RADIUS, seaLevel);

            if (innerLand  < minInnerLand)  continue;
            if (midOcean   < minMidOcean)   continue;
            if (outerOcean < minOuterOcean) continue;

            // Score combines: high inner-land + high outer-ocean = good island.
            int score = innerLand * 100 + midOcean * 100 + outerOcean * 200;
            long d = (long) pos.getX() * pos.getX() + (long) pos.getZ() * pos.getZ();
            if (best == null || score > best.score) {
                best = new Result(new BlockPos(pos.getX(), topY, pos.getZ()), score, r.getSecond(),
                    String.format("habitable inner=%d/8 mid_ocean=%d/8 outer_ocean=%d/8", innerLand, midOcean, outerOcean));
            }
        }
        return best;
    }

    /** Count how many of the 8 cardinal-direction samples at the given radius are land. */
    private static int countLand(ChunkGenerator chunkGen, NoiseConfig noiseConfig, ServerWorld world,
                                  int cx, int cz, int radius, int seaLevel) {
        int land = 0;
        for (int a = 0; a < SAMPLES_PER_RING; a++) {
            double angle = a * (2.0 * Math.PI / SAMPLES_PER_RING);
            int sx = cx + (int) (Math.cos(angle) * radius);
            int sz = cz + (int) (Math.sin(angle) * radius);
            int top = chunkGen.getHeight(sx, sz,
                Heightmap.Type.WORLD_SURFACE_WG, world, noiseConfig);
            if (top > seaLevel) land++;
        }
        return land;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Biome predicate
    // ─────────────────────────────────────────────────────────────────────────────

    private static boolean isHabitableBiome(RegistryEntry<Biome> e) {
        if (e.isIn(BiomeTags.IS_OCEAN)) return false;
        if (e.isIn(BiomeTags.IS_RIVER)) return false;
        if (e.isIn(BiomeTags.IS_NETHER)) return false;
        if (e.isIn(BiomeTags.IS_END)) return false;
        if (e.isIn(BiomeTags.IS_MOUNTAIN)) return false;
        if (e.isIn(BiomeTags.IS_DEEP_OCEAN)) return false;
        return true;
    }

    private static void log(Result r, long t0, String strategy) {
        Mists.LOG.info("Mists: NaturalSpawnFinder ({}) picked ({}, {}, {}) score={} biome={} in {}ms",
            strategy, r.pos.getX(), r.pos.getY(), r.pos.getZ(), r.score,
            r.biome.getKey().map(k -> k.getValue().toString()).orElse("?"),
            System.currentTimeMillis() - t0);
    }

    private NaturalSpawnFinder() {}
}
