package io.github.xsirdon.mists.worldgen.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * The v0.18 "water-world bubble" leaf — a {@link DensityFunction} that returns
 * {@link BubbleProfile#biasAt(double, double, double)} for the (x, z) of every
 * sampled position.
 *
 * <p>This class is the Minecraft-API-facing adapter; all math lives in
 * {@link BubbleProfile} so it can be unit-tested without bootstrapping
 * Minecraft's registry (DensityFunction's static init pulls in
 * DensityFunctionTypes which depends on Registries).
 *
 * <p>Composed via {@code DensityFunctionTypes.add(vanilla, this)} inside
 * {@link io.github.xsirdon.mists.mixin.worldgen.NoiseConfigMixin}:
 * <ul>
 *   <li>{@code continents} gets a strong negative bias (≈ -2.0) so the biome
 *       source places ocean biomes throughout the bubble regardless of seed.</li>
 *   <li>{@code finalDensity} gets a very strong negative bias (≈ -50) so the
 *       chunk generator carves a flat ocean floor at sea level. Combined with
 *       {@code max(..., island)} that keeps the Mists island sticking out.</li>
 * </ul>
 *
 * <p>Outside the bubble's fade band this function returns exactly 0.0, so
 * vanilla worldgen is untouched beyond ~2200 blocks from origin.
 */
public final class BubbleBiasFunction implements DensityFunction.Base {

    /** Lazy holder so the test suite doesn't trigger DensityFunction's static
     *  init (which needs registry bootstrap) when it doesn't have to. */
    private static final class Codecs {
        static final Codec<BubbleBiasFunction> CODEC =
            RecordCodecBuilder.create(i -> i.point(new BubbleBiasFunction(0.0)));
        static final CodecHolder<BubbleBiasFunction> HOLDER = CodecHolder.of(CODEC);
    }

    private final double biasValue;

    public BubbleBiasFunction(double biasValue) {
        this.biasValue = biasValue;
    }

    @Override
    public double sample(DensityFunction.NoisePos pos) {
        return BubbleProfile.biasAt(biasValue, pos.blockX(), pos.blockZ());
    }

    @Override
    public double minValue() { return Math.min(biasValue, 0.0); }

    @Override
    public double maxValue() { return Math.max(biasValue, 0.0); }

    @Override
    public CodecHolder<? extends DensityFunction> getCodecHolder() { return Codecs.HOLDER; }
}
