package io.github.xsirdon.mists.worldgen.density;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-Java tests for the v0.14 density-function math via {@link IslandProfile}.
 * Avoids touching {@link MistsIslandDensityFunction} directly because its
 * implementing interface ({@code DensityFunction}) triggers Minecraft's
 * registry bootstrap during static init, which fails outside a real server.
 */
class MistsIslandDensityFunctionTest {

    private static final int SEA = 63;

    @Test void deriveIsDeterministic() {
        MistsIslandConfig a = MistsIslandConfig.deriveFromSeed(12345L, SEA);
        MistsIslandConfig b = MistsIslandConfig.deriveFromSeed(12345L, SEA);
        assertEquals(a.cx, b.cx);
        assertEquals(a.cz, b.cz);
    }

    @Test void deriveDiffersForDifferentSeeds() {
        MistsIslandConfig a = MistsIslandConfig.deriveFromSeed(1L, SEA);
        MistsIslandConfig b = MistsIslandConfig.deriveFromSeed(2L, SEA);
        assertTrue(a.cx != b.cx || a.cz != b.cz);
    }

    @Test void deriveIsWithinExpectedDistance() {
        for (long s = 1; s < 30; s++) {
            MistsIslandConfig cfg = MistsIslandConfig.deriveFromSeed(s, SEA);
            double d = Math.sqrt((double) cfg.cx * cfg.cx + (double) cfg.cz * cfg.cz);
            // v0.15 pushes the island out to 3000..5500 blocks from origin
            // so it lands well away from continental land near (0,0).
            assertTrue(d >= 2999.0 && d <= 5501.0,
                "seed " + s + " landed at distance " + d);
        }
    }

    @Test void densityIsPositiveBelowSurfaceAtCenter() {
        MistsIslandConfig cfg = MistsIslandConfig.deriveFromSeed(42L, SEA);
        double d = IslandProfile.density(cfg, cfg.cx, SEA - 5, cfg.cz);
        assertTrue(d > 0.0, "expected positive density below surface at center, got " + d);
    }

    @Test void densityIsNegativeWellAboveSurfaceAtCenter() {
        MistsIslandConfig cfg = MistsIslandConfig.deriveFromSeed(42L, SEA);
        double d = IslandProfile.density(cfg, cfg.cx, SEA + 30, cfg.cz);
        assertTrue(d < 0.0, "expected negative density well above surface, got " + d);
    }

    @Test void densityIsZeroFarFromIsland() {
        MistsIslandConfig cfg = MistsIslandConfig.deriveFromSeed(42L, SEA);
        double d = IslandProfile.density(cfg, cfg.cx + 2000, SEA, cfg.cz + 2000);
        assertEquals(0.0, d, 0.0,
            "expected zero density far from island so vanilla noise dominates");
    }

    @Test void densityIsClampedToMinMax() {
        MistsIslandConfig cfg = MistsIslandConfig.deriveFromSeed(42L, SEA);
        double deep = IslandProfile.density(cfg, cfg.cx, SEA - 1000, cfg.cz);
        assertTrue(deep <= IslandProfile.MAX_DENSITY + 1e-9);
        double high = IslandProfile.density(cfg, cfg.cx, SEA + 1000, cfg.cz);
        assertTrue(high >= -IslandProfile.MAX_DENSITY - 1e-9);
    }

    @Test void profileTopYAtCentreIsAboveSeaLevel() {
        MistsIslandConfig cfg = MistsIslandConfig.deriveFromSeed(42L, SEA);
        int topY = IslandProfile.profileTopY(cfg, cfg.cx, cfg.cz);
        assertTrue(topY > SEA, "centre column should be land, got top=" + topY);
        assertTrue(topY <= SEA + cfg.maxHeight + 2,
            "centre column should not exceed configured maxHeight, got top=" + topY);
    }

    @Test void profileTopYBeyondFootprintIsMinValue() {
        MistsIslandConfig cfg = MistsIslandConfig.deriveFromSeed(42L, SEA);
        int topY = IslandProfile.profileTopY(cfg,
            cfg.cx + (int) cfg.underwaterRadius + 10, cfg.cz);
        assertEquals(Integer.MIN_VALUE, topY);
    }

    @Test void registryFallbackDerivesWhenMissing() {
        MistsIslandRegistry.clearForTesting();
        MistsIslandConfig cfg = MistsIslandRegistry.getOrDerive(99L, SEA);
        assertEquals(99L, cfg.worldSeed);
        MistsIslandConfig cfg2 = MistsIslandRegistry.getOrDerive(99L, SEA);
        assertSame(cfg, cfg2);
    }

    @Test void registryExplicitRegisterWinsOverDerivation() {
        MistsIslandRegistry.clearForTesting();
        MistsIslandConfig custom = new MistsIslandConfig(
            100, 200, SEA, 25.0, 50.0, 7, 12, 7L);
        MistsIslandRegistry.register(custom);
        MistsIslandConfig got = MistsIslandRegistry.getOrDerive(7L, SEA);
        assertEquals(100, got.cx);
        assertEquals(200, got.cz);
    }
}
