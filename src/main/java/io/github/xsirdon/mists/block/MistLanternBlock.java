package io.github.xsirdon.mists.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.LanternBlock;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/**
 * v0.21: a dimmer, mist-flavoured cousin of the vanilla lantern. Emits
 * subtle particles at random ticks to suggest faint crystallised mist
 * curling out of the cage.
 *
 * <p>Extends {@link LanternBlock} so we inherit:
 * <ul>
 *   <li>{@code HANGING}/{@code WATERLOGGED} state properties,</li>
 *   <li>placement state, neighbour-update behaviour, attachment shape,</li>
 *   <li>fluid state handling (waterloggable).</li>
 * </ul>
 * Light level is set to 11 (vanilla lantern is 15) via the block settings
 * in {@link MistsBlocks}; the block sound group is the vanilla LANTERN.
 */
public final class MistLanternBlock extends LanternBlock {

    public MistLanternBlock(Settings settings) {
        super(settings);
    }

    @Override
    public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        // Two-particle ambience: a soft "white ash" wisp drifting up from the
        // cage, plus a soul-fire-flame in the cage interior for a cool glow.
        double cx = pos.getX() + 0.5;
        double cz = pos.getZ() + 0.5;

        // Position the wisp slightly above the lantern's top, with a small
        // horizontal jitter so the stream isn't perfectly vertical.
        double jitterX = (random.nextDouble() - 0.5) * 0.3;
        double jitterZ = (random.nextDouble() - 0.5) * 0.3;
        double topY = pos.getY() + (state.get(HANGING) ? 1.05 : 0.95);
        world.addParticle(ParticleTypes.WHITE_ASH,
            cx + jitterX, topY, cz + jitterZ,
            0.0, 0.02, 0.0);

        // Soul-fire flame inside the cage. The cage centre is roughly at the
        // middle of the lantern model regardless of standing/hanging.
        double innerY = pos.getY() + (state.get(HANGING) ? 0.55 : 0.45);
        world.addParticle(ParticleTypes.SOUL_FIRE_FLAME,
            cx + (random.nextDouble() - 0.5) * 0.08,
            innerY,
            cz + (random.nextDouble() - 0.5) * 0.08,
            0.0, 0.0, 0.0);
    }
}
