package io.github.xsirdon.mists.worldgen.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * Density-function leaf that injects positive density inside a Mists island
 * volume and zero density outside, sized from a {@link MistsIslandConfig}.
 *
 * <p>This is the central piece of v0.14: combined into the vanilla
 * {@code finalDensity} via {@code max(natural, ours)}, it makes the chunk
 * generator output solid terrain inside our island radius and natural terrain
 * everywhere else. Vanilla surface rules paint grass/sand/dirt on top, vanilla
 * carvers carve caves through our generated stone, and vanilla feature
 * placement scatters trees/grass/flowers on the resulting biome columns.
 *
 * <p>All math lives in {@link IslandProfile} so it can be tested without
 * Minecraft's registry bootstrap; this class is a thin adapter to the
 * {@link DensityFunction.Base} interface.
 *
 * <p>This class is never serialised — it's installed at runtime by
 * {@code NoiseConfigMixin}. The codec is a placeholder that encodes nothing.
 */
public final class MistsIslandDensityFunction implements DensityFunction.Base {

    /** Lazy holder so the test suite doesn't trigger DensityFunction's static
     *  init (which needs registry bootstrap) when it doesn't have to. */
    private static final class Codecs {
        static final Codec<MistsIslandDensityFunction> CODEC =
            RecordCodecBuilder.create(i -> i.point(
                new MistsIslandDensityFunction(
                    new MistsIslandConfig(0, 0, 63, 0.0, 0.0, 0, 0, 0L))));
        static final CodecHolder<MistsIslandDensityFunction> HOLDER =
            CodecHolder.of(CODEC);
    }

    private final MistsIslandConfig cfg;

    public MistsIslandDensityFunction(MistsIslandConfig cfg) {
        this.cfg = cfg;
    }

    public MistsIslandConfig config() { return cfg; }

    @Override
    public double sample(DensityFunction.NoisePos pos) {
        return IslandProfile.density(cfg, pos.blockX(), pos.blockY(), pos.blockZ());
    }

    /** Direct sample entry exposed for code that already has plain coordinates. */
    public double sampleAt(int x, int y, int z) {
        return IslandProfile.density(cfg, x, y, z);
    }

    @Override
    public double minValue() { return -IslandProfile.MAX_DENSITY; }

    @Override
    public double maxValue() { return  IslandProfile.MAX_DENSITY; }

    @Override
    public CodecHolder<? extends DensityFunction> getCodecHolder() {
        return Codecs.HOLDER;
    }
}
