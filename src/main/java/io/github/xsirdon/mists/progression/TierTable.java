package io.github.xsirdon.mists.progression;

import io.github.xsirdon.mists.worldgen.MistsWorldData;

import static io.github.xsirdon.mists.MistsConstants.*;

public final class TierTable {

    public static Tier levelToTier(int level) {
        if (level >= TIER_OPEN_REQUIRED_LEVEL) return Tier.OPEN;
        if (level >= TIER_4_REQUIRED_LEVEL)    return Tier.FOUR;
        if (level >= TIER_3_REQUIRED_LEVEL)    return Tier.THREE;
        if (level >= TIER_2_REQUIRED_LEVEL)    return Tier.TWO;
        return Tier.ONE;
    }

    /** Returns the boundary radius for the given tier, ignoring any per-world overrides.
     *  Prefer {@link #tierToRadius(Tier, MistsWorldData)} when world context is available. */
    public static double tierToRadius(Tier tier) {
        return switch (tier) {
            case ONE   -> TIER_1_RADIUS;
            case TWO   -> TIER_2_RADIUS;
            case THREE -> TIER_3_RADIUS;
            case FOUR  -> TIER_4_RADIUS;
            case OPEN  -> TIER_OPEN_RADIUS;
        };
    }

    /** Returns the boundary radius for the given tier, applying any per-world overrides
     *  stored in {@link MistsWorldData}. Tier 1's radius is set dynamically by
     *  IslandPlacer to match the actual measured size of the spawn island, so the
     *  initial mist boundary wraps the island tightly instead of being a fixed 120 blocks. */
    public static double tierToRadius(Tier tier, MistsWorldData data) {
        if (tier == Tier.ONE && data != null && data.tier1RadiusOverride > 0) {
            return data.tier1RadiusOverride;
        }
        return tierToRadius(tier);
    }

    /** @deprecated prefer {@link #levelToRadius(int, MistsWorldData)} where possible. */
    @Deprecated
    public static double levelToRadius(int level) {
        return tierToRadius(levelToTier(level));
    }

    public static double levelToRadius(int level, MistsWorldData data) {
        return tierToRadius(levelToTier(level), data);
    }

    private TierTable() {}
}
