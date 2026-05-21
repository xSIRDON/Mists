package io.github.xsirdon.mists.worldgen;

import java.util.Random;

/**
 * A deterministic, seed-derived organic island mask centred at (cx, cz)
 * with a target outer radius. Distance-from-centre is perturbed by
 * smooth noise so the outline is irregular and natural-looking.
 *
 * Inside test: distance(p, center) <= radius * (1 + 0.35 * noise(p))
 *              ⇒ radius bumps in/out by up to 35% along the perimeter.
 */
public final class IslandShape {
    private final double cx, cz, radius;
    private final long seed;

    public IslandShape(double cx, double cz, double radius, long seed) {
        this.cx = cx; this.cz = cz; this.radius = radius; this.seed = seed;
    }

    public boolean contains(double x, double z) {
        double dx = x - cx, dz = z - cz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > radius * 1.4)  return false;       // fast reject
        if (dist < radius * 0.6)  return true;        // fast accept core
        double n = sampleNoise(x, z);                  // [-1, 1]
        double localR = radius * (1.0 + 0.35 * n);
        return dist <= localR;
    }

    private double sampleNoise(double x, double z) {
        // Lightweight value-noise: two octaves of seeded hash on a grid.
        return 0.6 * grad(x / 24.0, z / 24.0) + 0.4 * grad(x / 9.0, z / 9.0);
    }

    private double grad(double x, double z) {
        int xi = (int)Math.floor(x), zi = (int)Math.floor(z);
        double tx = x - xi, tz = z - zi;
        double a = hash(xi,     zi);
        double b = hash(xi + 1, zi);
        double c = hash(xi,     zi + 1);
        double d = hash(xi + 1, zi + 1);
        double ux = fade(tx), uz = fade(tz);
        double lerpX1 = a + ux * (b - a);
        double lerpX2 = c + ux * (d - c);
        return (lerpX1 + uz * (lerpX2 - lerpX1)) * 2.0 - 1.0;  // → [-1, 1]
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }

    private double hash(int x, int z) {
        long h = seed;
        h = h * 6364136223846793005L + (long) x * 1442695040888963407L;
        h = h * 6364136223846793005L + (long) z * 1442695040888963407L;
        h ^= h >>> 33;
        return ((h & 0xffffffffL) / (double) 0xffffffffL);   // [0, 1]
    }
}
