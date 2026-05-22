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
        // v0.18: place the island within the 2000-block "water-world bubble"
        // that the noise-router wrap forces to ocean. The bubble guarantees
        // ocean biome + ocean terrain inside its radius, so the deterministic
        // position no longer needs a runtime biome-relocation pass — the only
        // land within 32+ chunks of spawn is our own island.
        //
        // 200..800 blocks gives the island a bit of distance from world origin
        // (so spawn isn't right on top of the player's view-distance edge) but
        // keeps it well inside the bubble before the fade band begins at 1800.
        double dist = 200.0 + rng.nextDouble() * 600.0;
        int cx = (int) Math.round(Math.cos(angle) * dist);
        int cz = (int) Math.round(Math.sin(angle) * dist);

        // Profile parameters. surfaceR is the island's above-water radius;
        // underwaterR is where the sloped foundation reaches the seafloor.
        double surfaceR    = 24.0;
        double underwaterR = 50.0;
        int maxHeight = 7;
        int maxDepth  = 12;

        return new MistsIslandConfig(cx, cz, seaLevel, surfaceR, underwaterR,
                                       maxHeight, maxDepth, worldSeed);
    }
}
