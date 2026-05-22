package io.github.xsirdon.mists.worldgen.density;

/**
 * Pure-math counterpart to {@link BubbleBiasFunction} — keeps the radius / fade
 * profile of the v0.18 water-world bubble in a class that doesn't depend on
 * any Minecraft types, so it can be unit-tested without bringing up the
 * registry bootstrap.
 *
 * <p>The bubble is a flat-bottomed cylinder in (x, z): inside
 * {@link #BUBBLE_RADIUS} blocks of origin the function returns the full bias
 * value; from {@link #BUBBLE_RADIUS} to {@code BUBBLE_RADIUS + FADE_BAND} it
 * smoothly fades to zero via a Hermite smoothstep; beyond that it is exactly
 * zero so vanilla terrain is untouched.
 */
public final class BubbleProfile {

    /** Radius within which the full bias is applied. */
    public static final double BUBBLE_RADIUS = 2000.0;
    /** Width of the smooth-fade annulus immediately outside the bubble. */
    public static final double FADE_BAND = 200.0;

    private BubbleProfile() {}

    /**
     * Compute the bias contribution at world coordinate (x, z).
     *
     * @param biasValue the full-bias value to apply inside the bubble
     * @param x         world block X
     * @param z         world block Z
     * @return biasValue inside the radius, 0.0 past the fade band, smoothly
     *         interpolated in between
     */
    public static double biasAt(double biasValue, double x, double z) {
        double dist = Math.sqrt(x * x + z * z);
        if (dist >= BUBBLE_RADIUS + FADE_BAND) return 0.0;
        if (dist <= BUBBLE_RADIUS)              return biasValue;
        double t = (dist - BUBBLE_RADIUS) / FADE_BAND;
        // Smoothstep: 3t² − 2t³, equivalent to Hermite ease.
        double smoothed = t * t * (3.0 - 2.0 * t);
        return biasValue * (1.0 - smoothed);
    }
}
