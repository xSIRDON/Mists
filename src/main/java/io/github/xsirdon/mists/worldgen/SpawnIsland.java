package io.github.xsirdon.mists.worldgen;

import io.github.xsirdon.mists.Mists;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Random;

/**
 * Spawn island wrapper. The actual generation lives in {@link NaturalIslandBuilder}
 * — this class just calls it with the spawn-island specific parameters and adds
 * the spawn-pos anchoring and decoration pass.
 */
public final class SpawnIsland {

    public static final double SPAWN_ISLAND_RADIUS = 28.0;   // ~4 chunks across

    /** Maximum island height above sea level at the centre. */
    private static final int MAX_HEIGHT_ABOVE_SEA = 5;

    /** Push the perimeter ~1.5 blocks underwater so beach blends naturally into ocean. */
    private static final double EDGE_BIAS = 1.5;

    /** Legacy constants kept for backwards compatibility (ring islands + PlayerJoinClamp). */
    public static final int SPAWN_Y = 65;
    public static final int BASE_Y  = 60;

    public static void build(ServerWorld world, double cx, double cz, long worldSeed) {
        NaturalIslandBuilder.Result result = NaturalIslandBuilder.build(
            world, cx, cz, SPAWN_ISLAND_RADIUS,
            MAX_HEIGHT_ABOVE_SEA, EDGE_BIAS,
            worldSeed, worldSeed);

        // Anchor world spawn to the actual built top at the centre.
        BlockPos spawnBlock = new BlockPos((int) cx, result.centerTopY() + 1, (int) cz);
        world.setSpawnPos(spawnBlock, 0f);

        // Trees: 4–6 vanilla-shaped oaks, min 6-block spacing.
        Random treeRng = new Random(worldSeed ^ 0x7E_5_E5L);
        int treeCount = 4 + treeRng.nextInt(3);
        NaturalIslandBuilder.scatterOaks(world, result.coreTops(), treeCount, 6, treeRng);

        // Flora: tall grass + dandelions + poppies, scattered on actual grass tops.
        Random floraRng = new Random(worldSeed ^ 0xDEC0DEL);
        int tufts = 6 + floraRng.nextInt(7);    // 6–12
        int dandelions = 1 + floraRng.nextInt(4); // 1–4
        int poppies = floraRng.nextInt(3);        // 0–2
        NaturalIslandBuilder.scatterFlora(world, result.grassTops(), floraRng,
            tufts, IslandDecoration.tallGrassBlock().getDefaultState(), 1);
        NaturalIslandBuilder.scatterFlora(world, result.grassTops(), floraRng,
            dandelions, Blocks.DANDELION.getDefaultState(), 4);
        NaturalIslandBuilder.scatterFlora(world, result.grassTops(), floraRng,
            poppies, Blocks.POPPY.getDefaultState(), 4);

        Mists.LOG.info("Mists: spawn island built at ({}, ~{}, {}) sea={}",
            (int) cx, result.centerTopY(), (int) cz, result.seaLevel());
    }

    private SpawnIsland() {}
}
