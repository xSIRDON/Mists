package io.github.xsirdon.mists.progression;

import static io.github.xsirdon.mists.MistsConstants.*;

public final class TierTable {

    public static Tier levelToTier(int level) {
        if (level >= TIER_OPEN_REQUIRED_LEVEL) return Tier.OPEN;
        if (level >= TIER_4_REQUIRED_LEVEL)    return Tier.FOUR;
        if (level >= TIER_3_REQUIRED_LEVEL)    return Tier.THREE;
        if (level >= TIER_2_REQUIRED_LEVEL)    return Tier.TWO;
        return Tier.ONE;
    }

    public static double tierToRadius(Tier tier) {
        return switch (tier) {
            case ONE   -> TIER_1_RADIUS;
            case TWO   -> TIER_2_RADIUS;
            case THREE -> TIER_3_RADIUS;
            case FOUR  -> TIER_4_RADIUS;
            case OPEN  -> TIER_OPEN_RADIUS;
        };
    }

    public static double levelToRadius(int level) {
        return tierToRadius(levelToTier(level));
    }

    private TierTable() {}
}
