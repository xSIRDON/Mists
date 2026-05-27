package io.github.xsirdon.mists.worldgen;

import io.github.xsirdon.mists.Mists;
import io.github.xsirdon.mists.worldgen.density.MistsIslandConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.StairShape;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.util.Random;

/**
 * v0.21: programmatic placement of a small ruined wooden hut at the centre of
 * the spawn island. The first thing a player sees on spawn. Deliberately
 * weather-beaten — the roof has a couple of missing blocks and one wall has
 * mossy-cobble patches — to suggest "someone was here, a long time ago".
 *
 * <p>Footprint: 5 wide (x-axis) × 4 deep (z-axis), 4 blocks tall. Doorway is a
 * 1×2 gap in the south wall (positive z side) so the player can walk in.
 *
 * <p>Determinism: every randomised choice (which roof blocks are missing,
 * which walls are weathered) is driven by a {@link Random} seeded from
 * {@link MistsIslandConfig#worldSeed}, so the same world seed always produces
 * the same hut.
 */
public final class SpawnHutPlacer {

    /** Half-extent of the foundation along x. The foundation is (HALF_X*2+1) wide. */
    private static final int HALF_X = 2;
    /** Depth along z. The footprint spans z=centreZ-2..centreZ+1 (4 blocks). */
    private static final int Z_BACK  = -2;
    private static final int Z_FRONT =  1;
    /** Wall height in blocks above the foundation. */
    private static final int WALL_HEIGHT = 3;

    public static final Identifier CHEST_LOOT =
        new Identifier("mists", "chests/spawn_hut");

    private SpawnHutPlacer() {}

    /**
     * Place the hut centred on (cfg.cx, cfg.cz). The foundation sits on the
     * world-surface heightmap at that column. Safe to call on the main server
     * thread (uses synchronous setBlockState).
     */
    public static void place(ServerWorld world, MistsIslandConfig cfg) {
        int cx = cfg.cx;
        int cz = cfg.cz;
        int surfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, cx, cz);
        // Ensure we're at or above sea level — the heightmap returns the y of
        // the first non-solid block, which on the island top is one above
        // surface. We want to lay the floor *on* the surface, so subtract 1.
        int floorY = Math.max(surfaceY, cfg.seaLevel + 1);

        Random rng = new Random(cfg.worldSeed ^ 0x48555431L); // "HUT1"

        // ── Foundation: 5×4 stripped oak log floor ─────────────────────────
        BlockState floor = Blocks.STRIPPED_OAK_LOG.getDefaultState();
        for (int dx = -HALF_X; dx <= HALF_X; dx++) {
            for (int dz = Z_BACK; dz <= Z_FRONT; dz++) {
                setBlock(world, cx + dx, floorY, cz + dz, floor);
            }
        }

        // ── Walls (oak planks, occasional mossy-cobble weathering) ─────────
        BlockState planks = Blocks.OAK_PLANKS.getDefaultState();
        BlockState mossy  = Blocks.MOSSY_COBBLESTONE.getDefaultState();

        for (int wy = 1; wy <= WALL_HEIGHT; wy++) {
            int y = floorY + wy;

            // North wall (z = Z_BACK)
            for (int dx = -HALF_X; dx <= HALF_X; dx++) {
                setBlock(world, cx + dx, y, cz + Z_BACK, weatheredOrPlanks(planks, mossy, rng));
            }
            // South wall (z = Z_FRONT) with doorway at dx in {-1, 0..} — leave
            // a 1×2 gap at (dx=0) for wy in {1, 2}.
            for (int dx = -HALF_X; dx <= HALF_X; dx++) {
                boolean isDoorwayColumn = (dx == 0);
                boolean isDoorwayHeight = (wy == 1 || wy == 2);
                if (isDoorwayColumn && isDoorwayHeight) continue;
                setBlock(world, cx + dx, y, cz + Z_FRONT, weatheredOrPlanks(planks, mossy, rng));
            }
            // West wall (x = -HALF_X)
            for (int dz = Z_BACK + 1; dz <= Z_FRONT - 1; dz++) {
                setBlock(world, cx - HALF_X, y, cz + dz, weatheredOrPlanks(planks, mossy, rng));
            }
            // East wall (x = +HALF_X)
            for (int dz = Z_BACK + 1; dz <= Z_FRONT - 1; dz++) {
                setBlock(world, cx + HALF_X, y, cz + dz, weatheredOrPlanks(planks, mossy, rng));
            }
        }

        // ── Roof: oak stairs at floorY + WALL_HEIGHT + 1 ───────────────────
        int roofY = floorY + WALL_HEIGHT + 1;
        // Two opposing slopes meeting at z=mid. We make the south half slope
        // south (facing=NORTH stairs ascending toward the player) and the
        // north half slope north. The visual is a simple gable.
        BlockState stairsN = Blocks.OAK_STAIRS.getDefaultState()
            .with(StairsBlock.FACING, Direction.NORTH)
            .with(StairsBlock.HALF, BlockHalf.BOTTOM)
            .with(StairsBlock.SHAPE, StairShape.STRAIGHT);
        BlockState stairsS = Blocks.OAK_STAIRS.getDefaultState()
            .with(StairsBlock.FACING, Direction.SOUTH)
            .with(StairsBlock.HALF, BlockHalf.BOTTOM)
            .with(StairsBlock.SHAPE, StairShape.STRAIGHT);

        // Pre-pick the 2 random "missing" roof blocks so the result is stable.
        int totalRoofBlocks = (HALF_X * 2 + 1) * (Z_FRONT - Z_BACK + 1);
        int miss1 = rng.nextInt(totalRoofBlocks);
        int miss2 = rng.nextInt(totalRoofBlocks);

        int idx = 0;
        for (int dx = -HALF_X; dx <= HALF_X; dx++) {
            for (int dz = Z_BACK; dz <= Z_FRONT; dz++) {
                boolean skip = (idx == miss1) || (idx == miss2);
                idx++;
                if (skip) continue;
                // Front half (dz >= 0) uses south-facing stairs; back half uses north-facing.
                BlockState s = (dz >= 0) ? stairsS : stairsN;
                setBlock(world, cx + dx, roofY, cz + dz, s);
            }
        }

        // ── Chest at the back wall, centred ─────────────────────────────────
        // We place it one block in from the back wall, facing south so the
        // player approaching from the doorway can open it.
        BlockPos chestPos = new BlockPos(cx, floorY + 1, cz + Z_BACK + 1);
        BlockState chestState = Blocks.CHEST.getDefaultState()
            .with(ChestBlock.FACING, Direction.SOUTH);
        setBlock(world, chestPos.getX(), chestPos.getY(), chestPos.getZ(), chestState);

        BlockEntity be = world.getBlockEntity(chestPos);
        if (be instanceof LootableContainerBlockEntity loot) {
            loot.setLootTable(CHEST_LOOT, cfg.worldSeed ^ 0x4C4F4F54L); // "LOOT"
            Mists.LOG.info("Mists: spawn hut chest loot table set at {}", chestPos.toShortString());
        } else {
            Mists.LOG.warn("Mists: spawn hut chest BlockEntity missing at {} (got {})",
                chestPos.toShortString(), be);
        }

        Mists.LOG.info("Mists: spawn hut placed at ({}, {}, {})", cx, floorY, cz);
    }

    /** ~20% chance to use mossy cobble instead of planks. */
    private static BlockState weatheredOrPlanks(BlockState planks, BlockState mossy, Random rng) {
        return rng.nextFloat() < 0.20f ? mossy : planks;
    }

    /** setBlockState with NOTIFY_LISTENERS only — we don't need full updates
     *  here, just persistence + lighting recalculation, which the listeners
     *  handle. */
    private static void setBlock(ServerWorld world, int x, int y, int z, BlockState state) {
        world.setBlockState(new BlockPos(x, y, z), state, Block.NOTIFY_LISTENERS);
    }
}
