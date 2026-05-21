package io.github.xsirdon.mists.boundary;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadiusMathTest {

    @Test void distanceFromSpawn_2d() {
        assertEquals(0.0,  BoundaryMath.distanceFromSpawn(0,  0),  1e-9);
        assertEquals(5.0,  BoundaryMath.distanceFromSpawn(3,  4),  1e-9);
        assertEquals(13.0, BoundaryMath.distanceFromSpawn(-5, 12), 1e-9);
    }

    @Test void band_classification() {
        double r = 100.0;
        assertEquals(BoundaryBand.SAFE,    BoundaryMath.classify(50,   0, r));
        assertEquals(BoundaryBand.HOSTILE, BoundaryMath.classify(92,   0, r));
        assertEquals(BoundaryBand.HOSTILE, BoundaryMath.classify(97.9, 0, r));
        assertEquals(BoundaryBand.WALL,    BoundaryMath.classify(98.5, 0, r));
        assertEquals(BoundaryBand.VISUAL,  BoundaryMath.classify(110,  0, r));
        assertEquals(BoundaryBand.BEYOND,  BoundaryMath.classify(150,  0, r));
    }

    @Test void clampInside_pushesBackToHardWall() {
        double r = 100.0;
        double[] clamped = BoundaryMath.clampToWall(120, 0, r);
        double d = BoundaryMath.distanceFromSpawn(clamped[0], clamped[1]);
        assertEquals(98.0, d, 1e-6);   // r - HARD_WALL_INSET (2.0)
    }
}
