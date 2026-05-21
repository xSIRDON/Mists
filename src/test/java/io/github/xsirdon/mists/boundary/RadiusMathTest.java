package io.github.xsirdon.mists.boundary;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadiusMathTest {

    @Test void distanceFromSpawn_2d() {
        assertEquals(0.0,  BoundaryMath.distanceFromCenter(0,  0,  0, 0), 1e-9);
        assertEquals(5.0,  BoundaryMath.distanceFromCenter(3,  4,  0, 0), 1e-9);
        assertEquals(13.0, BoundaryMath.distanceFromCenter(-5, 12, 0, 0), 1e-9);
    }

    @Test void band_classification() {
        double r = 100.0;
        assertEquals(BoundaryBand.SAFE,    BoundaryMath.classify(50,   0, 0, 0, r));
        assertEquals(BoundaryBand.HOSTILE, BoundaryMath.classify(92,   0, 0, 0, r));
        assertEquals(BoundaryBand.HOSTILE, BoundaryMath.classify(97.9, 0, 0, 0, r));
        assertEquals(BoundaryBand.WALL,    BoundaryMath.classify(98.5, 0, 0, 0, r));
        assertEquals(BoundaryBand.VISUAL,  BoundaryMath.classify(110,  0, 0, 0, r));
        assertEquals(BoundaryBand.BEYOND,  BoundaryMath.classify(150,  0, 0, 0, r));
    }

    @Test void clampInside_pushesBackToHardWall() {
        double r = 100.0;
        double[] clamped = BoundaryMath.clampToWall(120, 0, 0, 0, r);
        double d = BoundaryMath.distanceFromCenter(clamped[0], clamped[1], 0, 0);
        assertEquals(98.0, d, 1e-6);   // r - HARD_WALL_INSET (2.0)
    }

    @Test void centerNonZero_offsetCorrectly() {
        // Center at (100, 200). Point (105, 200) should be distance 5 from center.
        assertEquals(5.0, BoundaryMath.distanceFromCenter(105, 200, 100, 200), 1e-9);
        // Same point with center (0,0) is distance sqrt(105²+200²) ≈ 225.
        assertTrue(BoundaryMath.distanceFromCenter(105, 200, 0, 0) > 200);
    }
}
