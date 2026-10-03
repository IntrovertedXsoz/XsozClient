package dev.xsoz.core.config;

import dev.xsoz.core.XsozContractException;
import dev.xsoz.core.sensitivity.FovRelativeMode;
import dev.xsoz.core.sensitivity.RampStage;
import dev.xsoz.core.sensitivity.SensitivityModel;
import dev.xsoz.core.sensitivity.SensitivityRamp;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The stored sensitivity block: the canonical {@code cmPer360}, the player's DPI, the
 * FOV the model was built at, the FOV-relative mode, and the adaptation ramp's position
 * (contracts.md C5.2, C6.1, C6.5).
 *
 * ============================ WHAT IS STORED, AND WHY =============================
 *
 * <p><strong>{@code cmPer360} is the stored quantity, and it is the only sensitivity
 * number in the file</strong> ({@code [CONTRACT DECISION] D1}). The vanilla slider value
 * {@code s} is DERIVED on read and is never persisted, and there is no
 * {@code sensitivityRaw} key anywhere: two persisted numbers describing one physical fact
 * is exactly how this project produced a 5.8x error and then a 6.667x one. A percentage
 * is unintelligible across DPI; a distance in centimetres is not, because it is the thing
 * the player's hand actually does.</p>
 *
 * <p><strong>All arithmetic is delegated to {@link SensitivityModel}.</strong> This type
 * stores and sequences; it computes nothing. Inventing a second formula here is how the
 * 8.0 trap gets walked into twice.</p>
 *
 * ============================ THE RAMP IS FIRST-CLASS =============================
 *
 * <p><strong>{@code cmPer360} is the TARGET. {@code rampStageIndex} is where the player
 * currently is on the way to it.</strong> A profile starts at
 * {@code 0.744140625} - the sourced point - with the ramp <em>active</em> at stage 0,
 * whose target is {@code 6.100} cm/360. {@link #effectiveCmPer360()} is what the game
 * should actually be set to today; {@link #cmPer360()} is where the player is going.</p>
 *
 * <pre>
 *   cmPer360 = 0.744140625   <- the sourced target, FOV 90, DPI 2000, s = 1.0
 *   rampStageIndex = 0       <- the ramp is ACTIVE, and today is 6.100 cm/360
 *   effectiveCmPer360()      -> 6.100   (stage 0)
 *
 *   withRampInactive()       -> rampStageIndex = -1, effective = 0.744140625
 * </pre>
 *
 * <p><strong>The stage table is not stored and is not generated here.</strong>
 * {@link SensitivityRamp} owns it, because a 1.5x generator anchored at 6.10 lands on
 * 0.5355 and overshoots the target (C6.5). This type stores an <em>index</em> into that
 * table and nothing else, so a table change is a code change and every profile follows
 * it.</p>
 *
 * <p><strong>{@code -1} is the only "ramp off" value</strong> ({@code
 * SensitivityRamp.RAMP_INACTIVE_STAGE_INDEX}), and it is the state a player is in after
 * overriding {@code cmPer360} by hand. It is a state, not a magic number: the constant is
 * named and the accessor is {@link #rampActive()}.</p>
 *
 * <p>Deeply immutable; every {@code with} returns a copy.</p>
 */
public final class ProfileSensitivity {

    private final SensitivityModel target;
    private final int rampStageIndex;

    private ProfileSensitivity(SensitivityModel target, int rampStageIndex) {
        this.target = target;
        this.rampStageIndex = rampStageIndex;
    }

    /**
     * A block with no ramp: the stored {@code cmPer360} is what the game gets today.
     *
     * @param cmPer360       the canonical stored travel. Unit: centimetres per 360 degrees
     * @param mouseDpi       the player's mouse resolution. Unit: counts per inch
     * @param fovVerticalDeg the vertical FOV the model is built at. Unit: degrees
     * @param fovRelativeMode the FOV-relative mode, non-null
     * @return the block
     * @throws XsozContractException if any argument is out of range
     */
    public static ProfileSensitivity ofCmPer360(double cmPer360, int mouseDpi, int fovVerticalDeg,
                                                FovRelativeMode fovRelativeMode) {
        return new ProfileSensitivity(
                SensitivityModel.ofCmPer360(cmPer360, mouseDpi, fovVerticalDeg, fovRelativeMode),
                SensitivityRamp.RAMP_INACTIVE_STAGE_INDEX);
    }

    /**
     * A block with the adaptation ramp active at a given stage.
     *
     * @param cmPer360       the canonical stored TARGET travel. Unit: centimetres per 360
     *                       degrees
     * @param mouseDpi       the player's mouse resolution. Unit: counts per inch
     * @param fovVerticalDeg the vertical FOV the model is built at. Unit: degrees
     * @param fovRelativeMode the FOV-relative mode, non-null
     * @param rampStageIndex the stage the player is on, 0..6
     * @return the block
     * @throws XsozContractException if any argument is out of range, or the stage index is
     *                               outside [0, 6]
     */
    public static ProfileSensitivity withRampStage(double cmPer360, int mouseDpi,
                                                   int fovVerticalDeg,
                                                   FovRelativeMode fovRelativeMode,
                                                   int rampStageIndex) {
        if (rampStageIndex < SensitivityRamp.START_STAGE_INDEX
                || rampStageIndex > SensitivityRamp.TERMINAL_STAGE_INDEX) {
            throw new XsozContractException(
                    "rampStageIndex must be within [" + SensitivityRamp.START_STAGE_INDEX + ", "
                            + SensitivityRamp.TERMINAL_STAGE_INDEX + "], or "
                            + SensitivityRamp.RAMP_INACTIVE_STAGE_INDEX
                            + " for an inactive ramp; was " + rampStageIndex
                            + ". The table has exactly " + SensitivityRamp.STAGE_COUNT + " stages.");
        }
        return new ProfileSensitivity(
                SensitivityModel.ofCmPer360(cmPer360, mouseDpi, fovVerticalDeg, fovRelativeMode),
                rampStageIndex);
    }

    /**
     * @return the canonical stored target travel. Unit: centimetres per 360 degrees. This
     *         is the {@code cmPer360} field of the profile file, and nothing else is.
     */
    public double cmPer360() {
        return target.cmPer360();
    }

    /**
     * @return the player's mouse resolution. Unit: counts per inch
     */
    public int mouseDpi() {
        return target.mouseDpi();
    }

    /**
     * @return the vertical FOV the model is built at. Unit: degrees
     */
    public int fovVerticalDeg() {
        return target.fovVerticalDeg();
    }

    /** @return the FOV-relative mode, never null */
    public FovRelativeMode fovRelativeMode() {
        return target.fovRelativeMode();
    }

    /**
     * @return the target model: the sourced point the ramp is walking towards. Its
     *         {@code cmPer360} is the stored field, unchanged by the ramp.
     */
    public SensitivityModel targetModel() {
        return target;
    }

    /**
     * @return the stored ramp stage index, or
     *         {@link SensitivityRamp#RAMP_INACTIVE_STAGE_INDEX} when the ramp is off
     */
    public int rampStageIndex() {
        return rampStageIndex;
    }

    /** @return whether the adaptation ramp is currently in force */
    public boolean rampActive() {
        return rampStageIndex >= SensitivityRamp.START_STAGE_INDEX;
    }

    /**
     * @return the stage the player is on
     * @throws XsozContractException if the ramp is inactive; use {@link #rampActive()}
     */
    public RampStage currentStage() {
        requireActiveRamp();
        return SensitivityRamp.STAGES[rampStageIndex];
    }

    /** @return the current stage, empty when the ramp is inactive */
    public Optional<RampStage> optCurrentStage() {
        return rampActive() ? Optional.of(SensitivityRamp.STAGES[rampStageIndex]) : Optional.<RampStage>empty();
    }

    /** @return the stage the ramp finishes on, which is the stored target */
    public RampStage terminalStage() {
        return SensitivityRamp.terminalStage();
    }

    /**
     * The travel the game should be set to <em>today</em>.
     *
     * <p>With the ramp active this is the current stage's target; with the ramp inactive
     * it is the stored {@code cmPer360}. Note the two are different numbers by
     * construction while a ramp is running - that is the entire point of a ramp.</p>
     *
     * @return today's effective travel. Unit: centimetres per 360 degrees
     */
    public double effectiveCmPer360() {
        return rampActive() ? SensitivityRamp.STAGES[rampStageIndex].cmPer360() : cmPer360();
    }

    /**
     * @return the model the game should use today, i.e. the target model with
     *         {@link #effectiveCmPer360()} substituted for {@link #cmPer360()}
     */
    public SensitivityModel effectiveModel() {
        double effective = effectiveCmPer360();
        return effective == cmPer360() ? target : target.withCmPer360(effective);
    }

    /**
     * The derived vanilla slider value for the effective travel. Never persisted.
     *
     * @return the slider value, dimensionless; may sit outside [0,1] for a target that
     *         vanilla cannot reach at the current DPI
     */
    public double effectiveSliderRatio() {
        return effectiveModel().s();
    }

    /**
     * @return whether today's effective travel is reachable inside the vanilla slider
     */
    public boolean effectiveIsInsideVanillaSlider() {
        return effectiveModel().isInsideVanillaSlider();
    }

    /**
     * @return how many stages remain before the ramp reaches the stored target; zero when
     *         the ramp is inactive or already on the last stage
     */
    public int remainingStages() {
        if (!rampActive()) {
            return 0;
        }
        return SensitivityRamp.TERMINAL_STAGE_INDEX - rampStageIndex;
    }

    /**
     * @param newIndex the stage to move to, 0..6
     * @return a copy at that stage
     * @throws XsozContractException if the index is outside the table
     */
    public ProfileSensitivity withRampStageIndex(int newIndex) {
        if (newIndex < SensitivityRamp.START_STAGE_INDEX
                || newIndex > SensitivityRamp.TERMINAL_STAGE_INDEX) {
            throw new XsozContractException(
                    "rampStageIndex must be within [0, " + SensitivityRamp.TERMINAL_STAGE_INDEX
                            + "]; was " + newIndex + ".");
        }
        return new ProfileSensitivity(target, newIndex);
    }

    /**
     * @return a copy one stage further along, clamped at the terminal stage. Never
     *         overshoots the target: the last stage IS the target.
     */
    public ProfileSensitivity advanced() {
        if (!rampActive()) {
            return this;
        }
        return new ProfileSensitivity(target, SensitivityRamp.advance(rampStageIndex).index());
    }

    /**
     * @return a copy one stage back, clamped at stage 0
     */
    public ProfileSensitivity regressed() {
        if (!rampActive()) {
            return this;
        }
        return new ProfileSensitivity(target, SensitivityRamp.regress(rampStageIndex).index());
    }

    /**
     * Turns the ramp off, leaving the stored target in force from today.
     *
     * <p>This is the "the player overrode the target by hand" state: the override and
     * the ramp are the same field, so overriding {@code cmPer360} and deactivating the
     * ramp cannot drift apart.</p>
     *
     * @return a copy with {@code rampStageIndex == -1}
     */
    public ProfileSensitivity withRampInactive() {
        return new ProfileSensitivity(target, SensitivityRamp.RAMP_INACTIVE_STAGE_INDEX);
    }

    /**
     * @param newCmPer360 the new target travel. Unit: centimetres per 360 degrees
     * @return a copy with the target changed, keeping the ramp position
     * @throws XsozContractException if the value is not finite and positive
     */
    public ProfileSensitivity withCmPer360(double newCmPer360) {
        return new ProfileSensitivity(target.withCmPer360(newCmPer360), rampStageIndex);
    }

    /**
     * @param newDpi counts per inch; the stored {@code cmPer360} is held CONSTANT (C6.3)
     * @return a copy at the new DPI
     * @throws XsozContractException if {@code newDpi} is not strictly positive
     */
    public ProfileSensitivity withMouseDpi(int newDpi) {
        return new ProfileSensitivity(target.withMouseDpi(newDpi), rampStageIndex);
    }

    /**
     * @param newFovVerticalDeg the new vertical FOV. Unit: degrees
     * @return a copy at the new FOV, with this block's {@link #fovRelativeMode()} applied
     *         to the TARGET model. The ramp stage's own travel is a target too, not a
     *         physical fact about the mouse, so it does not move with FOV.
     * @throws XsozContractException if the FOV is out of range, or
     *                               {@code SCREEN_SPEED} pushes the slider out of [0,1]
     */
    public ProfileSensitivity withFov(int newFovVerticalDeg) {
        return new ProfileSensitivity(target.withFov(newFovVerticalDeg), rampStageIndex);
    }

    /**
     * @param newMode the new FOV-relative mode
     * @return a copy with the mode changed
     * @throws XsozContractException if {@code newMode} is {@code null}
     */
    public ProfileSensitivity withFovRelativeMode(FovRelativeMode newMode) {
        if (newMode == null) {
            throw new XsozContractException("The FOV-relative mode must not be null.");
        }
        return new ProfileSensitivity(
                SensitivityModel.ofCmPer360(cmPer360(), mouseDpi(), fovVerticalDeg(), newMode),
                rampStageIndex);
    }

    private void requireActiveRamp() {
        if (!rampActive()) {
            throw new XsozContractException(
                    "The ramp is not active (rampStageIndex is " + rampStageIndex
                            + "), so there is no current stage. Ask rampActive() first; a stage "
                            + "read from an inactive ramp is the question \"what is stage -1?\".");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProfileSensitivity)) {
            return false;
        }
        ProfileSensitivity that = (ProfileSensitivity) other;
        return rampStageIndex == that.rampStageIndex && target.equals(that.target);
    }

    @Override
    public int hashCode() {
        return 31 * target.hashCode() + rampStageIndex;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "ProfileSensitivity[cmPer360=%.9f, mouseDpi=%d, fovVerticalDeg=%d, mode=%s, "
                        + "rampStageIndex=%d, effectiveCmPer360=%.9f]",
                cmPer360(), mouseDpi(), fovVerticalDeg(), fovRelativeMode(), rampStageIndex,
                effectiveCmPer360());
    }

    /**
     * @return the number of stages in the frozen table, for callers that size a UI list
     */
    public static int stageCount() {
        return SensitivityRamp.STAGE_COUNT;
    }

    /**
     * @return the stage the player would be placed at for a given stored travel, or -1
     * @see OptionalInt
     */
    public static OptionalInt stageIndexNearestTo(double cmPer360) {
        return OptionalInt.of(SensitivityRamp.stageIndexForProfile(cmPer360));
    }
}
