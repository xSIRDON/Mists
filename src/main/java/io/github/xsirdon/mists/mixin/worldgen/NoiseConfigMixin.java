package io.github.xsirdon.mists.mixin.worldgen;

import io.github.xsirdon.mists.Mists;
import io.github.xsirdon.mists.worldgen.density.MistsIslandConfig;
import io.github.xsirdon.mists.worldgen.density.MistsIslandDensityFunction;
import io.github.xsirdon.mists.worldgen.density.MistsIslandRegistry;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.util.math.noise.DoublePerlinNoiseSampler;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.densityfunction.DensityFunctionTypes;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The v0.14 hook that integrates Mists islands with vanilla chunk generation.
 *
 * <p>Vanilla's {@code NoiseConfig} owns the {@link NoiseRouter} that the chunk
 * generator samples for its terrain shape. The router is a record (immutable),
 * but {@link NoiseConfig#getNoiseRouter()} returns the field straight, so by
 * mutating the private final field at the tail of the constructor we install a
 * router whose {@code finalDensity} is {@code max(natural, ours)}.
 *
 * <p>Effect at chunk-gen time:
 * <ul>
 *   <li>Inside the island volume our density is positive → vanilla emits stone.</li>
 *   <li>Outside, we return zero, vanilla noise dominates → natural seafloor /
 *       continents unchanged.</li>
 *   <li>At the boundary, {@code max()} blends our soft gradient against the
 *       natural ambient noise, producing a foundation that tapers into the
 *       surrounding seafloor.</li>
 * </ul>
 *
 * <p>Only the overworld is wrapped. The Nether and End run {@code NoiseConfig}
 * constructors too, but with different sea levels and disabled aquifers/ore
 * veins, so we filter by detecting overworld-shaped settings.
 *
 * <p>The {@link MistsIslandRegistry} is consulted by seed; if no entry exists
 * we derive deterministically from the seed, so dimension load order doesn't
 * matter — the same world always gets the same island.
 */
@Mixin(NoiseConfig.class)
public abstract class NoiseConfigMixin {

    @Mutable
    @Shadow @Final
    private NoiseRouter noiseRouter;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void mists$wrapFinalDensity(ChunkGeneratorSettings settings,
                                         RegistryEntryLookup<DoublePerlinNoiseSampler.NoiseParameters> noiseParams,
                                         long seed,
                                         CallbackInfo ci) {
        if (!MistsIslandRegistry.isEnabled()) return;
        if (!isOverworldShaped(settings)) return;

        MistsIslandConfig cfg =
            MistsIslandRegistry.getOrDerive(seed, settings.seaLevel());

        DensityFunction island = new MistsIslandDensityFunction(cfg);
        // max(natural, ours): when our density is positive (inside the island
        // volume) it wins, generating land; when ours is zero or negative the
        // natural value wins, leaving the surrounding terrain untouched.
        DensityFunction wrappedFinal =
            DensityFunctionTypes.max(noiseRouter.finalDensity(), island);

        // Build a new router with finalDensity swapped, leaving every other
        // channel (biomes, aquifers, ore veins) untouched.
        NoiseRouter old = noiseRouter;
        this.noiseRouter = new NoiseRouter(
            old.barrierNoise(),
            old.fluidLevelFloodednessNoise(),
            old.fluidLevelSpreadNoise(),
            old.lavaNoise(),
            old.temperature(),
            old.vegetation(),
            old.continents(),
            old.erosion(),
            old.depth(),
            old.ridges(),
            old.initialDensityWithoutJaggedness(),
            wrappedFinal,
            old.veinToggle(),
            old.veinRidged(),
            old.veinGap()
        );

        Mists.LOG.info("Mists: density-function wrap installed for seed {} — island at ({}, {}), surfaceR={} underwaterR={}",
            seed, cfg.cx, cfg.cz, cfg.surfaceRadius, cfg.underwaterRadius);
    }

    /** Overworld signature: sea level 63, water as default fluid, stone as
     *  default block, and aquifers enabled. Nether & End all fail at least one
     *  of these. Custom datapack dimensions matching this pattern are treated
     *  as overworld-like and will get an island — acceptable. */
    private static boolean isOverworldShaped(ChunkGeneratorSettings s) {
        return s.seaLevel() == 63
            && s.defaultFluid().isOf(Blocks.WATER)
            && s.defaultBlock().isOf(Blocks.STONE)
            && s.hasAquifers();
    }
}
