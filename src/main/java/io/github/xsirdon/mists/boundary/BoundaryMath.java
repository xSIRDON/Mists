package io.github.xsirdon.mists.boundary;

import static io.github.xsirdon.mists.MistsConstants.*;

public final class BoundaryMath {

    public static double distanceFromSpawn(double x, double z) {
        return Math.sqrt(x * x + z * z);
    }

    public static BoundaryBand classify(double x, double z, double radius) {
        double d = distanceFromSpawn(x, z);
        double wallInner = radius - HARD_WALL_INSET;
        double hostileInner = wallInner - HOSTILE_BAND_THICKNESS;
        double visualOuter = radius + VISUAL_BAND_THICKNESS;
        if (d < hostileInner) return BoundaryBand.SAFE;
        if (d < wallInner)    return BoundaryBand.HOSTILE;
        if (d < radius)       return BoundaryBand.WALL;
        if (d < visualOuter)  return BoundaryBand.VISUAL;
        return BoundaryBand.BEYOND;
    }

    /** Returns {x, z} clamped so the player sits at radius - HARD_WALL_INSET. */
    public static double[] clampToWall(double x, double z, double radius) {
        double d = distanceFromSpawn(x, z);
        if (d <= radius - HARD_WALL_INSET) return new double[]{x, z};
        double scale = (radius - HARD_WALL_INSET) / d;
        return new double[]{ x * scale, z * scale };
    }

    private BoundaryMath() {}
}
