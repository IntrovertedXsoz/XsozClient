package dev.xsoz.client.training;

/**
 * "Natural Speedup/Slowdown": an adaptive staircase on the drill's difficulty p (0 = easiest,
 * 1 = the human limit used by Level Up).
 *
 * <p>Transformed up-down rule (Levitt 1971, J. Acoust. Soc. Am. 49:467): after {@link #UP_AFTER}
 * successes in a row the drill gets harder; after any miss it gets easier. A 3-down/1-up rule
 * converges on the difficulty you hit about 79% of the time - hard enough to improve, easy enough
 * not to break your rhythm. The step down is larger than the step up so a bad streak recovers
 * quickly. Pure Java; unit tested.</p>
 */
public final class Staircase {
    public static final int UP_AFTER = 3;
    public static final double STEP_UP = 0.05;
    public static final double STEP_DOWN = 0.08;

    private double p;
    private int streak;
    private double peak;

    public Staircase(double start) {
        this.p = clamp(start);
        this.peak = this.p;
    }

    public double p() { return p; }

    /** Highest difficulty reached this run. */
    public double peak() { return peak; }

    public void record(boolean success) {
        if (success) {
            streak++;
            if (streak >= UP_AFTER) {
                p = clamp(p + STEP_UP);
                streak = 0;
            }
        } else {
            p = clamp(p - STEP_DOWN);
            streak = 0;
        }
        peak = Math.max(peak, p);
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
}
