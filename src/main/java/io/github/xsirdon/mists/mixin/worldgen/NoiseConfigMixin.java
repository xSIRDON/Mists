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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v0.18 hook (v0.23 timing fix): installs a "water-world bubble" around world
 * origin and grafts the Mists island in at its centre.
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
 * <h2>Why we wrap BEFORE vanilla's apply-visitor (v0.23 fix)</h2>
 *
 * v0.18 wrapped at {@code @At("TAIL")} — i.e. AFTER vanilla's constructor had
 * already run {@code unappliedRouter.apply(LegacyNoiseDensityFunctionVisitor)}.
 * Vanilla's visitor walks the density tree and injects {@code seed} into the
 * concrete noise leaves (e.g. {@code class_6544$class_6552}). Wrapping after
 * the walk meant our fresh {@code add(...)} / {@code max(...)} combinator nodes
 * (built from the already-visited tree) were correct, BUT we also rebuilt
 * {@code multiNoiseSampler} from the wrapped router, and the references we
 * passed to it were never visited by anything that injected the seed into
 * leaves reachable along paths we mutated.
 *
 * <p>The crash surfaced when Fabric Biome API's {@code ChunkNoiseSampler.<init>}
 * mixin called {@code fabric_getSeed()} on a {@code class_6544$class_6552}
 * noise leaf reached through one of our combinator nodes, hit a
 * {@code seed == null}, and NPE'd during structure generation (BiomeMakeover's
 * MansionFeature was the trigger).
 *
 * <p>v0.23 fix: redirect the {@code NoiseRouter.apply(visitor)} call inside
 * {@code NoiseConfig.<init>}. We build the wrapped router from the
 * <em>unapplied</em> router (the raw, never-visited one off
 * {@code ChunkGeneratorSettings}), then hand vanilla's
 * {@code LegacyNoiseDensityFunctionVisitor} to {@code wrapped.apply(visitor)}.
 * Vanilla then walks our entire combined tree — every leaf, including those
 * inside our {@code add} / {@code max} combinators, gets the seed injected as
 * part of the normal pipeline. The fully-visited router is stored as
 * {@code this.noiseRouter}, and the {@code multiNoiseSampler} vanilla builds
 * immediately after observes our wrapped continents through the same pipeline
 * — no rebuild needed.
 *
 * <h2>Scope</h2>
 *
 * Only overworld-shaped {@link ChunkGeneratorSettings} are wrapped. The Nether
 * & End run {@code NoiseConfig.<init>} too but have different sea levels and
 * disabled aquifers, so they're detected and skipped.
 */
@Mixin(NoiseConfig.class)
public abstract class NoiseConfigMixin {

    /**
     * Per-thread capture of the {@code (settings, seed)} pair from the current
     * {@code NoiseConfig.<init>} invocation. Necessary because constructors run
     * on whatever thread requests world-gen state (server-startup, biome-source
     * cache priming, dimension reload), so a single static field is not safe.
     *
     * <p>Populated at {@code @Inject(HEAD)}, consumed by the {@code @Redirect}
     * around {@code NoiseRouter.apply}, cleared at {@code @Inject(RETURN)}.
     */
    private static final ThreadLocal<ChunkGeneratorSettings> SETTINGS_CAPTURE =
        new ThreadLocal<>();

    /** Companion to {@link #SETTINGS_CAPTURE}; {@code long[1]} so {@code null}
     *  means "no seed captured on this thread". */
    private static final ThreadLocal<long[]> SEED_CAPTURE = new ThreadLocal<>();

    /** Retained but no longer mutated post-construction — the redirect installs
     *  the wrapped router during the normal apply pipeline. */
    @Mutable
    @Shadow @Final
    private NoiseRouter noiseRouter;

    /**
     * Must be {@code static}: Mixin requires {@code @Inject(HEAD)} on a constructor
     * to use a static handler, because at the entry of {@code <init>} the implicit
     * {@code super()} call has not yet run and {@code this} is uninitialised. We
     * only write to static {@link ThreadLocal}s here, so {@code static} costs us
     * nothing and satisfies the injector.
     */
    @Inject(method = "<init>", at = @At("HEAD"))
    private static void mists$captureConstructorArgs(ChunkGeneratorSettings settings,
                                                      RegistryEntryLookup<DoublePerlinNoiseSampler.NoiseParameters> noiseParams,
                                                      long seed,
                                                      CallbackInfo ci) {
        SETTINGS_CAPTURE.set(settings);
        SEED_CAPTURE.set(new long[]{seed});
    }

    /**
     * Replaces the vanilla call
     * {@code unappliedRouter.apply(LegacyNoiseDensityFunctionVisitor)} inside
     * {@code NoiseConfig.<init>}.
     *
     * <p>When the captured settings are overworld-shaped and the Mists island
     * registry is enabled, we wrap the unapplied router first, then call
     * {@code apply(visitor)} on the wrapped router so vanilla's seed-injection
     * visitor walks our combined tree end-to-end. The returned, fully-visited
     * router is what the constructor will store as {@code this.noiseRouter}.
     *
     * <p>If we're not in an overworld context (or the registry is disabled),
     * delegate straight through — same behaviour as vanilla.
     */
    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/gen/noise/NoiseRouter;apply(Lnet/minecraft/world/gen/densityfunction/DensityFunction$DensityFunctionVisitor;)Lnet/minecraft/world/gen/noise/NoiseRouter;"
        )
    )
    private NoiseRouter mists$wrapBeforeApply(NoiseRouter unappliedRouter,
                                               DensityFunction.DensityFunctionVisitor visitor) {
        ChunkGeneratorSettings settings = SETTINGS_CAPTURE.get();
        long[] seedHolder = SEED_CAPTURE.get();

        if (settings == null || seedHolder == null
            || !MistsIslandRegistry.isEnabled()
            || !isOverworldShaped(settings)) {
            // Not our dimension / mod disabled — vanilla semantics exactly.
            return unappliedRouter.apply(visitor);
        }

        long seed = seedHolder[0];
        MistsIslandConfig cfg = MistsIslandRegistry.getOrDerive(seed, settings.seaLevel());

        NoiseRouter wrappedUnapplied = wrapRouter(unappliedRouter, cfg);

        // Now let vanilla's LegacyNoiseDensityFunctionVisitor walk the WHOLE
        // wrapped tree, including the leaves underneath our add/max nodes, so
        // every noise leaf gets its seed populated.
        NoiseRouter applied = wrappedUnapplied.apply(visitor);

        Mists.LOG.info(
            "Mists: water-world bubble installed ({}-block radius), island at ({}, {}) — seed {}",
            (int) BubbleProfile.BUBBLE_RADIUS, cfg.cx, cfg.cz, seed);

        return applied;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void mists$clearCapture(ChunkGeneratorSettings settings,
                                     RegistryEntryLookup<DoublePerlinNoiseSampler.NoiseParameters> noiseParams,
                                     long seed,
                                     CallbackInfo ci) {
        SETTINGS_CAPTURE.remove();
        SEED_CAPTURE.remove();
    }

    /**
     * Build a new {@link NoiseRouter} that wraps the bubble-bias and island
     * functions over the relevant channels of {@code base}. {@code base} must
     * be the unapplied router straight off {@code ChunkGeneratorSettings} — the
     * caller is responsible for handing the result through vanilla's apply
     * visitor so the seeds get injected.
     */
    private static NoiseRouter wrapRouter(NoiseRouter base, MistsIslandConfig cfg) {
        // ── Wrap continents (drives biome source) ────────────────────────────
        // -2.0 added to continentalness inside the bubble pushes the value well
        // into vanilla's deep-ocean range (~ -0.45 .. -1.05 in the
        // OverworldBiomeCreator climate parameter tables), so the biome source
        // emits ocean biomes for every column inside the bubble.
        DensityFunction continentsBias = new BubbleBiasFunction(-2.0);
        DensityFunction wrappedContinents =
            DensityFunctionTypes.add(base.continents(), continentsBias);

        // ── Wrap finalDensity (drives chunk-gen terrain) + island on top ─────
        // -50 added inside the bubble forces vanilla's pre-aquifer density well
        // below the threshold, so every column gets carved to ocean. The Mists
        // island then dominates locally via max(), producing land only at its
        // deterministic centre.
        DensityFunction terrainBias = new BubbleBiasFunction(-50.0);
        DensityFunction island = new MistsIslandDensityFunction(cfg);
        DensityFunction wrappedFinal = DensityFunctionTypes.max(
            DensityFunctionTypes.add(base.finalDensity(), terrainBias),
            island);

        // ── Wrap initialDensityWithoutJaggedness ─────────────────────────────
        // Surface-builder height estimation reads this; if it's still mountain-
        // height vanilla while finalDensity says ocean, surface rules can paint
        // alpine snow on bedrock. Same bias keeps the surface estimator in sync.
        DensityFunction wrappedInitial = DensityFunctionTypes.add(
            base.initialDensityWithoutJaggedness(), terrainBias);

        return new NoiseRouter(
            base.barrierNoise(),
            base.fluidLevelFloodednessNoise(),
            base.fluidLevelSpreadNoise(),
            base.lavaNoise(),
            base.temperature(),
            base.vegetation(),
            wrappedContinents,
            base.erosion(),
            base.depth(),
            base.ridges(),
            wrappedInitial,
            wrappedFinal,
            base.veinToggle(),
            base.veinRidged(),
            base.veinGap()
        );
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
