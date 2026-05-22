package io.github.xsirdon.mists.mixin.worldgen;

import io.github.xsirdon.mists.Mists;
import io.github.xsirdon.mists.worldgen.density.BubbleBiasFunction;
import io.github.xsirdon.mists.worldgen.density.BubbleProfile;
import io.github.xsirdon.mists.worldgen.density.MistsIslandConfig;
import io.github.xsirdon.mists.worldgen.density.MistsIslandDensityFunction;
import io.github.xsirdon.mists.worldgen.density.MistsIslandRegistry;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.util.math.noise.DoublePerlinNoiseSampler;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
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

import java.util.HashMap;
import java.util.Map;

/**
 * v0.18 hook: installs a "water-world bubble" around world origin and grafts
 * the Mists island in at its centre.
 *
 * <h2>What it does</h2>
 *
 * Within {@link BubbleBiasFunction#BUBBLE_RADIUS} blocks of (0, 0):
 * <ul>
 *   <li>The {@code continents} channel of the noise router is biased strongly
 *       negative, so the biome source places ocean / deep-ocean biomes
 *       throughout the bubble regardless of seed.</li>
 *   <li>The {@code finalDensity} channel is biased very negative so the chunk
 *       generator carves a flat sea-floor; the Mists island density is then
 *       {@code max}'d on top to keep the deterministic island sticking out.</li>
 *   <li>{@code initialDensityWithoutJaggedness} (used by surface builders for
 *       height estimation) gets the same finalDensity-style bias so surface
 *       rules paint ocean-floor materials rather than coastal sand/grass.</li>
 * </ul>
 *
 * <p>The bias smoothly fades to zero in the
 * {@link BubbleBiasFunction#FADE_BAND}-wide annulus just outside the bubble, so
 * vanilla terrain takes over again past ~2200 blocks from origin with no hard
 * edge.
 *
 * <h2>Why we have to rebuild the multi-noise sampler</h2>
 *
 * {@code NoiseConfig.<init>} captures the climate-sampler references from the
 * original router. Mutating {@code this.noiseRouter} after the fact is not
 * enough: the biome source reads {@code this.multiNoiseSampler}, and that
 * sampler holds direct references to the pre-wrap continents/erosion/depth/...
 * functions. So we also reach in and replace {@code multiNoiseSampler} with one
 * built from the wrapped router, applied through the same unwrapping visitor
 * vanilla uses (see {@link NoiseConfigUnwrapVisitor}).
 *
 * <h2>Scope</h2>
 *
 * Only overworld-shaped {@link ChunkGeneratorSettings} are wrapped. The Nether
 * & End run {@code NoiseConfig.<init>} too but have different sea levels and
 * disabled aquifers, so they're detected and skipped.
 */
@Mixin(NoiseConfig.class)
public abstract class NoiseConfigMixin {

    @Mutable
    @Shadow @Final
    private NoiseRouter noiseRouter;

    @Mutable
    @Shadow @Final
    private MultiNoiseUtil.MultiNoiseSampler multiNoiseSampler;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void mists$installWaterWorldBubble(ChunkGeneratorSettings settings,
                                                RegistryEntryLookup<DoublePerlinNoiseSampler.NoiseParameters> noiseParams,
                                                long seed,
                                                CallbackInfo ci) {
        if (!MistsIslandRegistry.isEnabled()) return;
        if (!isOverworldShaped(settings)) return;

        MistsIslandConfig cfg = MistsIslandRegistry.getOrDerive(seed, settings.seaLevel());

        // ── Wrap continents (drives biome source) ────────────────────────────
        // -2.0 added to continentalness inside the bubble pushes the value well
        // into vanilla's deep-ocean range (~ -0.45 .. -1.05 in the OverworldBiomeCreator
        // climate parameter tables), so the biome source emits ocean biomes for
        // every column inside the bubble.
        DensityFunction continentsBias = new BubbleBiasFunction(-2.0);
        DensityFunction wrappedContinents =
            DensityFunctionTypes.add(noiseRouter.continents(), continentsBias);

        // ── Wrap finalDensity (drives chunk-gen terrain) + island on top ─────
        // -50 added inside the bubble forces vanilla's pre-aquifer density well
        // below the threshold, so every column gets carved to ocean. The Mists
        // island then dominates locally via max(), producing land only at its
        // deterministic centre.
        DensityFunction terrainBias = new BubbleBiasFunction(-50.0);
        DensityFunction island = new MistsIslandDensityFunction(cfg);
        DensityFunction wrappedFinal = DensityFunctionTypes.max(
            DensityFunctionTypes.add(noiseRouter.finalDensity(), terrainBias),
            island);

        // ── Wrap initialDensityWithoutJaggedness ─────────────────────────────
        // Surface-builder height estimation reads this; if it's still mountain-
        // height vanilla while finalDensity says ocean, surface rules can paint
        // alpine snow on bedrock. Same bias keeps the surface estimator in sync.
        DensityFunction wrappedInitial = DensityFunctionTypes.add(
            noiseRouter.initialDensityWithoutJaggedness(), terrainBias);

        // ── Assemble the wrapped router ──────────────────────────────────────
        NoiseRouter old = this.noiseRouter;
        NoiseRouter wrapped = new NoiseRouter(
            old.barrierNoise(),
            old.fluidLevelFloodednessNoise(),
            old.fluidLevelSpreadNoise(),
            old.lavaNoise(),
            old.temperature(),
            old.vegetation(),
            wrappedContinents,
            old.erosion(),
            old.depth(),
            old.ridges(),
            wrappedInitial,
            wrappedFinal,
            old.veinToggle(),
            old.veinRidged(),
            old.veinGap()
        );
        this.noiseRouter = wrapped;

        // ── Rebuild multiNoiseSampler so the biome source sees the wrap ──────
        // Vanilla's NoiseConfig constructor builds its climate sampler by
        // .apply()'ing every climate channel through an anonymous visitor that
        // unwraps RegistryEntryHolder + Wrapping nodes (so the sampler reads
        // raw noise rather than the cache-cell-smoothed worldgen channels).
        // Mirror that exactly so our wrapped continents flows through with the
        // same semantics.
        NoiseConfigUnwrapVisitor unwrap = new NoiseConfigUnwrapVisitor();
        this.multiNoiseSampler = new MultiNoiseUtil.MultiNoiseSampler(
            wrapped.temperature().apply(unwrap),
            wrapped.vegetation().apply(unwrap),
            wrapped.continents().apply(unwrap),
            wrapped.erosion().apply(unwrap),
            wrapped.depth().apply(unwrap),
            wrapped.ridges().apply(unwrap),
            settings.spawnTarget()
        );

        Mists.LOG.info(
            "Mists: water-world bubble installed ({}-block radius), island at ({}, {}) — seed {}",
            (int) BubbleProfile.BUBBLE_RADIUS, cfg.cx, cfg.cz, seed);
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

    /**
     * Mirror of the anonymous {@code DensityFunctionVisitor} that
     * {@code NoiseConfig.<init>} uses for its climate sampler: unwraps
     * {@code RegistryEntryHolder} and {@code Wrapping} nodes so the multi-noise
     * sampler reads raw noise rather than the cache-cell-smoothed worldgen
     * channels. Cached per-instance so the same upstream node identity
     * collapses to the same unwrapped function (matters for the climate
     * sampler's own caching).
     */
    private static final class NoiseConfigUnwrapVisitor
            implements DensityFunction.DensityFunctionVisitor {
        private final Map<DensityFunction, DensityFunction> cache = new HashMap<>();

        @Override
        public DensityFunction apply(DensityFunction f) {
            return cache.computeIfAbsent(f, NoiseConfigUnwrapVisitor::unwrap);
        }

        private static DensityFunction unwrap(DensityFunction f) {
            if (f instanceof DensityFunctionTypes.RegistryEntryHolder holder) {
                return holder.function().value();
            }
            // Wrapping itself is package-private; cast through the public
            // Wrapper marker interface, which exposes the same .wrapped() accessor.
            if (f instanceof DensityFunctionTypes.Wrapper wrapper) {
                return wrapper.wrapped();
            }
            return f;
        }
    }
}
