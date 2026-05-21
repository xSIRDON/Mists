package io.github.xsirdon.mists.client;

/** Client-side cache of the player's current mist boundary. */
public final class MistState {

    public static volatile double currentRadius = Double.POSITIVE_INFINITY;
    public static volatile double animationFromRadius = Double.POSITIVE_INFINITY;
    public static volatile long   animationStartedAtMillis = 0L;
    public static final  long     ANIMATION_DURATION_MS = 3_000L;

    public static double effectiveRadius() {
        long elapsed = System.currentTimeMillis() - animationStartedAtMillis;
        if (elapsed >= ANIMATION_DURATION_MS) return currentRadius;
        double t = elapsed / (double) ANIMATION_DURATION_MS;
        // Ease-out cubic
        double eased = 1.0 - Math.pow(1.0 - t, 3.0);
        return animationFromRadius + (currentRadius - animationFromRadius) * eased;
    }

    public static void apply(double newRadius, double animateFrom) {
        animationFromRadius = animateFrom;
        currentRadius = newRadius;
        animationStartedAtMillis = System.currentTimeMillis();
    }

    private MistState() {}
}
