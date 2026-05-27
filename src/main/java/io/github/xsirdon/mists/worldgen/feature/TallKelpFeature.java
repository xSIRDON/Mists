package io.github.xsirdon.mists.worldgen.feature;

import com.mojang.serialization.Codec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.KelpBlock;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;

/**
 * v0.21: a "tall kelp" feature that works around the vanilla constraint that
 * kelp can't grow beyond ~8 blocks tall (vanilla {@code KelpBlock}'s
 * {@code maxHeight} property maxes out via age=25, and the
 * {@code minecraft:kelp} configured-feature uses a stochastic growth that
 * usually ends much earlier).
 *
 * <p>We bypass that by directly placing {@link Blocks#KELP_PLANT} segments
 * vertically from the origin upward, capped with a {@link Blocks#KELP} tip
 * block. The placement honours the existing water column — it refuses to
 * place into non-water blocks, so it gracefully stops at the surface or any
 * obstruction.
 *
 * <p>The configured-feature JSON wraps this in two presets:
 * {@code mists:tall_kelp_normal} (12..16 blocks) and
 * {@code mists:tall_kelp_giant} (18..24 blocks).
 */
public final class TallKelpFeature extends Feature<TallKelpFeatureConfig> {

    public TallKelpFeature(Codec<TallKelpFeatureConfig> codec) {
        super(codec);
    }

    @Override
    public boolean generate(FeatureContext<TallKelpFeatureConfig> context) {
        StructureWorldAccess world = context.getWorld();
        BlockPos origin = context.getOrigin();
        Random random = context.getRandom();
        TallKelpFeatureConfig cfg = context.getConfig();

        // The origin (from a heightmap OCEAN_FLOOR_WG placement) is the first
        // block above the seafloor. We need that block — and the block above —
        // to be water for kelp to start growing.
        if (!isPlaceableWater(world, origin)) return false;

        // Block below origin must be a solid surface (sand/dirt/gravel/stone)
        // for kelp to attach naturally; if it's water/air we'd be floating.
        BlockState below = world.getBlockState(origin.down());
        if (below.isAir() || below.getFluidState().isOf(Fluids.WATER)) return false;

        int chosenHeight = cfg.minHeight()
            + random.nextInt(Math.max(1, cfg.maxHeight() - cfg.minHeight() + 1));

        BlockPos.Mutable cursor = origin.mutableCopy();
        int placed = 0;

        for (int i = 0; i < chosenHeight; i++) {
            if (!isPlaceableWater(world, cursor)) break;

            boolean isTip = (i == chosenHeight - 1) || !isPlaceableWater(world, cursor.up());

            BlockState toPlace;
            if (isTip) {
                // Top tip: KELP block with a randomised age so the next
                // bonemeal pass doesn't always extend it the same way.
                int age = random.nextInt(26); // 0..25 inclusive
                toPlace = Blocks.KELP.getDefaultState().with(KelpBlock.AGE, age);
            } else {
                toPlace = Blocks.KELP_PLANT.getDefaultState();
            }

            world.setBlockState(cursor, toPlace, Block.NOTIFY_LISTENERS);
            placed++;

            if (isTip) break;
            cursor.move(0, 1, 0);
        }

        return placed > 0;
    }

    /** True if the position is currently water (any level) and not solid. */
    private static boolean isPlaceableWater(StructureWorldAccess world, BlockPos pos) {
        BlockState here = world.getBlockState(pos);
        return here.getFluidState().isOf(Fluids.WATER)
            && (here.isOf(Blocks.WATER) || here.isAir() || !here.getFluidState().isEmpty());
    }
}
