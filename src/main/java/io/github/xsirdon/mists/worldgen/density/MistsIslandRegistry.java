package io.github.xsirdon.mists.worldgen.density;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Process-wide registry of active Mists island configurations keyed by world seed.
 *
 * <p>The mixin running inside {@code NoiseConfig.<init>} cannot easily reach a
 * specific {@code ServerWorld} (NoiseConfig is created earlier in the lifecycle
 * and is dimension-agnostic), so we identify the world via the legacy world seed
 * that gets passed straight into {@code NoiseConfig.create(..., long seed)}.
 *
 * <p>Because every Mists island is also deterministically derivable from the seed
 * alone via {@link MistsIslandConfig#deriveFromSeed}, the registry is purely a
 * cache / opt-in mechanism — the mixin falls back to the deterministic derivation
 * for any overworld it has not seen registered.
 *
 * <p>This is process-global because density-function evaluation runs on worldgen
 * threads off the main server thread, and a {@link Map} lookup per chunk is a
 * one-time cost that gets cached by NoiseConfig's caching density functions.
 */
public final class MistsIslandRegistry {

    private static final Map<Long, MistsIslandConfig> BY_SEED = new ConcurrentHashMap<>();

    /** Whether the density-function pathway has been enabled. */
    private static volatile boolean enabled = true;

    /** Monotonic version counter — bumped on every {@link #register} call so that
     *  the {@code MistsIslandDensityFunction} can cheaply detect when a previously-
     *  cached config has been superseded (e.g. after IslandPlacer relocates the
     *  island to an actual ocean biome). */
    private static volatile long version = 0L;

    /** Register (or replace) the config for a world seed. Safe to call from any
     *  thread. Bumps the version counter so density functions invalidate their
     *  per-instance caches. */
    public static void register(MistsIslandConfig cfg) {
        BY_SEED.put(cfg.worldSeed, cfg);
        version++;
    }

    /** Look up by world seed; derive deterministically if not registered. */
    public static MistsIslandConfig getOrDerive(long worldSeed, int seaLevel) {
        MistsIslandConfig cfg = BY_SEED.get(worldSeed);
        if (cfg != null) return cfg;
        cfg = MistsIslandConfig.deriveFromSeed(worldSeed, seaLevel);
        // Cache so subsequent NoiseConfig instances for the same seed (e.g. on
        // reload) hit the same coordinates without re-deriving.
        BY_SEED.putIfAbsent(worldSeed, cfg);
        return BY_SEED.get(worldSeed);
    }

    /** Current registry version. Density functions read this to invalidate their
     *  per-instance cached config when the registry changes. */
    public static long version() { return version; }

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean b) { enabled = b; }

    /** Test-only: clear the registry. */
    public static void clearForTesting() {
        BY_SEED.clear();
        version++;
    }

    private MistsIslandRegistry() {}
}
