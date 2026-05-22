package io.github.xsirdon.mists.worldgen.density;

/**
 * Pure-Java math for the v0.14 island density profile. Lives separate from
 * {@link MistsIslandDensityFunction} so it can be unit-tested without booting
 * Minecraft's registry system (the DensityFunction interface's static
 * initialiser pulls in registry bootstrap, which fails outside a real server).
 *
 * <p>The two methods here — {@link #profileTopY} and {@link #density} — are the
 * total mathematical content of v0.14. The density-function class is a thin
 * adapter that delegates to {@link #density}.
 */
public final class IslandProfile {

    /** Maximum |density| we ever return. Clamps so we don't fight vanilla noise
     *  too aggressively (also bounds the function's min/max declarations). */
    public static final double MAX_DENSITY = 64.0;

    /** Multiplier on (topY - y). 0.25 gives a soft gradient so the wrapping
     *  max() blends with vanilla noise instead of producing sheer cliffs. */
    public static final double DENSITY_SCALE = 0.25;

    /**
     * Compute density at (x, y, z) for the given config. Positive when y is
     * below the synthetic island surface (solid), negative when above (air),
     * exactly zero when the column is outside the island's underwater footprint
     * (so vanilla noise dominates 100%).
     */
    public static double density(MistsIslandConfig cfg, int x, int y, int z) {
        double dx = x - cfg.cx;
        double dz = z - cfg.cz;
        double distSq = dx * dx + dz * dz;
        double outerR = cfg.underwaterRadius + 2.0;
        if (distSq > outerR * outerR) return 0.0;

        int targetTopY = profileTopY(cfg, x, z);
        if (targetTopY == Integer.MIN_VALUE) return 0.0;

        double raw = (targetTopY - y) * DENSITY_SCALE;
        if (raw >  MAX_DENSITY) raw =  MAX_DENSITY;
        if (raw < -MAX_DENSITY) raw = -MAX_DENSITY;
        return raw;
    }

    /**
     * Same piecewise profile as {@code NaturalIslandBuilder.profileTopY} but
     * inlined here so this helper has no dependency on Minecraft classes.
     *
     * @return absolute world Y of the topmost block, or {@link Integer#MIN_VALUE}
     *         if (x, z) is outside the island's underwater footprint
     */
    public static int profileTopY(MistsIslandConfig cfg, int x, int z) {
        double dx = x - cfg.cx;
        double dz = z - cfg.cz;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist > cfg.underwaterRadius + 1.0) return Integer.MIN_VALUE;

        double coarse = sampleOctave(x, z, Math.max(20.0, cfg.surfaceRadius), cfg.worldSeed);
        double fine   = sampleOctave(x, z, Math.max( 7.0, cfg.surfaceRadius * 0.32),
                                       cfg.worldSeed ^ 0x9E3779B1L);
        double noiseAdjust = coarse * 1.3 + fine * 0.5;

        double base;
        if (dist <= cfg.surfaceRadius) {
            double t = 1.0 - (dist / cfg.surfaceRadius);
            double smoothed = t * t * (3.0 - 2.0 * t);
            base = smoothed * cfg.maxHeight;
        } else {
            double t = (dist - cfg.surfaceRadius) / (cfg.underwaterRadius - cfg.surfaceRadius);
            double curved = t * t;
            base = -curved * cfg.maxDepth;
        }

        double dampening = (dist <= cfg.surfaceRadius) ? 0.7 : 0.4;
        double profile = base + noiseAdjust * dampening;
        return cfg.seaLevel + (int) Math.round(profile);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Coherent value noise — identical to NaturalIslandBuilder's so the v0.14
    // density-function path and the v0.13 block-placing fallback produce the
    // same shape for the same seed.
    // ─────────────────────────────────────────────────────────────────────────

    public static double sampleOctave(double x, double z, double scale, long seed) {
        double sx = x / scale, sz = z / scale;
        int xi = (int) Math.floor(sx);
        int zi = (int) Math.floor(sz);
        double tx = sx - xi, tz = sz - zi;
        double a = gridHash(xi,     zi,     seed);
        double b = gridHash(xi + 1, zi,     seed);
        double c = gridHash(xi,     zi + 1, seed);
        double d = gridHash(xi + 1, zi + 1, seed);
        double ux = smoothstep(tx);
        double uz = smoothstep(tz);
        double lerp1 = a + ux * (b - a);
        double lerp2 = c + ux * (d - c);
        return (lerp1 + uz * (lerp2 - lerp1)) * 2.0 - 1.0;
    }

    public static double gridHash(int x, int z, long seed) {
        long h = seed;
        h = h * 6364136223846793005L + (long) x * 1442695040888963407L;
        h = h * 6364136223846793005L + (long) z * 1442695040888963407L;
        h ^= h >>> 33;
        return ((h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL);
    }

    public static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private IslandProfile() {}
}
