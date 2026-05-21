package io.github.xsirdon.mists.progression;

import org.junit.jupiter.api.Test;
import static io.github.xsirdon.mists.MistsConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class TierTableTest {

    @Test void levelToTier_boundaries() {
        assertEquals(Tier.ONE,   TierTable.levelToTier(0));
        assertEquals(Tier.ONE,   TierTable.levelToTier(4));
        assertEquals(Tier.TWO,   TierTable.levelToTier(5));
        assertEquals(Tier.TWO,   TierTable.levelToTier(9));
        assertEquals(Tier.THREE, TierTable.levelToTier(10));
        assertEquals(Tier.THREE, TierTable.levelToTier(14));
        assertEquals(Tier.FOUR,  TierTable.levelToTier(15));
        assertEquals(Tier.FOUR,  TierTable.levelToTier(29));
        assertEquals(Tier.OPEN,  TierTable.levelToTier(30));
        assertEquals(Tier.OPEN,  TierTable.levelToTier(9999));
    }

    @Test void levelToTier_negativeFloorsToOne() {
        assertEquals(Tier.ONE, TierTable.levelToTier(-5));
    }

    @Test void tierToRadius_matchesConstants() {
        assertEquals(TIER_1_RADIUS,    TierTable.tierToRadius(Tier.ONE));
        assertEquals(TIER_2_RADIUS,    TierTable.tierToRadius(Tier.TWO));
        assertEquals(TIER_3_RADIUS,    TierTable.tierToRadius(Tier.THREE));
        assertEquals(TIER_4_RADIUS,    TierTable.tierToRadius(Tier.FOUR));
        assertEquals(TIER_OPEN_RADIUS, TierTable.tierToRadius(Tier.OPEN));
    }

    @Test void levelToRadius_composes() {
        assertEquals(TIER_1_RADIUS, TierTable.levelToRadius(0));
        assertEquals(TIER_2_RADIUS, TierTable.levelToRadius(5));
        assertEquals(TIER_3_RADIUS, TierTable.levelToRadius(10));
        assertEquals(TIER_4_RADIUS, TierTable.levelToRadius(15));
        assertEquals(TIER_OPEN_RADIUS, TierTable.levelToRadius(30));
    }
}
