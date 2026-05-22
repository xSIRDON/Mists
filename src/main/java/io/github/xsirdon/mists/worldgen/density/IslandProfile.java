package io.github.xsirdon.mists.worldgen.density;

/**
 * Pure-Java math for the island density profile. Lives separate from
 * {@link MistsIslandDensityFunction} so it can be unit-tested without booting
 * Minecraft's registry system.
 *
 * <p><b>v0.15 changes</b> over v0.14:
 * <ul>
 *   <li><b>Domain warping</b> — the (x, z) coords are perturbed by low-frequency
 *       noise before computing distance from centre. The circular cross-section
 *       becomes an irregular blob, killing the "perfect sphere" look.</li>
 *   <li><b>3D density noise</b> — multiple octaves of 3D coherent noise are
 *       added to the density value itself, so contour banding breaks up and
 *       cliffs/overhangs form naturally.</li>
 *   <li><b>Extended falloff tail</b> — density contribution doesn't cut off
 *       abruptly at underwaterRadius; it fades smoothly over an extra band so
 *       the foundation merges seamlessly into the natural seafloor.</li>
 * </ul>
 */
public final class IslandProfile {

    /** Maximum |density| we ever return. */
    public static final double MAX_DENSITY = 64.0;

    /** Multiplier on (topY - y). Lower = softer blend with vanilla noise. */
    public static final double DENSITY_SCALE = 0.25;

    /** Width of the smooth fade band beyond underwaterRadius where our density
     *  contribution diminishes to zero instead of cutting off abruptly. */
    public static final double FADE_BAND = 32.0;

    /** Compute density at (x, y, z) for the given config. */
    public static double density(MistsIslandConfig cfg, int x, int y, int z) {
        double dxRaw = x - cfg.cx;
        double dzRaw = z - cfg.cz;
        double distRawSq = dxRaw * dxRaw + dzRaw * dzRaw;

        // Quick reject: way past influence radius.
        double maxR = cfg.underwaterRadius + FADE_BAND;
        if (distRawSq > maxR * maxR) return 0.0;

        // Domain warp — distort the position with low-frequency noise so the
        // contour ceases to be a circle. Scale of warp ~= surfaceRadius * 0.6,
        // amplitude ~= surfaceRadius * 0.25.
        double warpScale = Math.max(18.0, cfg.surfaceRadius * 0.6);
        double warpAmp   = cfg.surfaceRadius * 0.25;
        double wx = sampleOctave(x, z, warpScale, cfg.worldSeed ^ 0xA1B2C3D4L) * warpAmp;
        double wz = sampleOctave(x, z, warpScale, cfg.worldSeed ^ 0xD4C3B2A1L) * warpAmp;
        double dx = dxRaw + wx;
        double dz = dzRaw + wz;
        double dist = Math.sqrt(dx * dx + dz * dz);

        // Outside the (warped) underwater extent, contribution fades to zero
        // over FADE_BAND blocks.
        if (dist > cfg.underwaterRadius + FADE_BAND) return 0.0;

        // Get the target topY using the (warped) distance.
        int targetTopY = profileTopYFromDist(cfg, x, z, dist);

        // Base contribution: positive below targetTopY, negative above.
        double raw = (targetTopY - y) * DENSITY_SCALE;

        // 3D density noise — adds vertical variation so contour banding breaks
        // up. Two octaves of 3D coherent noise. Amplitude is largest within the
        // surface zone, tapers off into the fade band.
        double surfaceZoneStrength = clamp01(1.0 - (dist - cfg.surfaceRadius) / (cfg.underwaterRadius - cfg.surfaceRadius + 1.0));
        if (surfaceZoneStrength < 0) surfaceZoneStrength = 0;
        double noise3DCoarse = sampleOctave3D(x, y, z, 14.0, cfg.worldSeed ^ 0xCEC1BABEL);
        double noise3DFine   = sampleOctave3D(x, y, z,  6.0, cfg.worldSeed ^ 0xFEEDFACEL);
        double densityNoise = (noise3DCoarse * 0.8 + noise3DFine * 0.35) * surfaceZoneStrength;
        raw += densityNoise;

        // Smooth fade past underwaterRadius so the foundation merges with the
        // natural seafloor instead of ending in a visible cliff.
        if (dist > cfg.underwaterRadius) {
            double t = (dist - cfg.underwaterRadius) / FADE_BAND;
            if (t > 1.0) t = 1.0;
            double fade = 1.0 - t * t * (3.0 - 2.0 * t); // smoothstep, inverted
            raw *= fade;
        }

        if (raw >  MAX_DENSITY) raw =  MAX_DENSITY;
        if (raw < -MAX_DENSITY) raw = -MAX_DENSITY;
        return raw;
    }

    /**
     * Convenience overload used by tests and external callers that want a
     * topY without computing density. Returns {@link Integer#MIN_VALUE} if
     * (x, z) is outside the underwater extent.
     */
    public static int profileTopY(MistsIslandConfig cfg, int x, int z) {
        double dxRaw = x - cfg.cx;
        double dzRaw = z - cfg.cz;

        double warpScale = Math.max(18.0, cfg.surfaceRadius * 0.6);
        double warpAmp   = cfg.surfaceRadius * 0.25;
        double wx = sampleOctave(x, z, warpScale, cfg.worldSeed ^ 0xA1B2C3D4L) * warpAmp;
        double wz = sampleOctave(x, z, warpScale, cfg.worldSeed ^ 0xD4C3B2A1L) * warpAmp;
        double dx = dxRaw + wx;
        double dz = dzRaw + wz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > cfg.underwaterRadius + 1.0) return Integer.MIN_VALUE;
        return profileTopYFromDist(cfg, x, z, dist);
    }

    private static int profileTopYFromDist(MistsIslandConfig cfg, int x, int z, double dist) {
        // Two octaves of horizontal noise — same as before, modulating the
        // top-Y profile so the surface doesn't sit at a flat level.
        double coarse = sampleOctave(x, z, Math.max(20.0, cfg.surfaceRadius), cfg.worldSeed);
        double fine   = sampleOctave(x, z, Math.max( 7.0, cfg.surfaceRadius * 0.32),
                                       cfg.worldSeed ^ 0x9E3779B1L);
        double noiseAdjust = coarse * 1.3 + fine * 0.5;

        double base;
        if (dist <= cfg.surfaceRadius) {
            // Above-water profile: smoothstep falloff from maxHeight at centre.
            double t = 1.0 - (dist / cfg.surfaceRadius);
            // Slightly biased smoothstep so the surface is flatter near the
            // edge and rises faster near centre — breaks the "perfect dome"
            // silhouette without making the centre too steep.
            double smoothed = t * t * (3.0 - 2.0 * t);
            base = smoothed * cfg.maxHeight;
        } else {
            // Underwater taper.
            double t = (dist - cfg.surfaceRadius) / (cfg.underwaterRadius - cfg.surfaceRadius);
            double curved = t * t;
            base = -curved * cfg.maxDepth;
        }

        // Larger noise amplitude on the surface than v0.14, so contours break up.
        double dampening = (dist <= cfg.surfaceRadius) ? 1.2 : 0.5;
        double profile = base + noiseAdjust * dampening;
        return cfg.seaLevel + (int) Math.round(profile);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Noise helpers — coherent value noise, used in 2D for the height profile
    // and warp field, and 3D for the density variation.
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

    public static double sampleOctave3D(double x, double y, double z, double scale, long seed) {
        double sx = x / scale, sy = y / scale, sz = z / scale;
        int xi = (int) Math.floor(sx);
        int yi = (int) Math.floor(sy);
        int zi = (int) Math.floor(sz);
        double tx = sx - xi, ty = sy - yi, tz = sz - zi;
        double c000 = gridHash3D(xi,     yi,     zi,     seed);
        double c100 = gridHash3D(xi + 1, yi,     zi,     seed);
        double c010 = gridHash3D(xi,     yi + 1, zi,     seed);
        double c110 = gridHash3D(xi + 1, yi + 1, zi,     seed);
        double c001 = gridHash3D(xi,     yi,     zi + 1, seed);
        double c101 = gridHash3D(xi + 1, yi,     zi + 1, seed);
        double c011 = gridHash3D(xi,     yi + 1, zi + 1, seed);
        double c111 = gridHash3D(xi + 1, yi + 1, zi + 1, seed);
        double ux = smoothstep(tx);
        double uy = smoothstep(ty);
        double uz = smoothstep(tz);
        double l00 = c000 + ux * (c100 - c000);
        double l10 = c010 + ux * (c110 - c010);
        double l01 = c001 + ux * (c101 - c001);
        double l11 = c011 + ux * (c111 - c011);
        double l0  = l00  + uy * (l10  - l00);
        double l1  = l01  + uy * (l11  - l01);
        return (l0 + uz * (l1 - l0)) * 2.0 - 1.0;
    }

    public static double gridHash(int x, int z, long seed) {
        long h = seed;
        h = h * 6364136223846793005L + (long) x * 1442695040888963407L;
        h = h * 6364136223846793005L + (long) z * 1442695040888963407L;
        h ^= h >>> 33;
        return ((h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL);
    }

    public static double gridHash3D(int x, int y, int z, long seed) {
        long h = seed;
        h = h * 6364136223846793005L + (long) x * 1442695040888963407L;
        h = h * 6364136223846793005L + (long) y * 1442695040888963407L;
        h = h * 6364136223846793005L + (long) z * 1442695040888963407L;
        h ^= h >>> 33;
        return ((h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL);
    }

    public static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private IslandProfile() {}
}
