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
            // v0.18: island sits 200..800 blocks from origin, well inside the
            // 2000-block water-world bubble the noise-router wrap installs.
            assertTrue(d >= 199.0 && d <= 801.0,
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

    // ── v0.18: water-world bubble bias ──────────────────────────────────────

    @Test void bubbleBiasIsFullValueAtOrigin() {
        assertEquals(-2.0, BubbleProfile.biasAt(-2.0, 0.0, 0.0), 1e-9);
        assertEquals(-50.0, BubbleProfile.biasAt(-50.0, 0.0, 0.0), 1e-9);
    }

    @Test void bubbleBiasIsFullValueAtInteriorEdge() {
        // dist = 1999 < 2000, so still full bias
        assertEquals(-2.0, BubbleProfile.biasAt(-2.0, 1999.0, 0.0), 1e-9);
    }

    @Test void bubbleBiasIsZeroPastFadeBand() {
        // dist = 2201 > 2200 (radius + band), so zero
        assertEquals(0.0, BubbleProfile.biasAt(-2.0, 2201.0, 0.0), 1e-9);
        assertEquals(0.0, BubbleProfile.biasAt(-50.0, 0.0, 3000.0), 1e-9);
    }

    @Test void bubbleBiasFadesMonotonicallyInBand() {
        // Within [2000, 2200] the magnitude of the bias decreases monotonically.
        double prevMag = Math.abs(BubbleProfile.biasAt(-2.0, 2000.0, 0.0));
        for (double d = 2010.0; d <= 2200.0; d += 10.0) {
            double mag = Math.abs(BubbleProfile.biasAt(-2.0, d, 0.0));
            assertTrue(mag <= prevMag + 1e-9,
                "bias should not grow as distance grows: at " + d + " got " + mag + " prev " + prevMag);
            prevMag = mag;
        }
        assertEquals(0.0, BubbleProfile.biasAt(-2.0, 2200.0, 0.0), 1e-9);
    }

    @Test void islandPositionIsInsideBubble() {
        // Every derived island for low seeds must sit within the bubble so it
        // is guaranteed forced-ocean terrain around it.
        for (long s = 1; s < 50; s++) {
            MistsIslandConfig cfg = MistsIslandConfig.deriveFromSeed(s, SEA);
            double d = Math.sqrt((double) cfg.cx * cfg.cx + (double) cfg.cz * cfg.cz);
            assertTrue(d < BubbleProfile.BUBBLE_RADIUS,
                "seed " + s + " landed at " + d + " — outside bubble radius "
                    + BubbleProfile.BUBBLE_RADIUS);
        }
    }
}
