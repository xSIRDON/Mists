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

    /** Try several ocean predicates in order, returning the first match or null. */
    private static Pair<BlockPos, RegistryEntry<Biome>> findOcean(ServerWorld world, BlockPos start) {
        Predicate<RegistryEntry<Biome>> primary = e -> e.isIn(BiomeTags.IS_OCEAN);
        Pair<BlockPos, RegistryEntry<Biome>> r = world.locateBiome(primary, start, 4000, 32, 64);
        if (r != null) return r;

        // Fallback: match explicit ocean biome keys in case the IS_OCEAN tag was stripped
        // by datapack/biome-modification mods.
        Predicate<RegistryEntry<Biome>> keys = e ->
            e.matchesKey(BiomeKeys.OCEAN)              ||
            e.matchesKey(BiomeKeys.DEEP_OCEAN)         ||
            e.matchesKey(BiomeKeys.WARM_OCEAN)         ||
            e.matchesKey(BiomeKeys.COLD_OCEAN)         ||
            e.matchesKey(BiomeKeys.LUKEWARM_OCEAN)     ||
            e.matchesKey(BiomeKeys.FROZEN_OCEAN)       ||
            e.matchesKey(BiomeKeys.DEEP_COLD_OCEAN)    ||
            e.matchesKey(BiomeKeys.DEEP_LUKEWARM_OCEAN)||
            e.matchesKey(BiomeKeys.DEEP_FROZEN_OCEAN);
        return world.locateBiome(keys, start, 4000, 32, 64);
    }

    private static void place(ServerWorld world, MistsWorldData data) {
        long seed = world.getSeed();
        Random rng = new Random(seed ^ 0x4D49535453L);  // "MISTS" as bytes

        // Locate the nearest ocean biome (any variant) to host the spawn island.
        Pair<BlockPos, RegistryEntry<Biome>> result = findOcean(world, new BlockPos(0, 64, 0));

        if (result == null) {
            Mists.LOG.warn("Mists: no ocean biome found within 4000 blocks; falling back to (0,0) — spawn will sit on natural terrain with a forced ocean ring");
            data.spawnX = 0.0;
            data.spawnZ = 0.0;
        } else {
            BlockPos found = result.getFirst();
            data.spawnX = found.getX();
            data.spawnZ = found.getZ();
            Mists.LOG.info("Mists: spawn island anchor located at ({}, {})", (int) data.spawnX, (int) data.spawnZ);
        }

        SpawnIsland.build(world, data.spawnX, data.spawnZ, seed);
        data.islands.add(new MistsWorldData.IslandRecord(
            1, data.spawnX, data.spawnZ, SpawnIsland.SPAWN_ISLAND_RADIUS, seed));

        // Move the world spawn to the new center.
        world.setSpawnPos(new BlockPos((int) data.spawnX, SpawnIsland.SPAWN_Y + 1, (int) data.spawnZ), 0f);

        // v0.7: Ring placement RE-ENABLED. The EC empty-forecast crash that motivated us
        // to disable this in v0.6 is now suppressed by EnhancedCelestialsForecastFixMixin.
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

            buildIsland(world, cx, cz, islandRadius, shapeSeed);
            data.islands.add(new MistsWorldData.IslandRecord(tier, cx, cz, islandRadius, shapeSeed));
        }
    }

    private static void buildIsland(ServerWorld world, double cx, double cz, double radius, long shapeSeed) {
        IslandShape shape = new IslandShape(cx, cz, radius, shapeSeed);
        int surfaceY = OceanCarver.SEA_LEVEL + 3;
        int from = (int) (-radius * 1.5);
        int to   = (int) ( radius * 1.5);
        double beachThreshold = radius * 0.85;

        for (int dx = from; dx <= to; dx++) {
            for (int dz = from; dz <= to; dz++) {
                int x = (int) cx + dx, z = (int) cz + dz;
                if (shape.contains(x, z)) {
                    double ddx = x - cx, ddz = z - cz;
                    double dist = Math.sqrt(ddx * ddx + ddz * ddz);
                    for (int y = SpawnIsland.BASE_Y; y <= OceanCarver.SEA_LEVEL + 2; y++) {
                        world.setBlockState(new BlockPos(x, y, z),
                            Blocks.DIRT.getDefaultState(), 2);
                    }
                    // Outer ~15% of radius → sand beach. Inner → grass.
                    if (dist > beachThreshold) {
                        world.setBlockState(new BlockPos(x, surfaceY, z),
                            Blocks.SAND.getDefaultState(), 2);
                    } else {
                        world.setBlockState(new BlockPos(x, surfaceY, z),
                            Blocks.GRASS_BLOCK.getDefaultState(), 2);
                    }
                }
            }
        }

        // Optional central hill bump for larger islands (≥ 25 radius). One extra grass block
        // at center; scattered second-layer cells within R*0.25 with 30% probability.
        if (radius > 25) {
            Random hillRng = new Random(shapeSeed ^ 0xBADD1E5L);
            double hillR = radius * 0.25;
            for (int dx = -(int) hillR; dx <= (int) hillR; dx++) {
                for (int dz = -(int) hillR; dz <= (int) hillR; dz++) {
                    int x = (int) cx + dx, z = (int) cz + dz;
                    if (dx * dx + dz * dz > hillR * hillR) continue;
                    if (!world.getBlockState(new BlockPos(x, surfaceY, z)).isOf(Blocks.GRASS_BLOCK))
                        continue;
                    world.setBlockState(new BlockPos(x, surfaceY + 1, z),
                        Blocks.GRASS_BLOCK.getDefaultState(), 2);
                    if (hillRng.nextDouble() < 0.30) {
                        world.setBlockState(new BlockPos(x, surfaceY + 2, z),
                            Blocks.GRASS_BLOCK.getDefaultState(), 2);
                    }
                }
            }
        }

        decorateRingIsland(world, (int) cx, (int) cz, radius, surfaceY, shapeSeed);
    }

    /**
     * Decorate a ring island: 1-2 ponds (if R > 30), trees (~1 per 40 grass blocks²),
     * tall grass / flowers (~1 per 25 grass blocks²). All seeded from {@code shapeSeed}
     * so reloads produce identical scenery.
     */
    private static void decorateRingIsland(ServerWorld world, int cx, int cz, double radius,
                                           int surfaceY, long shapeSeed) {
        Random rng = new Random(shapeSeed ^ 0xDEC0DEL);
        int searchRadius = (int) Math.ceil(radius) + 2;
        // Re-scan to find the actual surface (post-hill); ring islands may have y = surfaceY or surfaceY+1.
        // We decorate on the configured surfaceY (initial grass layer); plants on top of hill bumps
        // would float, so collecting at the base surface keeps the plant placement valid.
        List<int[]> grass = IslandDecoration.collectGrassSurface(
            world, cx, cz, searchRadius, surfaceY);
        if (grass.isEmpty()) return;

        // 1. Ponds — 1 or 2 for R > 30.
        if (radius > 30) {
            int pondCount = 1 + (rng.nextInt(100) < 40 ? 1 : 0);
            double coreR = radius * 0.5;
            for (int p = 0; p < pondCount; p++) {
                for (int attempt = 0; attempt < 12; attempt++) {
                    double a = rng.nextDouble() * Math.PI * 2;
                    double r = rng.nextDouble() * coreR;
                    int px = cx + (int) Math.round(Math.cos(a) * r);
                    int pz = cz + (int) Math.round(Math.sin(a) * r);
                    if (world.getBlockState(new BlockPos(px, surfaceY, pz)).isOf(Blocks.GRASS_BLOCK)) {
                        IslandDecoration.carvePond(world, px, pz, surfaceY);
                        break;
                    }
                }
            }
        }

        // Recollect after pond carving.
        grass = IslandDecoration.collectGrassSurface(world, cx, cz, searchRadius, surfaceY);
        if (grass.isEmpty()) return;

        // 2. Trees: ~1 per 40 blocks² of grass surface; min spacing ~4 blocks between trunks.
        int treeCount = Math.max(0, grass.size() / 40);
        scatterTrees(world, grass, rng, treeCount, surfaceY);

        // Recollect — trees consumed grass cells (or at least their canopies shade them).
        grass = IslandDecoration.collectGrassSurface(world, cx, cz, searchRadius, surfaceY);

        // 3. Tall grass tufts: ~1 per 25 blocks² of grass surface.
        Block tallGrass = IslandDecoration.tallGrassBlock();
        int tufts = Math.max(1, grass.size() / 25);
        IslandDecoration.scatterPlants(world, grass, rng, tufts, tallGrass, surfaceY, 1);

        // 4. Dandelions (1 per 60) + poppies (1 per 90).
        int dandelions = Math.max(0, grass.size() / 60);
        IslandDecoration.scatterPlants(world, grass, rng, dandelions, Blocks.DANDELION, surfaceY, 4);
        int poppies = Math.max(0, grass.size() / 90);
        IslandDecoration.scatterPlants(world, grass, rng, poppies, Blocks.POPPY, surfaceY, 4);
    }

    private static void scatterTrees(ServerWorld world, List<int[]> candidates, Random rng,
                                     int count, int surfaceY) {
        if (candidates.isEmpty() || count <= 0) return;
        java.util.List<int[]> placed = new java.util.ArrayList<>();
        int attempts = 0;
        int placedCount = 0;
        int maxAttempts = count * 15;
        while (placedCount < count && attempts < maxAttempts) {
            attempts++;
            int[] c = candidates.get(rng.nextInt(candidates.size()));
            boolean tooClose = false;
            for (int[] pp : placed) {
                int ddx = pp[0] - c[0], ddz = pp[1] - c[1];
                if (ddx * ddx + ddz * ddz < 16) { tooClose = true; break; }
            }
            if (tooClose) continue;
            IslandDecoration.placeOak(world, c[0], c[1], surfaceY);
            placed.add(c);
            placedCount++;
        }
    }

    private IslandPlacer() {}
}
