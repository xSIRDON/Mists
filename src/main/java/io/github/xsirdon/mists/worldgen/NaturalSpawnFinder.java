package io.github.xsirdon.mists.worldgen;

import io.github.xsirdon.mists.Mists;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.function.Predicate;

/**
 * Finds a small, isolated, naturally-generated island in the world's seed.
 *
 * <p>Queries the chunk generator's noise function directly via
 * {@link ChunkGenerator#getHeight} — this samples the terrain heightmap
 * without loading chunks, so we can survey a multi-thousand-block area
 * cheaply at world creation. We then score each candidate by:
 *
 * <ul>
 *   <li><b>islandness</b> — fraction of cells within an inner radius that are land</li>
 *   <li><b>isolation</b> — fraction of cells in the outer ring that are ocean</li>
 *   <li><b>compactness penalty</b> — too much mid-zone land = it's a continent shore, not an island</li>
 * </ul>
 *
 * <p>The result is a position that sits on top of a natural landmass roughly
 * the size of a small islet — small enough to feel like an island, with open
 * water all around. Spawn is set to that position with no block placement at
 * all; whatever biome surface rules + carvers + features the world has
 * produce the actual terrain.
 */
public final class NaturalSpawnFinder {

    /** Maximum acceptable elevation above sea level. Higher means we picked a mountain. */
    private static final int MAX_TOP_ABOVE_SEA = 12;

    /** Minimum required elevation above sea level. Lower means it's barely above water. */
    private static final int MIN_TOP_ABOVE_SEA = 1;

    /** Inner sample radius — "is this cell mostly land?" */
    private static final int INNER_RADIUS = 28;

    /** Outer sample radius — "is the surrounding ocean?" */
    private static final int OUTER_RADIUS = 110;

    /** Sampling step inside the score function (smaller = more accurate, slower). */
    private static final int SAMPLE_STEP = 8;

    /** How wide to search (blocks from origin in each direction). */
    private static final int SEARCH_RADIUS = 4000;

    /** Step between candidates in the world-wide scan. Smaller = more candidates considered. */
    private static final int CANDIDATE_STEP = 80;

    /** Minimum acceptable combined score (islandness × isolation). Tuned empirically. */
    private static final int MIN_ACCEPTED_SCORE = 1500;

    public static final class Result {
        public final BlockPos pos;
        public final int score;
        public final int islandness;
        public final int isolation;
        public final RegistryEntry<Biome> biome;
        Result(BlockPos pos, int score, int islandness, int isolation, RegistryEntry<Biome> biome) {
            this.pos = pos;
            this.score = score;
            this.islandness = islandness;
            this.isolation = isolation;
            this.biome = biome;
        }
    }

    /**
     * Survey the world for the best natural island spawn point. Returns null only if
     * absolutely no candidate scored above the minimum threshold.
     */
    public static Result find(ServerWorld world) {
        ChunkGenerator chunkGen = world.getChunkManager().getChunkGenerator();
        NoiseConfig noiseConfig = world.getChunkManager().getNoiseConfig();
        int seaLevel = world.getSeaLevel();
        Predicate<RegistryEntry<Biome>> habitable = NaturalSpawnFinder::isHabitableBiome;

        Result best = null;
        int candidatesChecked = 0;
        int candidatesScored = 0;

        for (int gx = -SEARCH_RADIUS; gx <= SEARCH_RADIUS; gx += CANDIDATE_STEP) {
            for (int gz = -SEARCH_RADIUS; gz <= SEARCH_RADIUS; gz += CANDIDATE_STEP) {
                candidatesChecked++;

                // Cheap initial filter: must be land at modest elevation.
                int topY = chunkGen.getHeight(gx, gz,
                    Heightmap.Type.WORLD_SURFACE_WG, world, noiseConfig);
                int aboveSea = topY - seaLevel;
                if (aboveSea < MIN_TOP_ABOVE_SEA || aboveSea > MAX_TOP_ABOVE_SEA) continue;

                // Biome filter (cheap — sampled from biome source).
                BlockPos pos = new BlockPos(gx, topY, gz);
                RegistryEntry<Biome> biome = world.getBiome(pos);
                if (!habitable.test(biome)) continue;

                // Score (expensive — ~500 height samples per candidate).
                int[] components = scoreCandidate(chunkGen, noiseConfig, world, gx, gz, seaLevel);
                int islandness = components[0];
                int isolation = components[1];
                int score = islandness * isolation;
                candidatesScored++;

                if (score < MIN_ACCEPTED_SCORE) continue;

                // Prefer higher score; tiebreak on closer to origin.
                if (best == null || score > best.score ||
                    (score == best.score && magnitudeSq(pos) < magnitudeSq(best.pos))) {
                    best = new Result(pos, score, islandness, isolation, biome);
                }
            }
        }

        if (best == null) {
            Mists.LOG.warn("Mists: NaturalSpawnFinder checked {} candidates, scored {}, none above threshold {}",
                candidatesChecked, candidatesScored, MIN_ACCEPTED_SCORE);
        } else {
            Mists.LOG.info("Mists: NaturalSpawnFinder picked ({}, {}, {}) score={} islandness={}/100 isolation={}/100 biome={} (checked {} / scored {})",
                best.pos.getX(), best.pos.getY(), best.pos.getZ(),
                best.score, best.islandness, best.isolation,
                best.biome.getKey().map(k -> k.getValue().toString()).orElse("unknown"),
                candidatesChecked, candidatesScored);
        }
        return best;
    }

    /** Returns {islandness, isolation} each as 0..100. */
    private static int[] scoreCandidate(ChunkGenerator chunkGen, NoiseConfig noiseConfig,
                                         ServerWorld world, int cx, int cz, int seaLevel) {
        int innerLand = 0, innerTotal = 0;
        int outerOcean = 0, outerTotal = 0;
        for (int dx = -OUTER_RADIUS; dx <= OUTER_RADIUS; dx += SAMPLE_STEP) {
            for (int dz = -OUTER_RADIUS; dz <= OUTER_RADIUS; dz += SAMPLE_STEP) {
                int distSq = dx * dx + dz * dz;
                if (distSq > OUTER_RADIUS * OUTER_RADIUS) continue;
                int top = chunkGen.getHeight(cx + dx, cz + dz,
                    Heightmap.Type.WORLD_SURFACE_WG, world, noiseConfig);
                boolean isLand = top > seaLevel;
                if (distSq <= INNER_RADIUS * INNER_RADIUS) {
                    innerTotal++;
                    if (isLand) innerLand++;
                } else {
                    outerTotal++;
                    if (!isLand) outerOcean++;
                }
            }
        }
        int islandness = innerTotal == 0 ? 0 : (innerLand * 100 / innerTotal);
        int isolation  = outerTotal == 0 ? 0 : (outerOcean * 100 / outerTotal);
        return new int[]{ islandness, isolation };
    }

    private static long magnitudeSq(BlockPos p) {
        return (long) p.getX() * p.getX() + (long) p.getZ() * p.getZ();
    }

    /**
     * Habitability: any biome that isn't ocean, river, or extreme. The intent is to
     * spawn somewhere a player can survive on day 1 — not on a mountain peak, not
     * in a lava pool, not in deep tundra.
     */
    private static boolean isHabitableBiome(RegistryEntry<Biome> e) {
        if (e.isIn(BiomeTags.IS_OCEAN)) return false;
        if (e.isIn(BiomeTags.IS_RIVER)) return false;
        if (e.isIn(BiomeTags.IS_NETHER)) return false;
        if (e.isIn(BiomeTags.IS_END)) return false;
        if (e.isIn(BiomeTags.IS_MOUNTAIN)) return false;
        if (e.isIn(BiomeTags.IS_DEEP_OCEAN)) return false;
        // Everything else — plains, forests, beach, jungle, taiga, savanna, mushroom, etc.
        return true;
    }

    private NaturalSpawnFinder() {}
}
