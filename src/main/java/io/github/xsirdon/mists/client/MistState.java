package io.github.xsirdon.mists.client;

/** Client-side cache of the player's current mist boundary. */
public final class MistState {

    public static volatile double currentRadius = Double.POSITIVE_INFINITY;
    public static volatile double animationFromRadius = Double.POSITIVE_INFINITY;
    public static volatile long   animationStartedAtMillis = 0L;
    public static volatile double centerX = 0.0;
    public static volatile double centerZ = 0.0;
    /** Most recent tier ordinal received from the server (ONE=0..OPEN=4). */
    public static volatile int    currentTier = 0;
    /** The tier ordinal immediately prior to the last apply() — used by the UI to detect unlocks. */
    public static volatile int    previousTier = 0;
    public static final  long     ANIMATION_DURATION_MS = 3_000L;

    public static double effectiveRadius() {
        long elapsed = System.currentTimeMillis() - animationStartedAtMillis;
        if (elapsed >= ANIMATION_DURATION_MS) return currentRadius;
        double t = elapsed / (double) ANIMATION_DURATION_MS;
        // Ease-out cubic
        double eased = 1.0 - Math.pow(1.0 - t, 3.0);
        return animationFromRadius + (currentRadius - animationFromRadius) * eased;
    }

    public static void apply(double newRadius, double animateFrom, double cx, double cz, int tierOrdinal) {
        animationFromRadius = animateFrom;
        currentRadius = newRadius;
        animationStartedAtMillis = System.currentTimeMillis();
        centerX = cx;
        centerZ = cz;
        if (tierOrdinal > currentTier) {
            previousTier = currentTier;
            currentTier = tierOrdinal;
        } else {
            previousTier = currentTier;
            currentTier = tierOrdinal;
        }
    }

    private MistState() {}
}
