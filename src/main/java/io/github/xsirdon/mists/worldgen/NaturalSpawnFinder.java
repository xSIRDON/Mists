package io.github.xsirdon.mists.worldgen;

import com.mojang.datafixers.util.Pair;
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
 * <p>v0.11.1 strategy (fast): use {@link ServerWorld#locateBiome} to harvest
 * habitable biome candidates from multiple offset start points, then verify
 * each with a small set of {@link ChunkGenerator#getHeight} samples. The
 * previous version did a grid scan with ~10,000 height calls and blocked the
 * server thread for minutes; this version does ~13 locateBiome calls and
 * ~32 height samples per surviving candidate (well under 1000 total ops).
 *
 * <p>Scoring components per candidate:
 * <ul>
 *   <li><b>islandness</b> — fraction of inner-ring cells that are land</li>
 *   <li><b>isolation</b> — fraction of outer-ring cells that are ocean</li>
 * </ul>
 *
 * <p>Final score = islandness × isolation (0..10000). Candidates with score
 * below {@link #MIN_ACCEPTED_SCORE} are rejected and we fall back to the
 * artificial NaturalIslandBuilder.
 */
public final class NaturalSpawnFinder {

    private static final int MIN_TOP_ABOVE_SEA = 1;
    private static final int MAX_TOP_ABOVE_SEA = 12;

    /** Sample radii for scoring (blocks from candidate centre). */
    private static final int INNER_RADIUS = 28;
    private static final int OUTER_RADIUS = 96;

    /** Number of sample angles per ring. 4 rings × this = total samples. */
    private static final int ANGLES_PER_RING = 8;

    private static final int MIN_ACCEPTED_SCORE = 1500;

    /** Offset start points for locateBiome. We sweep this 13-point pattern so
     *  the seed's various habitable regions are all explored. */
    private static final int[][] SEARCH_STARTS = {
        {     0,     0 },
        {  1000,     0 }, { -1000,     0 }, {     0,  1000 }, {     0, -1000 },
        {  1500,  1500 }, { -1500, -1500 }, {  1500, -1500 }, { -1500,  1500 },
        {  2500,     0 }, { -2500,     0 }, {     0,  2500 }, {     0, -2500 }
    };

    /** Per-start locateBiome search radius. */
    private static final int LOCATE_RADIUS = 1500;

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

    public static Result find(ServerWorld world) {
        long t0 = System.currentTimeMillis();
        ChunkGenerator chunkGen = world.getChunkManager().getChunkGenerator();
        NoiseConfig noiseConfig = world.getChunkManager().getNoiseConfig();
        int seaLevel = world.getSeaLevel();
        Predicate<RegistryEntry<Biome>> habitable = NaturalSpawnFinder::isHabitableBiome;

        Result best = null;
        int candidatesConsidered = 0;
        int candidatesScored = 0;

        for (int[] off : SEARCH_STARTS) {
            BlockPos start = new BlockPos(off[0], seaLevel, off[1]);
            Pair<BlockPos, RegistryEntry<Biome>> r = world.locateBiome(habitable, start, LOCATE_RADIUS, 64, 64);
            if (r == null) continue;
            candidatesConsidered++;

            BlockPos pos = r.getFirst();
            int topY = chunkGen.getHeight(pos.getX(), pos.getZ(),
                Heightmap.Type.WORLD_SURFACE_WG, world, noiseConfig);
            int aboveSea = topY - seaLevel;
            if (aboveSea < MIN_TOP_ABOVE_SEA || aboveSea > MAX_TOP_ABOVE_SEA) continue;

            int[] components = scoreCandidate(chunkGen, noiseConfig, world,
                pos.getX(), pos.getZ(), seaLevel);
            int islandness = components[0];
            int isolation = components[1];
            int score = islandness * isolation;
            candidatesScored++;

            if (score < MIN_ACCEPTED_SCORE) continue;

            if (best == null || score > best.score ||
                (score == best.score && magnitudeSq(pos) < magnitudeSq(best.pos))) {
                BlockPos top = new BlockPos(pos.getX(), topY, pos.getZ());
                best = new Result(top, score, islandness, isolation, r.getSecond());
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        if (best == null) {
            Mists.LOG.warn("Mists: NaturalSpawnFinder found 0 acceptable candidates in {}ms ({} considered, {} scored)",
                elapsed, candidatesConsidered, candidatesScored);
        } else {
            Mists.LOG.info("Mists: NaturalSpawnFinder picked ({}, {}, {}) score={} islandness={}/100 isolation={}/100 biome={} ({}ms, {} considered)",
                best.pos.getX(), best.pos.getY(), best.pos.getZ(),
                best.score, best.islandness, best.isolation,
                best.biome.getKey().map(k -> k.getValue().toString()).orElse("?"),
                elapsed, candidatesConsidered);
        }
        return best;
    }

    /** Returns {islandness, isolation} as ints 0..100.
     *  Uses 4-ring × 8-angle = 32-sample stencil — small enough to be fast. */
    private static int[] scoreCandidate(ChunkGenerator chunkGen, NoiseConfig noiseConfig,
                                         ServerWorld world, int cx, int cz, int seaLevel) {
        int innerLand = 0, innerTotal = 0;
        int outerOcean = 0, outerTotal = 0;
        // 2 inner rings + 2 outer rings × 8 angles.
        for (int ringIdx = 0; ringIdx < 4; ringIdx++) {
            // Ring radii: INNER_RADIUS/2, INNER_RADIUS, (INNER+OUTER)/2, OUTER_RADIUS.
            int ringR = switch (ringIdx) {
                case 0 -> INNER_RADIUS / 2;
                case 1 -> INNER_RADIUS;
                case 2 -> (INNER_RADIUS + OUTER_RADIUS) / 2;
                default -> OUTER_RADIUS;
            };
            boolean inner = ringIdx < 2;
            for (int a = 0; a < ANGLES_PER_RING; a++) {
                double angle = a * (2.0 * Math.PI / ANGLES_PER_RING);
                int sx = cx + (int) (Math.cos(angle) * ringR);
                int sz = cz + (int) (Math.sin(angle) * ringR);
                int top = chunkGen.getHeight(sx, sz,
                    Heightmap.Type.WORLD_SURFACE_WG, world, noiseConfig);
                boolean isLand = top > seaLevel;
                if (inner) {
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

    private static boolean isHabitableBiome(RegistryEntry<Biome> e) {
        if (e.isIn(BiomeTags.IS_OCEAN)) return false;
        if (e.isIn(BiomeTags.IS_RIVER)) return false;
        if (e.isIn(BiomeTags.IS_NETHER)) return false;
        if (e.isIn(BiomeTags.IS_END)) return false;
        if (e.isIn(BiomeTags.IS_MOUNTAIN)) return false;
        if (e.isIn(BiomeTags.IS_DEEP_OCEAN)) return false;
        return true;
    }

    private NaturalSpawnFinder() {}
}
