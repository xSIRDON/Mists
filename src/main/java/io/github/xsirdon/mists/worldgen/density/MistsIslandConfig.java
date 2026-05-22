package io.github.xsirdon.mists.worldgen.density;

/**
 * Deterministic per-seed island parameters. Computed purely from the world seed
 * so the same seed always yields the same island, with no dependency on
 * dimension load order.
 *
 * <p>The mixin that wraps {@code finalDensity} reads this config to know where
 * and how big to make the synthetic island. The IslandPlacer (which fires later
 * in the lifecycle) computes the same config to set the world spawn.
 */
public final class MistsIslandConfig {

    /** Magic mask combined with the world seed so our derived RNG stream is
     *  independent of any vanilla generators that also reuse the seed. */
    public static final long SEED_MASK = 0x4D49535453L; // "MISTS"

    public final int cx;
    public final int cz;
    public final int seaLevel;
    public final double surfaceRadius;
    public final double underwaterRadius;
    public final int maxHeight;
    public final int maxDepth;
    /** Original world seed used to derive the noise channels. */
    public final long worldSeed;

    public MistsIslandConfig(int cx, int cz, int seaLevel,
                              double surfaceRadius, double underwaterRadius,
                              int maxHeight, int maxDepth, long worldSeed) {
        this.cx = cx;
        this.cz = cz;
        this.seaLevel = seaLevel;
        this.surfaceRadius = surfaceRadius;
        this.underwaterRadius = underwaterRadius;
        this.maxHeight = maxHeight;
        this.maxDepth = maxDepth;
        this.worldSeed = worldSeed;
    }

    /**
     * Derive the island for a given world seed using a deterministic RNG that
     * is independent of any vanilla noise stream. Same seed → same island.
     */
    public static MistsIslandConfig deriveFromSeed(long worldSeed, int seaLevel) {
        long mistsSeed = worldSeed ^ SEED_MASK;
        java.util.Random rng = new java.util.Random(mistsSeed);
        double angle = rng.nextDouble() * 2.0 * Math.PI;
        // 200..1000 blocks from origin — close enough that the first chunks
        // generated near (0,0) won't include our terrain, far enough to feel
        // exploratory.
        double dist = 200.0 + rng.nextDouble() * 800.0;
        int cx = (int) Math.round(Math.cos(angle) * dist);
        int cz = (int) Math.round(Math.sin(angle) * dist);

        // Profile parameters chosen to make a small "starter island" — ~24 block
        // surface radius, ~50 block underwater footprint, capped at 7 blocks above
        // sea level. The 12-block depth lets the foundation actually reach into
        // the seafloor on most terrain.
        double surfaceR    = 24.0;
        double underwaterR = 50.0;
        int maxHeight = 7;
        int maxDepth  = 12;

        return new MistsIslandConfig(cx, cz, seaLevel, surfaceR, underwaterR,
                                       maxHeight, maxDepth, worldSeed);
    }
}
