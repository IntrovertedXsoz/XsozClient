package dev.xsoz.core.sensitivity;

import java.util.Locale;

/**
 * One stage of the sensitivity adaptation ramp (contracts.md C6.5).
 *
 * <p>Deeply immutable (contracts.md 0.4): final fields, no setters, primitives only.</p>
 *
 * <p><strong>A stage is a TARGET, not a computation.</strong> The table is baked because
 * a geometric generator cannot express "stop at the target": see
 * {@link SensitivityRamp} for the arithmetic that a 1.5x generator gets wrong.</p>
 */
public final class RampStage {

    private final int index;
    private final double cmPer360;
    private final int minDwellDays;
    private final int maxDwellDays;

    /**
     * Creates a stage.
     *
     * @param index        the stage position, 0 (start) to 6 (target)
     * @param cmPer360     the target travel per full turn in centimetres, finite and positive
     * @param minDwellDays the minimum time a player should hold this stage, in days
     * @param maxDwellDays the maximum time a player should hold this stage, in days
     * @throws IllegalArgumentException if {@code index} is negative, if {@code cmPer360} is not
     *                                  finite and positive, or if the dwell window is inverted
     */
    public RampStage(int index, double cmPer360, int minDwellDays, int maxDwellDays) {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative; was " + index);
        }
        if (Double.isNaN(cmPer360) || Double.isInfinite(cmPer360) || cmPer360 <= 0.0) {
            throw new IllegalArgumentException(
                    "cmPer360 must be a finite positive number; was " + cmPer360);
        }
        if (minDwellDays <= 0 || maxDwellDays < minDwellDays) {
            throw new IllegalArgumentException(
                    "dwell window must satisfy 0 < minDwellDays <= maxDwellDays; was ["
                            + minDwellDays + ", " + maxDwellDays + "]");
        }
        this.index = index;
        this.cmPer360 = cmPer360;
        this.minDwellDays = minDwellDays;
        this.maxDwellDays = maxDwellDays;
    }

    /**
     * The stage position, 0 for the start stage and 6 for the target stage.
     *
     * @return a non-negative stage position
     */
    public int index() {
        return index;
    }

    /**
     * The target travel for a full 360 degree turn.
     *
     * <p>Stored as a {@code double} and never recomputed from a displayed, rounded
     * value: the final stage is {@code 0.744140625}, not {@code 0.744}.</p>
     *
     * @return centimetres per 360 degrees
     */
    public double cmPer360() {
        return cmPer360;
    }

    /**
     * The minimum time a player should hold this stage.
     *
     * @return days
     */
    public int minDwellDays() {
        return minDwellDays;
    }

    /**
     * The maximum time a player should hold this stage.
     *
     * @return days
     */
    public int maxDwellDays() {
        return maxDwellDays;
    }

    /**
     * Whether this is the final stage that a ramp ends on.
     *
     * @return true for the last stage index
     */
    public boolean isTerminal() {
        return index == SensitivityRamp.TERMINAL_STAGE_INDEX;
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
        if (!(o instanceof RampStage)) {
            return false;
        }
        RampStage other = (RampStage) o;
        return index == other.index
                && minDwellDays == other.minDwellDays
                && maxDwellDays == other.maxDwellDays
                && Double.compare(cmPer360, other.cmPer360) == 0;
    }

    /**
     * Hash consistent with {@link #equals(Object)}.
     *
     * @return a hash code
     */
    @Override
    public int hashCode() {
        int result = index;
        long bits = Double.doubleToLongBits(cmPer360);
        result = 31 * result + (int) (bits ^ (bits >>> 32));
        result = 31 * result + minDwellDays;
        result = 31 * result + maxDwellDays;
        return result;
    }

    /**
     * Diagnostics only.
     *
     * @return a debug string naming every field with its unit
     */
    @Override
    public String toString() {
        return String.format(
                Locale.ROOT,
                "RampStage[index=%d, cmPer360=%.9f, dwell=%d-%d days]",
                index, cmPer360, minDwellDays, maxDwellDays);
    }
}
