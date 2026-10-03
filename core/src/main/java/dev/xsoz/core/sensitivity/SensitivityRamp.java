package dev.xsoz.core.sensitivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The sensitivity adaptation ramp: 7 stages from a comfortable starting travel down to
 * the player's chosen target (contracts.md C6.5, {@code [CONTRACT DECISION] D2}).
 *
 * <p><strong>THE TABLE IS A LITERAL. It is NOT computed by a generator.</strong></p>
 *
 * <pre>
 *   A geometric 1.5x generator anchored at 6.100 produces:
 *       6.1000, 4.0667, 2.7111, 1.8074, 1.2049, 0.8033, 0.5355
 *   and the sixth step, 0.8033 / 1.5 = 0.5355, OVERSHOOTS past the 0.744140625
 *   target and is FASTER than it.
 *
 *   The final stage must therefore be a PARTIAL step, and a generator cannot express
 *   "stop at the target". Baking the table is the only way to guarantee the last stage
 *   lands ON the target rather than past it.
 * </pre>
 *
 * <p>Stage ratios: {@code 6.100/4.070 = 1.4994}, then 1.5 five times, then
 * {@code 0.800/0.744140625 = 1.0751} - a <em>partial</em> final step. The
 * "last ratio is strictly less than 1.5" assertion is the encoding of "do not
 * overshoot".</p>
 *
 * <p>A stage may only be advanced on an objective criterion from the coaching
 * subsystem, never on a date. If that subsystem is disarmed the criterion is UNKNOWN and
 * the UI must say so rather than counting days and advancing anyway.</p>
 */
public final class SensitivityRamp {

    /** The number of stages in the frozen ramp. */
    public static final int STAGE_COUNT = 7;

    /** The index of the first stage, the comfortable starting point. */
    public static final int START_STAGE_INDEX = 0;

    /** The index of the final stage, which lands exactly on the target. */
    public static final int TERMINAL_STAGE_INDEX = 6;

    /** The stage index that means "the ramp is not active". */
    public static final int RAMP_INACTIVE_STAGE_INDEX = -1;

    /** The target the ramp exists to reach: 0.744140625 cm/360. Unit: cm. */
    public static final double TARGET_CM_PER_360 = 0.744140625;

    /**
     * The seven stages, exactly as frozen in contracts.md C6.5.
     *
     * <p><strong>MUTATE AT YOUR OWN RISK.</strong> The array is exposed because the
     * contract freezes the name; {@link #stages()} is the accessor a caller should use,
     * because it hands back an unmodifiable copy.</p>
     */
    public static final RampStage[] STAGES;

    static {
        RampStage[] table = new RampStage[STAGE_COUNT];
        table[0] = new RampStage(0, 6.100, 3, 5);
        table[1] = new RampStage(1, 4.070, 3, 5);
        table[2] = new RampStage(2, 2.710, 3, 5);
        table[3] = new RampStage(3, 1.810, 3, 5);
        table[4] = new RampStage(4, 1.200, 3, 5);
        table[5] = new RampStage(5, 0.800, 3, 5);
        table[6] = new RampStage(6, TARGET_CM_PER_360, 3, 5);
        STAGES = table;
    }

    private SensitivityRamp() {
        // Utility class.
    }

    /**
     * An unmodifiable copy of the frozen table, in stage order.
     *
     * @return a defensive, unmodifiable list of exactly {@link #STAGE_COUNT} stages
     */
    public static List<RampStage> stages() {
        return Collections.unmodifiableList(new ArrayList<>(java.util.Arrays.asList(STAGES)));
    }

    /**
     * The stage whose target is nearest to a given travel, by absolute difference.
     *
     * @param cmPer360 centimetres per 360 degrees
     * @return the nearest stage, never null
     * @throws IllegalArgumentException if {@code cmPer360} is not finite and positive
     */
    public static RampStage stageForCmPer360(double cmPer360) {
        requirePositiveFinite(cmPer360, "cmPer360");
        RampStage nearest = STAGES[0];
        double bestDelta = Math.abs(STAGES[0].cmPer360() - cmPer360);
        for (int i = 1; i < STAGES.length; i++) {
            double delta = Math.abs(STAGES[i].cmPer360() - cmPer360);
            if (delta < bestDelta) {
                bestDelta = delta;
                nearest = STAGES[i];
            }
        }
        return nearest;
    }

    /**
     * The index of the stage nearest to a given travel.
     *
     * @param cmPer360 centimetres per 360 degrees
     * @return a stage index in [0, {@link #STAGE_COUNT}-1]
     * @throws IllegalArgumentException if {@code cmPer360} is not finite and positive
     */
    public static int stageIndexForProfile(double cmPer360) {
        return stageForCmPer360(cmPer360).index();
    }

    /**
     * The next stage, clamped at the terminal one. Never overshoots the target.
     *
     * @param currentIndex the stage the player is on, in [0, {@link #STAGE_COUNT}-1]
     * @return the next stage, or the terminal stage itself when already there
     * @throws IllegalArgumentException if {@code currentIndex} is outside the table
     */
    public static RampStage advance(int currentIndex) {
        requireStageIndex(currentIndex);
        if (currentIndex >= TERMINAL_STAGE_INDEX) {
            return STAGES[TERMINAL_STAGE_INDEX];
        }
        return STAGES[currentIndex + 1];
    }

    /**
     * The previous stage, clamped at the first one.
     *
     * @param currentIndex the stage the player is on, in [0, {@link #STAGE_COUNT}-1]
     * @return the previous stage, or the first stage itself when already there
     * @throws IllegalArgumentException if {@code currentIndex} is outside the table
     */
    public static RampStage regress(int currentIndex) {
        requireStageIndex(currentIndex);
        if (currentIndex <= START_STAGE_INDEX) {
            return STAGES[START_STAGE_INDEX];
        }
        return STAGES[currentIndex - 1];
    }

    /**
     * Whether the table is strictly descending in travel per full turn.
     *
     * @return true when every stage is faster than the one before it
     */
    public static boolean isMonotonicDescending() {
        for (int i = 1; i < STAGES.length; i++) {
            if (!(STAGES[i].cmPer360() < STAGES[i - 1].cmPer360())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The ratio between a stage and the one before it.
     *
     * @param index the stage index, in [1, {@link #STAGE_COUNT}-1]
     * @return a dimensionless ratio, always greater than 1 for the frozen table
     * @throws IllegalArgumentException if {@code index} is outside [1, 6]
     */
    public static double stageRatio(int index) {
        requireStageIndex(index);
        if (index < 1) {
            throw new IllegalArgumentException(
                    "stageRatio is defined from stage 1 onward; index was " + index);
        }
        return STAGES[index - 1].cmPer360() / STAGES[index].cmPer360();
    }

    /**
     * The terminal stage, which lands exactly on {@link #TARGET_CM_PER_360}.
     *
     * @return the final stage
     */
    public static RampStage terminalStage() {
        return STAGES[TERMINAL_STAGE_INDEX];
    }

    private static void requireStageIndex(int index) {
        if (index < START_STAGE_INDEX || index >= STAGES.length) {
            throw new IllegalArgumentException(
                    "stage index must be within [0, " + (STAGES.length - 1) + "]; was " + index);
        }
    }

    private static void requirePositiveFinite(double value, String name) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(
                    name + " must be a finite positive number; was " + value);
        }
    }

    /**
     * Diagnostics only. Prints the frozen table.
     *
     * @return a multi-line debug string
     */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("SensitivityRamp[");
        for (int i = 0; i < STAGES.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(String.format(Locale.ROOT, "%.9f", STAGES[i].cmPer360()));
        }
        return text.append(']').toString();
    }
}
