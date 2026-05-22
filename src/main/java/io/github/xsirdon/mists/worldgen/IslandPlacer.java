package io.github.xsirdon.mists.worldgen;

import com.mojang.datafixers.util.Pair;
import io.github.xsirdon.mists.Mists;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

import java.util.ArrayList;
import java.util.List;
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

    private static void place(ServerWorld world, MistsWorldData data) {
        long seed = world.getSeed();

        // ── v0.11: Prefer a NATURAL island from the seed's terrain ────────────
        // ChunkGenerator.getHeight samples the noise function directly so we can
        // survey thousands of candidate positions without loading any chunks.
        // We pick the best-scoring "small isolated landmass" and set spawn there.
        // No block placement happens at all — the actual island is whatever
        // vanilla worldgen + biome surface rules + carvers produce. Caves spawn.
        // Ores spawn. Trees grow per the biome. It's the seed's island.
        NaturalSpawnFinder.Result natural = NaturalSpawnFinder.find(world);

        if (natural != null) {
            data.spawnX = natural.pos.getX();
            data.spawnZ = natural.pos.getZ();
            BlockPos spawnPos = natural.pos.up();
            world.setSpawnPos(spawnPos, 0f);

            // Measure the actual island extent so the tier-1 mist wraps the island
            // closely. ~25 blocks of ocean buffer past the island edge.
            int islandRadius = NaturalSpawnFinder.measureIslandExtent(world,
                (int) data.spawnX, (int) data.spawnZ);
            data.tier1RadiusOverride = islandRadius + 25;
            data.islands.add(new MistsWorldData.IslandRecord(
                1, data.spawnX, data.spawnZ, islandRadius, seed));
            Mists.LOG.info("Mists: using natural island at ({}, {}) — measured radius {}, tier1 mist {}",
                (int) data.spawnX, (int) data.spawnZ, islandRadius, (int) data.tier1RadiusOverride);
        } else {
            // ── Fallback: build an artificial island via NaturalIslandBuilder ──
            // v0.12: this path now uses 3D noise cave carving + vanilla configured
            // features (TreeConfiguredFeatures.OAK, VegetationConfiguredFeatures.*,
            // OreConfiguredFeatures.*). The result is built from the ocean floor up
            // through stone (with carved caves), dirt, then grass cap with vanilla
            // trees/grass/flowers placed by Minecraft's own feature generators.
            Mists.LOG.warn("Mists: no suitable natural island in the seed; building artificial island via vanilla feature pipeline.");
            BlockPos found = findOpenOcean(world);
            if (found == null) {
                data.spawnX = 0.0;
                data.spawnZ = 0.0;
                Mists.LOG.warn("Mists: no open ocean found either; falling back to (0,0).");
            } else {
                data.spawnX = found.getX();
                data.spawnZ = found.getZ();
            }
            double islandRadius = SpawnIsland.SPAWN_ISLAND_RADIUS;
            NaturalIslandBuilder.Result result = NaturalIslandBuilder.build(
                world, data.spawnX, data.spawnZ, islandRadius,
                /*maxHeight*/ 5, /*edgeBias*/ 1.5, seed, seed);
            BlockPos spawnPos = new BlockPos((int) data.spawnX, result.centerTopY() + 1, (int) data.spawnZ);
            world.setSpawnPos(spawnPos, 0f);
            data.tier1RadiusOverride = islandRadius + 25;
            data.islands.add(new MistsWorldData.IslandRecord(
                1, data.spawnX, data.spawnZ, islandRadius, seed));
        }

        // ── Ring placement DISABLED for v0.11 ─────────────────────────────────
        // Focus is entirely on getting the spawn island to feel like natural
        // Minecraft terrain. Rings will return in v0.12 once we figure out how
        // to find natural islands at fixed radii from spawn (or accept that
        // they'll be vanilla "whatever the seed put at this distance" terrain).

        Mists.LOG.info("Mists: spawn placement complete ({} record)", data.islands.size());
    }

    private IslandPlacer() {}
}
