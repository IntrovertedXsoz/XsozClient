package dev.xsoz.core.sensitivity;

import java.util.Objects;

/**
 * The remedy for a {@code cmPer360} that vanilla cannot reach at the current DPI
 * (contracts.md C6.2).
 *
 * <p>Deeply immutable (contracts.md 0.4). This is a description, not a clamp: the
 * player is told the achievable range and the DPI that would reach it, and the
 * choice stays theirs.</p>
 */
public final class RangeHint {

    private final double minCmPer360;
    private final double maxCmPer360;
    private final int minDpi;
    private final int recommendedDpi;

    /**
     * Creates a hint.
     *
     * @param minCmPer360      the fastest sensitivity reachable at the current DPI
     * @param maxCmPer360      the slowest sensitivity reachable at the current DPI
     * @param minDpi           the lowest DPI at which the target is reachable at all
     * @param recommendedDpi   the DPI that puts the target comfortably inside the slider
     * @throws NullPointerException never; every argument is a primitive
     */
    public RangeHint(double minCmPer360, double maxCmPer360, int minDpi, int recommendedDpi) {
        this.minCmPer360 = minCmPer360;
        this.maxCmPer360 = maxCmPer360;
        this.minDpi = minDpi;
        this.recommendedDpi = recommendedDpi;
    }

    /**
     * The fastest {@code cmPer360} reachable at the current DPI - i.e. vanilla's slider top.
     *
     * @return centimetres per 360 degrees
     */
    public double minCmPer360() {
        return minCmPer360;
    }

    /**
     * The slowest {@code cmPer360} reachable at the current DPI - i.e. vanilla's slider floor.
     *
     * @return centimetres per 360 degrees
     */
    public double maxCmPer360() {
        return maxCmPer360;
    }

    /**
     * The lowest DPI at which the target is reachable inside the slider at all.
     *
     * @return counts per inch
     */
    public int minDpi() {
        return minDpi;
    }

    /**
     * The DPI that puts the target at roughly 72% of the vanilla slider.
     *
     * @return counts per inch
     */
    public int recommendedDpi() {
        return recommendedDpi;
    }

    /**
     * Builds the sentence the settings screen shows verbatim.
     *
     * @param targetCmPer360 the target the player asked for
     * @param mouseDpi       the DPI they are on
     * @return a complete, non-empty sentence
     */
    public String uiSentence(double targetCmPer360, int mouseDpi) {
        return String.format(
                java.util.Locale.ROOT,
                "%.3f cm/360 needs at least %d DPI to sit at 72%% of the vanilla slider. "
                        + "You are on %d DPI. Achievable here: %.3f to %.3f cm/360.",
                targetCmPer360, recommendedDpi, mouseDpi, minCmPer360, maxCmPer360);
    }

    /**
     * Value equality on all four fields.
     *
     * @param o the object to compare against
     * @return true when every field matches
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RangeHint)) {
            return false;
        }
        RangeHint other = (RangeHint) o;
        return minDpi == other.minDpi
                && recommendedDpi == other.recommendedDpi
                && Double.compare(minCmPer360, other.minCmPer360) == 0
                && Double.compare(maxCmPer360, other.maxCmPer360) == 0;
    }

    /**
     * Hash consistent with {@link #equals(Object)}.
     *
     * @return a hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(minCmPer360, maxCmPer360, minDpi, recommendedDpi);
    }

    /**
     * Diagnostics only.
     *
     * @return a debug string naming every field with its unit
     */
    @Override
    public String toString() {
        return "RangeHint[minCmPer360=" + minCmPer360 + ", maxCmPer360=" + maxCmPer360
                + ", minDpi=" + minDpi + ", recommendedDpi=" + recommendedDpi + "]";
    }
}
