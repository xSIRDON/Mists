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

import java.util.Set;
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
    private static final int INNER_RADIUS  = 20;  // most must be land
    private static final int MID_RADIUS    = 60;  // mostly ocean
    private static final int OUTER_RADIUS  = 150; // ZERO land allowed at this ring or beyond
    private static final int FAR_RADIUS    = 250; // ZERO land allowed
    private static final int VERY_FAR_RADIUS = 400; // ZERO land allowed

    private static final int SAMPLES_PER_RING = 16; // 16 angles for precision

    /** Maximum acceptable measured island extent (blocks). Anything larger means we picked
     *  a continental coastline rather than a real island. */
    private static final int MAX_ISLAND_RADIUS = 50;

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

    /** Per user request: skip mushroom_fields and prefer plains-family biomes. */
    private static final Set<net.minecraft.registry.RegistryKey<Biome>> PLAINS_FAMILY = Set.of(
        BiomeKeys.PLAINS,
        BiomeKeys.SUNFLOWER_PLAINS,
        BiomeKeys.MEADOW,
        BiomeKeys.BEACH,
        BiomeKeys.SAVANNA,
        BiomeKeys.FOREST,
        BiomeKeys.FLOWER_FOREST,
        BiomeKeys.BIRCH_FOREST
    );

    public static Result find(ServerWorld world) {
        long t0 = System.currentTimeMillis();
        ChunkGenerator chunkGen = world.getChunkManager().getChunkGenerator();
        NoiseConfig noiseConfig = world.getChunkManager().getNoiseConfig();
        int seaLevel = world.getSeaLevel();

        // Strategy 1: plains-family biome, STRICT isolation (0 land at any of 3 far rings).
        Result plains = findIslandStrict(world, chunkGen, noiseConfig, seaLevel,
            /*maxFarLand*/ 0, /*minInnerLand*/ 12, /*maxMidLand*/ 2,
            /*biomeFilter*/ NaturalSpawnFinder::isPlainsFamily);
        if (plains != null) {
            log(plains, t0, "plains-family STRICT");
            return plains;
        }

        // Strategy 2: any habitable biome, strict isolation.
        Result strict = findIslandStrict(world, chunkGen, noiseConfig, seaLevel,
            /*maxFarLand*/ 0, /*minInnerLand*/ 12, /*maxMidLand*/ 2,
            /*biomeFilter*/ NaturalSpawnFinder::isHabitableBiome);
        if (strict != null) {
            log(strict, t0, "habitable STRICT");
            return strict;
        }

        // Strategy 3: plains-family, slightly relaxed (allow 1 land at far rings, 4 at mid).
        Result plainsLenient = findIslandStrict(world, chunkGen, noiseConfig, seaLevel,
            /*maxFarLand*/ 1, /*minInnerLand*/ 10, /*maxMidLand*/ 4,
            /*biomeFilter*/ NaturalSpawnFinder::isPlainsFamily);
        if (plainsLenient != null) {
            log(plainsLenient, t0, "plains-family lenient");
            return plainsLenient;
        }

        // Strategy 4: any habitable, slightly relaxed.
        Result lenient = findIslandStrict(world, chunkGen, noiseConfig, seaLevel,
            /*maxFarLand*/ 1, /*minInnerLand*/ 10, /*maxMidLand*/ 4,
            /*biomeFilter*/ NaturalSpawnFinder::isHabitableBiome);
        if (lenient != null) {
            log(lenient, t0, "habitable lenient");
            return lenient;
        }

        Mists.LOG.warn("Mists: NaturalSpawnFinder found no isolated island after {}ms — falling back to artificial generation",
            System.currentTimeMillis() - t0);
        return null;
    }

    /**
     * Measure the actual extent of the natural island around the given centre.
     * Sample outward radially until we hit mostly ocean — that's where the island ends.
     * Returns a radius in blocks (capped to a sane range so the boundary isn't absurd).
     */
    public static int measureIslandExtent(ServerWorld world, int cx, int cz) {
        ChunkGenerator chunkGen = world.getChunkManager().getChunkGenerator();
        NoiseConfig noiseConfig = world.getChunkManager().getNoiseConfig();
        int seaLevel = world.getSeaLevel();
        int lastLandRadius = 10;
        for (int r = 10; r <= 200; r += 5) {
            int landCount = 0;
            int samples = 12;
            for (int a = 0; a < samples; a++) {
                double angle = a * (2.0 * Math.PI / samples);
                int sx = cx + (int) (Math.cos(angle) * r);
                int sz = cz + (int) (Math.sin(angle) * r);
                int top = chunkGen.getHeight(sx, sz,
                    Heightmap.Type.WORLD_SURFACE_WG, world, noiseConfig);
                if (top > seaLevel) landCount++;
            }
            double landRatio = landCount / (double) samples;
            if (landRatio >= 0.25) {
                lastLandRadius = r;
            } else {
                // 75%+ ocean at this radius — past the island edge.
                break;
            }
        }
        return Math.max(20, Math.min(MAX_ISLAND_RADIUS, lastLandRadius));
    }

    private static boolean isPlainsFamily(RegistryEntry<Biome> e) {
        if (!isHabitableBiome(e)) return false;
        return e.getKey().map(PLAINS_FAMILY::contains).orElse(false);
    }

    /**
     * Strict island detection: a position is only accepted as an island if at
     * THREE far rings (150, 250, 400 blocks) ALL 16 cardinal-sample directions
     * are ocean. Plus the inner 20 blocks must be land. Plus the measured island
     * extent must be smaller than {@link #MAX_ISLAND_RADIUS} (otherwise it's a
     * continent the locateBiome happened to land on the coast of).
     *
     * @param maxFarLand maximum allowed land samples at the three far rings (0 for strict, higher for lenient)
     * @param minInnerLand minimum required land samples at INNER_RADIUS
     * @param maxMidLand maximum allowed land samples at MID_RADIUS
     */
    private static Result findIslandStrict(ServerWorld world, ChunkGenerator chunkGen,
                                            NoiseConfig noiseConfig, int seaLevel,
                                            int maxFarLand, int minInnerLand, int maxMidLand,
                                            Predicate<RegistryEntry<Biome>> biomeFilter) {
        Result best = null;

        for (int[] off : SEARCH_STARTS) {
            BlockPos start = new BlockPos(off[0], seaLevel, off[1]);
            Pair<BlockPos, RegistryEntry<Biome>> r = world.locateBiome(biomeFilter, start, 1500, 64, 64);
            if (r == null) continue;

            BlockPos pos = r.getFirst();
            int topY = chunkGen.getHeight(pos.getX(), pos.getZ(),
                Heightmap.Type.WORLD_SURFACE_WG, world, noiseConfig);
            int aboveSea = topY - seaLevel;
            if (aboveSea < MIN_TOP_ABOVE_SEA || aboveSea > MAX_TOP_ABOVE_SEA) continue;

            // Rings, from inner to outer.
            int innerLand   = countLand(chunkGen, noiseConfig, world, pos.getX(), pos.getZ(), INNER_RADIUS, seaLevel);
            int midLand     = countLand(chunkGen, noiseConfig, world, pos.getX(), pos.getZ(), MID_RADIUS, seaLevel);
            int outerLand   = countLand(chunkGen, noiseConfig, world, pos.getX(), pos.getZ(), OUTER_RADIUS, seaLevel);
            int farLand     = countLand(chunkGen, noiseConfig, world, pos.getX(), pos.getZ(), FAR_RADIUS, seaLevel);
            int veryFarLand = countLand(chunkGen, noiseConfig, world, pos.getX(), pos.getZ(), VERY_FAR_RADIUS, seaLevel);

            if (innerLand  < minInnerLand)           continue;
            if (midLand    > maxMidLand)             continue;
            if (outerLand  > maxFarLand)             continue;
            if (farLand    > maxFarLand)             continue;
            if (veryFarLand > maxFarLand + 1)        continue; // very-far gets +1 tolerance

            // Final check: measure the actual extent of the island. If it's wider than
            // MAX_ISLAND_RADIUS, it's a continent we happened to sample the edge of.
            int extent = measureIslandExtent(world, pos.getX(), pos.getZ());
            if (extent > MAX_ISLAND_RADIUS) continue;

            // Score: high inner-land + low far-land + small extent = great island.
            int score = innerLand * 100
                       + (SAMPLES_PER_RING - outerLand) * 50
                       + (SAMPLES_PER_RING - farLand) * 100
                       + (SAMPLES_PER_RING - veryFarLand) * 50
                       + (MAX_ISLAND_RADIUS - extent) * 20;

            if (best == null || score > best.score) {
                best = new Result(new BlockPos(pos.getX(), topY, pos.getZ()), score, r.getSecond(),
                    String.format("inner=%d/16 mid=%d/16 outer=%d/16 far=%d/16 vfar=%d/16 extent=%d",
                        innerLand, midLand, outerLand, farLand, veryFarLand, extent));
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
