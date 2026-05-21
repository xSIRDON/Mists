package io.github.xsirdon.mists.worldgen;

import io.github.xsirdon.mists.Mists;
import io.github.xsirdon.mists.MistsConstants;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

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

        SpawnIsland.build(world, seed);
        data.islands.add(new MistsWorldData.IslandRecord(1, 0, 0, SpawnIsland.SPAWN_ISLAND_RADIUS, seed));

        placeRing(world, data, rng, 2, MistsConstants.TIER_2_RADIUS,  6 * 16,  16 * 16);
        placeRing(world, data, rng, 3, MistsConstants.TIER_3_RADIUS, 10 * 16,  28 * 16);
        placeRing(world, data, rng, 4, MistsConstants.TIER_4_RADIUS, 16 * 16,  48 * 16);

        // Inter-island ocean carve (out to slightly beyond tier 4).
        carveOcean(world, data, (int)(MistsConstants.TIER_4_RADIUS + 100));

        Mists.LOG.info("Mists: archipelago placement complete ({} islands)", data.islands.size());
    }

    private static void placeRing(ServerWorld world, MistsWorldData data, Random rng,
                                  int tier, double ringRadius, int minArea, int maxArea) {
        int count = 3 + rng.nextInt(3);  // 3–5
        double angleStep = (Math.PI * 2) / count;
        double angleJitter = angleStep * 0.4;
        double baseAngle = rng.nextDouble() * Math.PI * 2;

        for (int i = 0; i < count; i++) {
            double angle = baseAngle + i * angleStep + (rng.nextDouble() - 0.5) * angleJitter;
            double r = ringRadius + (rng.nextDouble() - 0.5) * 80.0;
            double cx = Math.cos(angle) * r;
            double cz = Math.sin(angle) * r;

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

    /** Inside the ring zone, any land block above sea level not part of a placed island is drowned. */
    private static void carveOcean(ServerWorld world, MistsWorldData data, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                double d = Math.sqrt((double) x * x + (double) z * z);
                if (d > radius) continue;
                if (isInsideAnyIsland(data, x, z)) continue;
                OceanCarver.carveColumnToOcean(world, x, z);
            }
        }
    }

    private static boolean isInsideAnyIsland(MistsWorldData data, int x, int z) {
        for (MistsWorldData.IslandRecord r : data.islands) {
            double dx = x - r.cx, dz = z - r.cz;
            if (dx * dx + dz * dz <= (r.radius * 1.05) * (r.radius * 1.05)) return true;
        }
        return false;
    }

    private IslandPlacer() {}
}
