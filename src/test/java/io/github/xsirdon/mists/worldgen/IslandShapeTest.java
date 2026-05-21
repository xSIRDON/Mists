package io.github.xsirdon.mists.worldgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IslandShapeTest {

    @Test void center_isAlwaysInside() {
        IslandShape s = new IslandShape(0, 0, 30.0, 12345L);
        assertTrue(s.contains(0, 0));
    }

    @Test void farOutside_isAlwaysOutside() {
        IslandShape s = new IslandShape(0, 0, 30.0, 12345L);
        assertFalse(s.contains(200, 0));
        assertFalse(s.contains(0, 200));
        assertFalse(s.contains(150, 150));
    }

    @Test void deterministicForSameSeed() {
        IslandShape a = new IslandShape(0, 0, 30.0, 42L);
        IslandShape b = new IslandShape(0, 0, 30.0, 42L);
        for (int x = -50; x <= 50; x += 7) {
            for (int z = -50; z <= 50; z += 7) {
                assertEquals(a.contains(x, z), b.contains(x, z),
                    "mismatch at (" + x + "," + z + ")");
            }
        }
    }

    @Test void differentSeed_differentShape() {
        IslandShape a = new IslandShape(0, 0, 30.0, 1L);
        IslandShape b = new IslandShape(0, 0, 30.0, 2L);
        int diffs = 0;
        for (int x = -40; x <= 40; x += 4)
            for (int z = -40; z <= 40; z += 4)
                if (a.contains(x, z) != b.contains(x, z)) diffs++;
        assertTrue(diffs > 5, "expected at least some shape variation between seeds");
    }
}
