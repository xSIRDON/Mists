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

    /** Whether the density-function pathway has been enabled. Set to true when
     *  the mod initializes (or always, in v0.14). The IslandPlacer reads this to
     *  decide whether to skip its block-placing fallback. */
    private static volatile boolean enabled = true;

    /** Register (or replace) the config for a world seed. Safe to call from any
     *  thread. Primarily used by the IslandPlacer hook to canonicalize the
     *  config at the right moment, but not required — the mixin will derive on
     *  the fly via {@link #getOrDerive}. */
    public static void register(MistsIslandConfig cfg) {
        BY_SEED.put(cfg.worldSeed, cfg);
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

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean b) { enabled = b; }

    /** Test-only: clear the registry. */
    public static void clearForTesting() { BY_SEED.clear(); }

    private MistsIslandRegistry() {}
}
