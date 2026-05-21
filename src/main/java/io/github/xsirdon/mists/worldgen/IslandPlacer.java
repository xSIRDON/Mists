package io.github.xsirdon.mists.worldgen;

import com.mojang.datafixers.util.Pair;
import io.github.xsirdon.mists.Mists;
import io.github.xsirdon.mists.MistsConstants;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.block.Blocks;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

import java.util.Random;

public final class IslandPlacer {

    public static void register() {
        ServerWorldEvents.LOAD.register((server, world) -> {
            if (world.getRegistryKey() != World.OVERWORLD) return;
            MistsWorldData data = MistsWorldData.get(world);
            if (data.placed) return;
            place(world, data);
            data.placed = true;
            data.markDirty();
        });
    }

    private static void place(ServerWorld world, MistsWorldData data) {
        long seed = world.getSeed();
        Random rng = new Random(seed ^ 0x4D49535453L);  // "MISTS" as bytes

        // Locate the nearest ocean biome (any variant) to host the spawn island.
        Pair<BlockPos, RegistryEntry<Biome>> result = world.locateBiome(
            biomeEntry -> biomeEntry.isIn(BiomeTags.IS_OCEAN),
            new BlockPos(0, 64, 0),
            2000,   // horizontal radius (blocks)
            32,     // horizontalBlockCheckInterval
            64      // verticalBlockCheckInterval
        );

        if (result == null) {
            Mists.LOG.warn("Mists: no ocean biome found within 2000 blocks of (0,0); falling back to (0,0)");
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

        placeRing(world, data, rng, data.spawnX, data.spawnZ, 2, MistsConstants.TIER_2_RADIUS,  6 * 16,  16 * 16);
        placeRing(world, data, rng, data.spawnX, data.spawnZ, 3, MistsConstants.TIER_3_RADIUS, 10 * 16,  28 * 16);
        placeRing(world, data, rng, data.spawnX, data.spawnZ, 4, MistsConstants.TIER_4_RADIUS, 16 * 16,  48 * 16);

        // v0.1: Inter-island ocean carve is DISABLED. Doing ~12M synchronous block updates
        // during world load froze the server thread. Players will see natural vanilla land
        // between islands instead of ocean — aesthetic regression only, the mist boundary
        // still gates progression correctly. v0.2 reintroduces this as paced background work.

        Mists.LOG.info("Mists: archipelago placement complete ({} islands, ocean carve skipped for v0.1)", data.islands.size());
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
        int from = (int) (-radius * 1.5);
        int to   = (int) ( radius * 1.5);
        for (int dx = from; dx <= to; dx++) {
            for (int dz = from; dz <= to; dz++) {
                int x = (int) cx + dx, z = (int) cz + dz;
                if (shape.contains(x, z)) {
                    for (int y = SpawnIsland.BASE_Y; y <= OceanCarver.SEA_LEVEL + 2; y++) {
                        world.setBlockState(new BlockPos(x, y, z),
                            Blocks.DIRT.getDefaultState(), 2);
                    }
                    world.setBlockState(new BlockPos(x, OceanCarver.SEA_LEVEL + 3, z),
                        Blocks.GRASS_BLOCK.getDefaultState(), 2);
                }
            }
        }
    }

    private IslandPlacer() {}
}
