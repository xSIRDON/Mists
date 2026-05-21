package io.github.xsirdon.mists.worldgen;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Shared decoration helpers for both the spawn island and the ring islands.
 *
 * <p>Everything here is deterministic given the seed argument so a given world produces
 * the same scenery on repeated loads. The decoration pass runs <em>after</em> the bulk
 * terrain placement, and only paints on grass surfaces it can find at the expected y.
 */
public final class IslandDecoration {

    private IslandDecoration() {}

    /**
     * Place a small 3x3 freshwater pond (1 block deep) centered at (px, pz) on a grass surface.
     * The cell directly under the pond is left as dirt so light filters through naturally.
     */
    public static void carvePond(ServerWorld world, int px, int pz, int surfaceY) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos top = new BlockPos(px + dx, surfaceY, pz + dz);
                // Only carve if the surface here is grass (avoid drilling through sand/beach).
                if (!world.getBlockState(top).isOf(Blocks.GRASS_BLOCK)) continue;
                world.setBlockState(top, Blocks.WATER.getDefaultState(), 2);
                world.setBlockState(new BlockPos(px + dx, surfaceY - 1, pz + dz),
                    Blocks.DIRT.getDefaultState(), 2);
            }
        }
    }

    /** Scatter a 2x2 patch of dirt over existing grass at (px, pz). Used for weathered variety. */
    public static void dirtPatch(ServerWorld world, int px, int pz, int surfaceY) {
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                BlockPos p = new BlockPos(px + dx, surfaceY, pz + dz);
                if (world.getBlockState(p).isOf(Blocks.GRASS_BLOCK)) {
                    world.setBlockState(p, Blocks.DIRT.getDefaultState(), 2);
                }
            }
        }
    }

    /**
     * Place a single plant block at (x, surfaceY + 1) iff the surface is grass and the cell above
     * is currently air. Returns true if placement happened.
     */
    public static boolean placePlant(ServerWorld world, int x, int z, int surfaceY, Block plant) {
        BlockPos surface = new BlockPos(x, surfaceY, z);
        if (!world.getBlockState(surface).isOf(Blocks.GRASS_BLOCK)) return false;
        BlockPos above = surface.up();
        if (!world.getBlockState(above).isAir()) return false;
        world.setBlockState(above, plant.getDefaultState(), 2);
        return true;
    }

    /**
     * Place a small oak tree at (x, surfaceY + 1, z): 5-block trunk with a tapered 5x5x3 canopy.
     * Only places if the surface is grass and there's room. Idempotent-ish — calling on top of an
     * existing tree just overwrites in place.
     */
    public static void placeOak(ServerWorld world, int x, int z, int surfaceY) {
        BlockPos surface = new BlockPos(x, surfaceY, z);
        if (!world.getBlockState(surface).isOf(Blocks.GRASS_BLOCK)) return;
        int y = surfaceY + 1;
        for (int i = 0; i < 5; i++) {
            world.setBlockState(new BlockPos(x, y + i, z), Blocks.OAK_LOG.getDefaultState(), 2);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 3; dy <= 5; dy++) {
                    BlockPos p = new BlockPos(x + dx, y + dy, z + dz);
                    if (world.getBlockState(p).isAir()
                        && Math.abs(dx) + Math.abs(dz) + Math.abs(dy - 4) <= 4) {
                        world.setBlockState(p, Blocks.OAK_LEAVES.getDefaultState(), 2);
                    }
                }
            }
        }
    }

    /**
     * Resolve the "tall grass" block. Yarn 1.20.1 renamed GRASS -> SHORT_GRASS in some builds;
     * we try the new name first via reflection and fall back to {@link Blocks#GRASS}. Both
     * exist in 1.20.1 yarn 10 — using reflection avoids a hard compile dep on one or the other.
     */
    public static Block tallGrassBlock() {
        try {
            java.lang.reflect.Field f = Blocks.class.getField("SHORT_GRASS");
            return (Block) f.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return Blocks.GRASS;
        }
    }

    /** Collected list of (x, z) candidate grass cells inside the island for decoration sampling. */
    public static List<int[]> collectGrassSurface(ServerWorld world, int cx, int cz,
                                                  int searchRadius, int surfaceY) {
        List<int[]> grass = new ArrayList<>();
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                int x = cx + dx, z = cz + dz;
                if (world.getBlockState(new BlockPos(x, surfaceY, z)).isOf(Blocks.GRASS_BLOCK)) {
                    grass.add(new int[]{ x, z });
                }
            }
        }
        return grass;
    }

    /**
     * Scatter plants over candidate grass cells. Picks {@code count} unique cells (subject to
     * a min-distance constraint between any two selected cells), then places {@code plant} above
     * each.
     */
    public static int scatterPlants(ServerWorld world, List<int[]> candidates, Random rng,
                                    int count, Block plant, int surfaceY, int minSpacingSq) {
        if (candidates.isEmpty() || count <= 0) return 0;
        Set<Long> used = new HashSet<>();
        List<int[]> taken = new ArrayList<>();
        int placed = 0;
        int attempts = 0;
        int maxAttempts = count * 12;
        while (placed < count && attempts < maxAttempts) {
            attempts++;
            int[] c = candidates.get(rng.nextInt(candidates.size()));
            long key = ((long) c[0] << 32) ^ (c[1] & 0xFFFFFFFFL);
            if (!used.add(key)) continue;
            boolean tooClose = false;
            for (int[] p : taken) {
                int ddx = p[0] - c[0], ddz = p[1] - c[1];
                if (ddx * ddx + ddz * ddz < minSpacingSq) { tooClose = true; break; }
            }
            if (tooClose) continue;
            if (placePlant(world, c[0], c[1], surfaceY, plant)) {
                taken.add(c);
                placed++;
            }
        }
        return placed;
    }
}
