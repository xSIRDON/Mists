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

    private final long worldSeed;
    private final int seaLevelHint;
    /** Per-instance cached config — re-fetched from the registry whenever the
     *  registry's version counter advances (e.g. IslandPlacer relocates the
     *  island to an actual ocean biome after the mixin already installed). */
    private volatile MistsIslandConfig cachedCfg;
    private volatile long cachedVersion = -1L;

    public MistsIslandDensityFunction(MistsIslandConfig initial) {
        this.worldSeed = initial.worldSeed;
        this.seaLevelHint = initial.seaLevel;
        this.cachedCfg = initial;
    }

    public MistsIslandConfig config() {
        // Refresh-on-demand: if the registry has a newer cfg for our seed, swap it in.
        long currentVersion = MistsIslandRegistry.version();
        if (currentVersion != cachedVersion) {
            cachedCfg = MistsIslandRegistry.getOrDerive(worldSeed, seaLevelHint);
            cachedVersion = currentVersion;
        }
        return cachedCfg;
    }

    @Override
    public double sample(DensityFunction.NoisePos pos) {
        return IslandProfile.density(config(), pos.blockX(), pos.blockY(), pos.blockZ());
    }

    /** Direct sample entry exposed for code that already has plain coordinates. */
    public double sampleAt(int x, int y, int z) {
        return IslandProfile.density(config(), x, y, z);
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
