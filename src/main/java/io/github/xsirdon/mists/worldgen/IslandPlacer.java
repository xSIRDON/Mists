package io.github.xsirdon.mists.worldgen;

import com.mojang.datafixers.util.Pair;
import io.github.xsirdon.mists.Mists;
import io.github.xsirdon.mists.MistsConstants;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

public final class IslandPlacer {

    public static void register() {
        // v0.7: back to load-time placement. The EC empty-forecast crash is now suppressed
        // by EnhancedCelestialsForecastFixMixin, so we no longer need to dance around it.
        ServerWorldEvents.LOAD.register((server, world) -> {
            if (world.getRegistryKey() != World.OVERWORLD) return;
            MistsWorldData data = MistsWorldData.get(world);
            if (data.placed) return;
            place(world, data);
            data.placed = true;
            data.markDirty();
        });
    }

    /** Combined "is this an ocean biome" predicate covering both the IS_OCEAN tag
     *  and explicit BiomeKeys variants, so it survives datapack biome modifications
     *  that strip the tag. */
    private static final Predicate<RegistryEntry<Biome>> ANY_OCEAN = e ->
        e.isIn(BiomeTags.IS_OCEAN)              ||
        e.matchesKey(BiomeKeys.OCEAN)           ||
        e.matchesKey(BiomeKeys.DEEP_OCEAN)      ||
        e.matchesKey(BiomeKeys.WARM_OCEAN)      ||
        e.matchesKey(BiomeKeys.COLD_OCEAN)      ||
        e.matchesKey(BiomeKeys.LUKEWARM_OCEAN)  ||
        e.matchesKey(BiomeKeys.FROZEN_OCEAN)    ||
        e.matchesKey(BiomeKeys.DEEP_COLD_OCEAN) ||
        e.matchesKey(BiomeKeys.DEEP_LUKEWARM_OCEAN) ||
        e.matchesKey(BiomeKeys.DEEP_FROZEN_OCEAN);

    /**
     * Find a genuinely open patch of ocean (lots of ocean in every direction within 300
     * blocks). Cast a wide net of candidate ocean points using locateBiome from many
     * offset start positions, then score each by surrounding-ocean ratio and pick the
     * best one. Prefers candidates closer to world origin on ties.
     *
     * <p>Returns null only if no candidate could be found at all — extremely rare in
     * any normal Minecraft seed.
     */
    private static BlockPos findOpenOcean(ServerWorld world) {
        int seaY = world.getSeaLevel();

        // Spiral of offset start points so locateBiome explores diverse areas.
        int[][] offsets = {
            {     0,     0 },
            {  1500,     0 }, { -1500,     0 }, {     0,  1500 }, {     0, -1500 },
            {  1500,  1500 }, { -1500, -1500 }, {  1500, -1500 }, { -1500,  1500 },
            {  3000,     0 }, { -3000,     0 }, {     0,  3000 }, {     0, -3000 },
            {  2500,  2500 }, { -2500, -2500 }, {  2500, -2500 }, { -2500,  2500 }
        };

        List<BlockPos> candidates = new ArrayList<>();
        for (int[] off : offsets) {
            BlockPos searchStart = new BlockPos(off[0], seaY, off[1]);
            Pair<BlockPos, RegistryEntry<Biome>> r =
                world.locateBiome(ANY_OCEAN, searchStart, 2000, 64, 64);
            if (r != null) candidates.add(r.getFirst());
        }

        if (candidates.isEmpty()) return null;

        BlockPos best = null;
        int bestScore = -1;
        long bestDistFromZero = Long.MAX_VALUE;

        for (BlockPos c : candidates) {
            int score = scoreOpenness(world, c, 300);
            long d2 = (long) c.getX() * c.getX() + (long) c.getZ() * c.getZ();
            // Prefer higher score; on ties prefer closer to origin (less player travel later).
            if (score > bestScore || (score == bestScore && d2 < bestDistFromZero)) {
                best = c;
                bestScore = score;
                bestDistFromZero = d2;
            }
        }

        Mists.LOG.info("Mists: best ocean candidate has openness {}/100 at ({}, {})",
            bestScore, best.getX(), best.getZ());
        return best;
    }

    /** Sample biomes at four concentric rings × 16 angles around {@code center}.
     *  Return the percentage of samples that are ocean (0..100). */
    private static int scoreOpenness(ServerWorld world, BlockPos center, int radius) {
        int oceanCount = 0;
        int totalChecks = 0;
        for (int ringIdx = 1; ringIdx <= 4; ringIdx++) {
            int ringR = radius * ringIdx / 4;
            for (int a = 0; a < 16; a++) {
                double angle = Math.toRadians(a * 22.5);
                int dx = (int) (Math.cos(angle) * ringR);
                int dz = (int) (Math.sin(angle) * ringR);
                BlockPos check = new BlockPos(center.getX() + dx, center.getY(), center.getZ() + dz);
                if (ANY_OCEAN.test(world.getBiome(check))) oceanCount++;
                totalChecks++;
            }
        }
        return oceanCount * 100 / totalChecks;
    }

    /** True if a candidate (cx, cz) sits in ocean — used by ring placement to skip
     *  slots that fall on natural land. */
    private static boolean isOceanAt(ServerWorld world, double cx, double cz) {
        return ANY_OCEAN.test(world.getBiome(
            new BlockPos((int) cx, world.getSeaLevel(), (int) cz)));
    }

    private static void place(ServerWorld world, MistsWorldData data) {
        long seed = world.getSeed();
        Random rng = new Random(seed ^ 0x4D49535453L);  // "MISTS" as bytes

        // Locate genuinely open ocean (lots of ocean in every direction, not just an
        // ocean biome adjacent to coastline).
        BlockPos found = findOpenOcean(world);

        if (found == null) {
            Mists.LOG.warn("Mists: no ocean candidate located; falling back to (0,0). Spawn may sit on natural terrain.");
            data.spawnX = 0.0;
            data.spawnZ = 0.0;
        } else {
            data.spawnX = found.getX();
            data.spawnZ = found.getZ();
            Mists.LOG.info("Mists: spawn island anchor at ({}, {})", (int) data.spawnX, (int) data.spawnZ);
        }

        // SpawnIsland.build now handles setSpawnPos internally using the correct top y.
        SpawnIsland.build(world, data.spawnX, data.spawnZ, seed);
        data.islands.add(new MistsWorldData.IslandRecord(
            1, data.spawnX, data.spawnZ, SpawnIsland.SPAWN_ISLAND_RADIUS, seed));

        // Ring islands: still placed, but each slot is checked for "is this ocean?"
        // before building. Slots that fall on natural land are skipped — no more
        // synthetic grass patches embedded in vanilla forests.
        placeRing(world, data, rng, data.spawnX, data.spawnZ, 2, MistsConstants.TIER_2_RADIUS,  6 * 16,  16 * 16);
        placeRing(world, data, rng, data.spawnX, data.spawnZ, 3, MistsConstants.TIER_3_RADIUS, 10 * 16,  28 * 16);
        placeRing(world, data, rng, data.spawnX, data.spawnZ, 4, MistsConstants.TIER_4_RADIUS, 16 * 16,  48 * 16);

        Mists.LOG.info("Mists: archipelago placement complete ({} islands)", data.islands.size());
    }

    private static void placeRing(ServerWorld world, MistsWorldData data, Random rng,
                                  double centerX, double centerZ,
                                  int tier, double ringRadius, int minArea, int maxArea) {
        int count = 3 + rng.nextInt(3);  // 3–5
        double angleStep = (Math.PI * 2) / count;
        double angleJitter = angleStep * 0.4;
        double baseAngle = rng.nextDouble() * Math.PI * 2;

        for (int i = 0; i < count; i++) {
            double angle = baseAngle + i * angleStep + (rng.nextDouble() - 0.5) * angleJitter;
            double r = ringRadius + (rng.nextDouble() - 0.5) * 80.0;
            double cx = centerX + Math.cos(angle) * r;
            double cz = centerZ + Math.sin(angle) * r;

            int area = minArea + rng.nextInt(maxArea - minArea + 1);
            double islandRadius = Math.sqrt(area / Math.PI);
            long shapeSeed = rng.nextLong();

            // Skip ring slots that fall on natural land — placing a synthetic island
            // on top of a forest looks awful. The mist boundary still gates progression
            // regardless of whether a tier-N island exists in every slot.
            if (!isOceanAt(world, cx, cz)) {
                Mists.LOG.info("Mists: skipping tier {} slot at ({}, {}) — not ocean", tier, (int) cx, (int) cz);
                continue;
            }

            buildIsland(world, cx, cz, islandRadius, shapeSeed);
            data.islands.add(new MistsWorldData.IslandRecord(tier, cx, cz, islandRadius, shapeSeed));
        }
    }

    private static void buildIsland(ServerWorld world, double cx, double cz, double radius, long shapeSeed) {
        // Scale max height with island size, like vanilla beach islands —
        // a tiny island is ~2 blocks high, a huge island is ~7 blocks high.
        int maxHeight = (int) Math.max(2, Math.min(7, radius / 8.0 + 1));
        // Wider islands need more aggressive edge taper so they don't end abruptly.
        double edgeBias = 1.5;

        NaturalIslandBuilder.Result result = NaturalIslandBuilder.build(
            world, cx, cz, radius, maxHeight, edgeBias, shapeSeed, shapeSeed);

        // Tree count proportional to grass area (~ 1 oak per 50 blocks² of grass surface).
        Random rng = new Random(shapeSeed ^ 0xDEC0DEL);
        int treeCount = Math.max(2, result.coreTops().size() / 50);
        NaturalIslandBuilder.scatterOaks(world, result.coreTops(), treeCount, 5, rng);

        // Flora proportional to grass area.
        int grassSize = result.grassTops().size();
        int tufts = Math.max(2, grassSize / 25);
        int dandelions = Math.max(0, grassSize / 60);
        int poppies = Math.max(0, grassSize / 90);
        NaturalIslandBuilder.scatterFlora(world, result.grassTops(), rng,
            tufts, IslandDecoration.tallGrassBlock().getDefaultState(), 1);
        NaturalIslandBuilder.scatterFlora(world, result.grassTops(), rng,
            dandelions, Blocks.DANDELION.getDefaultState(), 4);
        NaturalIslandBuilder.scatterFlora(world, result.grassTops(), rng,
            poppies, Blocks.POPPY.getDefaultState(), 4);
    }

    private IslandPlacer() {}
}
