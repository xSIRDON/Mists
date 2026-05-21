package io.github.xsirdon.mists.boundary;

import static io.github.xsirdon.mists.MistsConstants.*;

public final class BoundaryMath {

    public static double distanceFromCenter(double x, double z, double cx, double cz) {
        double dx = x - cx;
        double dz = z - cz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static BoundaryBand classify(double x, double z, double cx, double cz, double radius) {
        double d = distanceFromCenter(x, z, cx, cz);
        double wallInner = radius - HARD_WALL_INSET;
        double hostileInner = wallInner - HOSTILE_BAND_THICKNESS;
        double visualOuter = radius + VISUAL_BAND_THICKNESS;
        if (d < hostileInner) return BoundaryBand.SAFE;
        if (d < wallInner)    return BoundaryBand.HOSTILE;
        if (d < radius)       return BoundaryBand.WALL;
        if (d < visualOuter)  return BoundaryBand.VISUAL;
        return BoundaryBand.BEYOND;
    }

    /** Returns {x, z} clamped so the player sits at radius - HARD_WALL_INSET from (cx, cz). */
    public static double[] clampToWall(double x, double z, double cx, double cz, double radius) {
        double dx = x - cx;
        double dz = z - cz;
        double d = Math.sqrt(dx * dx + dz * dz);
        double inner = radius - HARD_WALL_INSET;
        if (d <= inner) return new double[]{x, z};
        double scale = inner / d;
        return new double[]{ cx + dx * scale, cz + dz * scale };
    }

    private BoundaryMath() {}
}
