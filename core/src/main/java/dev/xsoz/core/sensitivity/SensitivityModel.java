package dev.xsoz.core.sensitivity;

import dev.xsoz.core.XsozContractException;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The canonical sensitivity model (contracts.md C6).
 *
 * <p><strong>The stored unit is {@code cmPer360}, and only {@code cmPer360}</strong>
 * (C6.1, {@code [CONTRACT DECISION] D1}). The vanilla slider value {@code s} is
 * DERIVED on read and is never persisted; {@code mouseDpi} is persisted because it is
 * a hardware fact, not a derived one. Two persisted numbers describing one physical
 * fact is exactly how this project produced a 5.8x error and then a 6.667x error.</p>
 *
 * <p>Deeply immutable (contracts.md 0.4): final fields, no setters, every factory
 * validates eagerly and throws rather than clamping.</p>
 *
 * ============================ THE 8.0 TRAP =============================
 * Do NOT "simplify" the coefficient from 1.2 to 8.0. This project has already been
 * burned by this and by its two ancestors.
 *
 * The vanilla call chain is TWO methods in TWO files:
 *
 * <pre>
 *   MouseHandler.turnPlayer(double dx, double dy):
 *       ss   = s * 0.6F + 0.2F
 *       sens = ss * ss * ss * 8.0F        &lt;-- 8.0 is a HALF-FORMED intermediate
 *       player.turn(dx * sens, dy * sens)
 *
 *   Entity.turn(double xo, double yo):
 *       yDelta = (float) xo * 0.15F        &lt;-- the 0.15 lives HERE, a different file
 *       setYRot(yRot + yDelta)
 *
 *   =&gt; 8.0 * 0.15 = 1.2, and the factor spans two methods.
 * </pre>
 *
 * <pre>
 *   WRONG (truncated at MouseHandler):   0.8^3 * 8.0 = 4.096  deg/count
 *   RIGHT (both methods):                0.8^3 * 1.2 = 0.6144  deg/count
 *   ratio: 4.096 / 0.6144 = 6.66667 = 1 / 0.15   &lt;-- the dropped Entity.turn factor
 * </pre>
 *
 * The 8.0 is genuine vanilla code and is quoted correctly by upstream sources. The
 * bug is reading one method of a two-method call chain and stopping at the
 * half-finished value. {@code SensitivityModelCoefficientTest} reads the compiled
 * constant and fails if it is ever changed to 8.0.
 * =======================================================================
 */
public final class SensitivityModel {

    /**
     * The coefficient that converts {@code (0.6*s + 0.2)^3} into degrees per mouse count.
     *
     * <p>THE VALUE IS 1.2. It is the product of vanilla's {@code 8.0F} in the input
     * handler and vanilla's {@code 0.15F} in the entity turn method. See the trap note
     * at the top of this class.</p>
     */
    public static final double DEG_PER_COUNT_COEFFICIENT = 1.2;

    /** Centimetres of mouse travel for a full 360 degree yaw turn, expressed per inch. Unit: cm. */
    public static final double CM_PER_360_FROM_INCHES = 914.4;

    /** Centimetres in one inch. Unit: cm. */
    public static final double CM_PER_INCH = 2.54;

    /** The vanilla slider floor ("0%"). Unit: dimensionless slider ratio. */
    public static final double VANILLA_SENS_MIN = 0.0;

    /** The vanilla slider top ("100%"). Unit: dimensionless slider ratio. */
    public static final double VANILLA_SENS_MAX = 1.0;

    /** Lowest vertical FOV accepted as sane. Unit: degrees. */
    public static final int MIN_FOV_VERTICAL_DEG = 30;

    /** Highest vertical FOV accepted as sane. Unit: degrees. */
    public static final int MAX_FOV_VERTICAL_DEG = 110;

    /** The slider value that produced vanilla's shipped default {@code cmPer360} at 1000 DPI. Unit: ratio. */
    public static final double VANILLA_DEFAULT_SENS_RATIO = 0.5;

    /** The slider value a recommended DPI aims for, so the target is not pinned at the ceiling. Unit: ratio. */
    public static final double RECOMMENDED_SLIDER_RATIO = 0.72;

    private static final double DEGREES_PER_FULL_TURN = 360.0;
    private static final double DEGREES_PER_QUARTER_TURN = 90.0;

    /**
     * Round-off allowance on a slider-bound comparison, and nothing else.
     *
     * <p>{@code s} is recovered from {@code cmPer360} through a divide, a cube root and
     * two affine steps, so the exact slider endpoints do not survive it bit-for-bit: the
     * Marlow point {@code cmPer360 = 0.744140625} at 2000 DPI comes back as
     * {@code 1.0000000000000002}, two ULP above the top of the slider.</p>
     *
     * <p>Vanilla's slider range [0,1] INCLUDES both ends: {@code s = 0.0} and
     * {@code s = 1.0} are both reachable, well-documented settings. A predicate that
     * reads an exact endpoint as "out of range" is therefore wrong, not strict. This
     * tolerance is ~1e9 times wider than the observed round-off and ~1e7 times narrower
     * than the smallest difference the UI can display, so it can only ever absorb
     * floating-point noise and never a genuinely out-of-range value.</p>
     */
    private static final double SLIDER_BOUND_TOLERANCE = 1e-9;

    private final double cmPer360;
    private final int mouseDpi;
    private final int fovVerticalDeg;
    private final FovRelativeMode fovRelativeMode;

    private SensitivityModel(
            double cmPer360, int mouseDpi, int fovVerticalDeg, FovRelativeMode fovRelativeMode) {
        this.cmPer360 = cmPer360;
        this.mouseDpi = mouseDpi;
        this.fovVerticalDeg = fovVerticalDeg;
        this.fovRelativeMode = fovRelativeMode;
    }

    // =========================================================================
    // Static canonical math - contracts.md C6.0, verbatim
    // =========================================================================

    /**
     * Degrees of yaw per raw mouse count, from the vanilla slider value {@code s}.
     *
     * <p>{@code degPerCount(s) = 1.2 * (0.6*s + 0.2)^3}. {@code s} is {@code options.txt}'s
     * {@code mouseSensitivity}, the 0-100% slider, in [0,1].</p>
     *
     * <p>THE COEFFICIENT IS 1.2. IT IS NOT 8.0. See the trap note at the top of this class.</p>
     *
     * @param sensRatio the vanilla slider value, in [0,1]
     * @return degrees turned per raw mouse count
     * @throws XsozContractException if {@code sensRatio} is not a finite number in [0,1]
     */
    public static double degPerCount(double sensRatio) {
        requireSensRatio(sensRatio);
        double scaled = 0.6 * sensRatio + 0.2;
        return DEG_PER_COUNT_COEFFICIENT * scaled * scaled * scaled;
    }

    /**
     * Degrees of yaw per inch of mouse travel.
     *
     * @param sensRatio the vanilla slider value, in [0,1]
     * @param mouseDpi  counts per inch of the physical mouse
     * @return degrees turned per inch of travel
     * @throws XsozContractException if {@code sensRatio} or {@code mouseDpi} is invalid
     */
    public static double degPerInch(double sensRatio, int mouseDpi) {
        requireMouseDpi(mouseDpi);
        return degPerCount(sensRatio) * mouseDpi;
    }

    /**
     * Centimetres of mouse travel for a full 360 degree yaw turn.
     *
     * <p>{@code cmPer360(s, dpi) = 914.4 / (degPerCount(s) * dpi)}. The 914.4 is
     * {@code 360 deg / (k deg/count) = counts}, {@code / dpi = inches},
     * {@code * 2.54 = cm}.</p>
     *
     * @param sensRatio the vanilla slider value, in [0,1]
     * @param mouseDpi  counts per inch of the physical mouse
     * @return centimetres per 360 degrees
     * @throws XsozContractException if {@code sensRatio} or {@code mouseDpi} is invalid
     */
    public static double cmPer360(double sensRatio, int mouseDpi) {
        return cmPer360FromDegPerCount(degPerCount(sensRatio), mouseDpi);
    }

    /**
     * Centimetres per 360 degrees from a raw degrees-per-count coefficient.
     *
     * @param degPerCountValue degrees turned per raw mouse count
     * @return centimetres per 360 degrees
     * @throws XsozContractException if the coefficient is zero, negative or not finite
     */
    public static double cmPer360FromDegPerCount(double degPerCountValue) {
        if (!isFinite(degPerCountValue) || degPerCountValue <= 0.0) {
            throw new XsozContractException(
                    "degPerCount must be a finite positive number; was " + degPerCountValue
                            + ". A non-positive coefficient has no finite cm/360.");
        }
        return CM_PER_360_FROM_INCHES / degPerCountValue;
    }

    /**
     * Centimetres per 360 degrees at a given DPI, from a raw degrees-per-count coefficient.
     *
     * @param degPerCountValue degrees turned per raw mouse count
     * @param mouseDpi         counts per inch of the physical mouse
     * @return centimetres per 360 degrees
     * @throws XsozContractException if the coefficient or the DPI is invalid
     */
    public static double cmPer360FromDegPerCount(double degPerCountValue, int mouseDpi) {
        requireMouseDpi(mouseDpi);
        return cmPer360FromDegPerCount(degPerCountValue) / mouseDpi;
    }

    /**
     * Inverts {@link #cmPer360FromDegPerCount(double, int)} to a raw coefficient.
     *
     * @param cmPer360 centimetres per 360 degrees, must be finite and positive
     * @param mouseDpi counts per inch of the physical mouse
     * @return degrees turned per raw mouse count
     * @throws XsozContractException if the target or the DPI is invalid
     */
    public static double degPerCountFromCmPer360(double cmPer360, int mouseDpi) {
        requireMouseDpi(mouseDpi);
        requireCmPer360(cmPer360);
        return CM_PER_360_FROM_INCHES / (cmPer360 * mouseDpi);
    }

    /**
     * The vanilla slider value that produces a raw degrees-per-count coefficient.
     *
     * <p>Exact algebraic inverse of {@link #degPerCount(double)}:
     * {@code s = (cbrt(k / 1.2) - 0.2) / 0.6}. The returned value is NOT clamped: a
     * caller asking for a coefficient the vanilla slider cannot produce must be able to
     * see {@code 1.1693}, not a silent {@code 1.0}.</p>
     *
     * @param degPerCountValue degrees turned per raw mouse count, finite and positive
     * @return the required slider value, dimensionless and possibly outside [0,1]
     * @throws XsozContractException if the coefficient is not finite and positive
     */
    public static double sFromDegPerCount(double degPerCountValue) {
        if (!isFinite(degPerCountValue) || degPerCountValue <= 0.0) {
            throw new XsozContractException(
                    "degPerCount must be a finite positive number; was " + degPerCountValue
                            + ". It cannot be inverted to a vanilla slider value.");
        }
        return (Math.cbrt(degPerCountValue / DEG_PER_COUNT_COEFFICIENT) - 0.2) / 0.6;
    }

    /**
     * The vanilla slider value that produces a target {@code cmPer360} at a DPI.
     *
     * @param cmPer360 centimetres per 360 degrees, finite and positive
     * @param mouseDpi counts per inch of the physical mouse
     * @return the required slider value, dimensionless and possibly outside [0,1]
     * @throws XsozContractException if the target or the DPI is invalid
     */
    public static double sFromCmPer360(double cmPer360, int mouseDpi) {
        return sFromDegPerCount(degPerCountFromCmPer360(cmPer360, mouseDpi));
    }

    /**
     * Contract-named alias of {@link #cmPer360(double, int)}.
     *
     * @param sensRatio the vanilla slider value, in [0,1]
     * @param mouseDpi  counts per inch of the physical mouse
     * @return centimetres per 360 degrees
     * @throws XsozContractException if {@code sensRatio} or {@code mouseDpi} is invalid
     */
    public static double cmPer360FromS(double sensRatio, int mouseDpi) {
        return cmPer360(sensRatio, mouseDpi);
    }

    /**
     * The {@code SCREEN_SPEED} FOV transform: the factor applied to
     * {@code degPerCount} when the vertical FOV changes (contracts.md C6.4).
     *
     * <p>{@code k_new = k_old * tan(fovNew/2) / tan(fovOld/2)}.</p>
     *
     * <p><strong>The direction is the tell and is not optional.</strong> Widening the
     * FOV makes the image move slower for a given {@code degPerCount}, so the factor
     * is GREATER THAN ONE and sensitivity is RAISED. The widely repeated <em>linear</em>
     * FOV rule is a bug, not a variant: it is a Quake/Source-era heuristic from
     * engines that couple FOV to input, and it is 43-46% wrong even inside its own
     * premise.</p>
     *
     * @param fovOldVerticalDeg the current vertical FOV, in degrees, within [30,110]
     * @param fovNewVerticalDeg the requested vertical FOV, in degrees, within [30,110]
     * @return the multiplicative factor on {@code degPerCount}; greater than 1 when widening
     * @throws XsozContractException if either FOV is outside [30,110]
     */
    public static double fovRelativeDegPerCountFactor(int fovOldVerticalDeg, int fovNewVerticalDeg) {
        requireFovVerticalDeg(fovOldVerticalDeg);
        requireFovVerticalDeg(fovNewVerticalDeg);
        double tanHalfOld = Math.tan(Math.toRadians(fovOldVerticalDeg / 2.0));
        double tanHalfNew = Math.tan(Math.toRadians(fovNewVerticalDeg / 2.0));
        return tanHalfNew / tanHalfOld;
    }

    // =========================================================================
    // Instance surface - contracts.md C6.2
    // =========================================================================

    /**
     * Builds a model from the canonical stored unit.
     *
     * <p>Any positive {@code cmPer360} is accepted, including values vanilla's slider
     * cannot produce. Out-of-vanilla values are a STATE, not an exception: ask
     * {@link #isInsideVanillaSlider()} and {@link #rangeHint()}.</p>
     *
     * @param cmPer360        centimetres per 360 degrees, finite and positive
     * @param mouseDpi        counts per inch, strictly positive
     * @param fovVerticalDeg  vertical FOV in degrees, within [30,110]
     * @param fovRelativeMode how the model responds to a FOV change, non-null
     * @return a validated model
     * @throws NullPointerException      if {@code fovRelativeMode} is null
     * @throws XsozContractException     if any numeric argument is out of range
     */
    public static SensitivityModel ofCmPer360(
            double cmPer360, int mouseDpi, int fovVerticalDeg, FovRelativeMode fovRelativeMode) {
        requireMouseDpi(mouseDpi);
        requireCmPer360(cmPer360);
        requireFovVerticalDeg(fovVerticalDeg);
        Objects.requireNonNull(fovRelativeMode, "fovRelativeMode must not be null");
        return new SensitivityModel(cmPer360, mouseDpi, fovVerticalDeg, fovRelativeMode);
    }

    /**
     * Builds a model from a vanilla slider value.
     *
     * @param sensRatio       the vanilla slider value, strictly within [0,1]
     * @param mouseDpi        counts per inch, strictly positive
     * @param fovVerticalDeg  vertical FOV in degrees, within [30,110]
     * @param fovRelativeMode how the model responds to a FOV change, non-null
     * @return a validated model whose canonical field is the derived {@code cmPer360}
     * @throws SensitivityOutOfRangeException if {@code sensRatio} is outside [0,1]
     * @throws XsozContractException          if the DPI or the FOV is out of range
     */
    public static SensitivityModel ofVanillaSlider(
            double sensRatio, int mouseDpi, int fovVerticalDeg, FovRelativeMode fovRelativeMode) {
        requireMouseDpi(mouseDpi);
        requireFovVerticalDeg(fovVerticalDeg);
        Objects.requireNonNull(fovRelativeMode, "fovRelativeMode must not be null");
        if (!isFinite(sensRatio) || sensRatio < VANILLA_SENS_MIN || sensRatio > VANILLA_SENS_MAX) {
            throw new SensitivityOutOfRangeException(
                    sensRatio,
                    mouseDpi,
                    "Vanilla's mouseSensitivity slider only covers 0.0 to 1.0; "
                            + sensRatio + " is outside it. Vanilla cannot be asked for this value.");
        }
        return new SensitivityModel(
                cmPer360(sensRatio, mouseDpi), mouseDpi, fovVerticalDeg, fovRelativeMode);
    }

    /**
     * The canonical stored quantity: centimetres of travel for a full 360 degree turn.
     *
     * @return centimetres per 360 degrees
     */
    public double cmPer360() {
        return cmPer360;
    }

    /**
     * The player's mouse resolution.
     *
     * @return counts per inch
     */
    public int mouseDpi() {
        return mouseDpi;
    }

    /**
     * The vertical field of view this model was built at.
     *
     * @return degrees
     */
    public int fovVerticalDeg() {
        return fovVerticalDeg;
    }

    /**
     * How this model responds to a FOV change.
     *
     * @return the non-null mode
     */
    public FovRelativeMode fovRelativeMode() {
        return fovRelativeMode;
    }

    /**
     * The derived vanilla slider value. Never persisted (C6.1).
     *
     * @return the slider value, dimensionless; may be outside [0,1] for an out-of-range model
     */
    public double s() {
        return sFromDegPerCount(degPerCount());
    }

    /**
     * The derived degrees-per-count coefficient at the current DPI.
     *
     * @return degrees turned per raw mouse count
     */
    public double degPerCount() {
        return degPerCountFromCmPer360(cmPer360, mouseDpi);
    }

    /**
     * The derived degrees turned per inch of travel.
     *
     * @return degrees per inch
     */
    public double degPerInch() {
        return degPerCount() * mouseDpi;
    }

    /**
     * Mouse travel for a 90 degree turn, in millimetres.
     *
     * @return millimetres per 90 degrees
     */
    public double mmPer90Deg() {
        double cmPer90 = cmPer360 * (DEGREES_PER_QUARTER_TURN / DEGREES_PER_FULL_TURN);
        return cmPer90 * 10.0;
    }

    /**
     * Whether the vanilla slider can produce this model's sensitivity at its DPI.
     *
     * <p>{@code 0.0 <= s() <= 1.0}, INCLUSIVE at both ends, up to
     * {@link #SLIDER_BOUND_TOLERANCE} of double round-off. Both endpoints are reachable
     * vanilla settings, and a model built at one of them - the Marlow point
     * {@code s = 1.0} at 2000 DPI is exactly the top - must not read as out of
     * range.</p>
     *
     * @return true when {@code 0.0 <= s() <= 1.0}
     */
    public boolean isInsideVanillaSlider() {
        return isInsideSliderRange(s());
    }

    /**
     * The remedy for a sensitivity vanilla cannot reach, if one is needed.
     *
     * @return empty when the model is inside the vanilla slider
     */
    public Optional<RangeHint> rangeHint() {
        if (isInsideVanillaSlider()) {
            return Optional.empty();
        }
        double fastestCmPer360 = cmPer360(VANILLA_SENS_MAX, mouseDpi);
        double slowestCmPer360 = cmPer360(VANILLA_SENS_MIN, mouseDpi);
        int minDpi = minDpiForCmPer360(cmPer360);
        int recommendedDpi = minDpiForCmPer360(cmPer360, RECOMMENDED_SLIDER_RATIO);
        return Optional.of(new RangeHint(fastestCmPer360, slowestCmPer360, minDpi, recommendedDpi));
    }

    /**
     * Returns a model at a new vertical FOV, applying this model's FOV-relative mode.
     *
     * <p>{@code PHYSICAL} leaves the sensitivity untouched, which is what vanilla does.
     * {@code SCREEN_SPEED} applies the {@code tan} transform and throws rather than
     * clamping when the result lands outside the slider.</p>
     *
     * @param newFovVerticalDeg the requested vertical FOV, within [30,110]
     * @return a new model, or an equal-valued one under {@code PHYSICAL}
     * @throws SensitivityOutOfRangeException if {@code SCREEN_SPEED} pushes the slider value out of [0,1]
     * @throws XsozContractException          if the FOV is out of range
     */
    public SensitivityModel withFov(int newFovVerticalDeg) {
        requireFovVerticalDeg(newFovVerticalDeg);
        if (fovRelativeMode == FovRelativeMode.PHYSICAL || newFovVerticalDeg == fovVerticalDeg) {
            return new SensitivityModel(cmPer360, mouseDpi, newFovVerticalDeg, fovRelativeMode);
        }
        double factor = fovRelativeDegPerCountFactor(fovVerticalDeg, newFovVerticalDeg);
        double newDegPerCount = degPerCount() * factor;
        double newSensRatio = sFromDegPerCount(newDegPerCount);
        if (!isInsideSliderRange(newSensRatio)) {
            throw new SensitivityOutOfRangeException(
                    newSensRatio,
                    mouseDpi,
                    minDpiForFovAdjustedTarget(cmPer360, factor),
                    String.format(
                            Locale.ROOT,
                            "SCREEN_SPEED would need a vanilla slider value of %.4f at %d DPI when moving "
                                    + "FOV %d to %d. Vanilla's slider only reaches 1.0. Raise your DPI to at "
                                    + "least %d, or use PHYSICAL mode, which is what the game actually does.",
                            newSensRatio, mouseDpi, fovVerticalDeg, newFovVerticalDeg,
                            minDpiForFovAdjustedTarget(cmPer360, factor)));
        }
        return new SensitivityModel(
                cmPer360FromDegPerCount(newDegPerCount, mouseDpi),
                mouseDpi,
                newFovVerticalDeg,
                fovRelativeMode);
    }

    /**
     * Returns a model at a different {@code cmPer360}, holding DPI and FOV.
     *
     * @param newCmPer360 centimetres per 360 degrees, finite and positive
     * @return a new model
     * @throws XsozContractException if {@code newCmPer360} is not finite and positive
     */
    public SensitivityModel withCmPer360(double newCmPer360) {
        requireCmPer360(newCmPer360);
        return new SensitivityModel(newCmPer360, mouseDpi, fovVerticalDeg, fovRelativeMode);
    }

    /**
     * Returns a model at a different DPI with {@code cmPer360} held CONSTANT (C6.3).
     *
     * <p>The feel being calibrated to is the {@code cmPer360}. Moving it as a side
     * effect of a DPI change would silently move the player's aim.</p>
     *
     * @param newDpi counts per inch, strictly positive
     * @return a new model with the same {@code cmPer360} and a recomputed slider value
     * @throws XsozContractException if {@code newDpi} is not strictly positive
     */
    public SensitivityModel withMouseDpi(int newDpi) {
        requireMouseDpi(newDpi);
        return new SensitivityModel(cmPer360, newDpi, fovVerticalDeg, fovRelativeMode);
    }

    /**
     * Returns the model reachable at the lowest DPI that keeps a target inside the slider
     * at no more than {@code sensRatioCeiling}.
     *
     * @param targetCmPer360  centimetres per 360 degrees to reach, finite and positive
     * @param sensRatioCeiling the highest slider value the player will accept, in (0,1]
     * @return a model holding {@code targetCmPer360} with a DPI at or above the minimum
     * @throws XsozContractException if any argument is out of range
     */
    public SensitivityModel withDpiForTargetS(double targetCmPer360, double sensRatioCeiling) {
        requireCmPer360(targetCmPer360);
        if (!isFinite(sensRatioCeiling) || sensRatioCeiling <= 0.0 || sensRatioCeiling > VANILLA_SENS_MAX) {
            throw new XsozContractException(
                    "sensRatioCeiling must be a finite value in (0,1]; was " + sensRatioCeiling);
        }
        int dpi = minDpiForCmPer360(targetCmPer360, sensRatioCeiling);
        return new SensitivityModel(targetCmPer360, dpi, fovVerticalDeg, fovRelativeMode);
    }

    /**
     * The lowest DPI at which a target {@code cmPer360} is reachable inside the slider.
     *
     * @param targetCmPer360 centimetres per 360 degrees, finite and positive
     * @return counts per inch
     * @throws XsozContractException if {@code targetCmPer360} is not finite and positive
     */
    public static int minDpiForCmPer360(double targetCmPer360) {
        requireCmPer360(targetCmPer360);
        // 914.4 / 0.6144 = 1490.625; dividing by cm/360 gives the DPI at which the
        // required slider value is exactly 1.0, i.e. the lowest DPI that reaches it at all.
        return (int) Math.ceil(CM_PER_360_FROM_INCHES / (DEG_PER_COUNT_COEFFICIENT
                * Math.pow(0.6 * VANILLA_SENS_MAX + 0.2, 3) * targetCmPer360));
    }

    /**
     * The lowest DPI at which a target is reachable at no more than a slider ceiling.
     *
     * @param targetCmPer360   centimetres per 360 degrees, finite and positive
     * @param sensRatioCeiling the highest acceptable slider value, in (0,1]
     * @return counts per inch
     * @throws XsozContractException if any argument is out of range
     */
    public static int minDpiForCmPer360(double targetCmPer360, double sensRatioCeiling) {
        requireCmPer360(targetCmPer360);
        if (!isFinite(sensRatioCeiling) || sensRatioCeiling <= 0.0 || sensRatioCeiling > VANILLA_SENS_MAX) {
            throw new XsozContractException(
                    "sensRatioCeiling must be a finite value in (0,1]; was " + sensRatioCeiling);
        }
        double degPerCountAtCeiling = DEG_PER_COUNT_COEFFICIENT
                * Math.pow(0.6 * sensRatioCeiling + 0.2, 3);
        return (int) Math.ceil(CM_PER_360_FROM_INCHES / (degPerCountAtCeiling * targetCmPer360));
    }

    /**
     * The lowest DPI at which a target survives a {@code SCREEN_SPEED} FOV change.
     *
     * <p>Rising DPI lowers the degrees-per-count a given {@code cmPer360} needs, which
     * frees headroom under the slider ceiling. That is the direction that helps: a
     * faster target always needs MORE dpi, never less (C6.1).</p>
     *
     * @param targetCmPer360 centimetres per 360 degrees held constant, finite and positive
     * @param factor         the FOV-relative factor applied to {@code degPerCount}, positive
     * @return counts per inch
     * @throws XsozContractException if {@code targetCmPer360} is not finite and positive
     */
    public static int minDpiForFovAdjustedTarget(double targetCmPer360, double factor) {
        requireCmPer360(targetCmPer360);
        if (!isFinite(factor) || factor <= 0.0) {
            throw new XsozContractException(
                    "FOV-relative factor must be a finite positive number; was " + factor);
        }
        double degPerCountAtSliderTop = DEG_PER_COUNT_COEFFICIENT
                * Math.pow(0.6 * VANILLA_SENS_MAX + 0.2, 3);
        return (int) Math.ceil(
                factor * CM_PER_360_FROM_INCHES / (degPerCountAtSliderTop * targetCmPer360));
    }

    // =========================================================================
    // Validation - contracts.md 0.4: violations throw, they are never clamped
    // =========================================================================

    private static void requireSensRatio(double sensRatio) {
        if (!isFinite(sensRatio) || sensRatio < VANILLA_SENS_MIN || sensRatio > VANILLA_SENS_MAX) {
            throw new XsozContractException(
                    "sensRatio must be a finite value within [" + VANILLA_SENS_MIN + ","
                            + VANILLA_SENS_MAX + "]; was " + sensRatio
                            + ". Vanilla's mouseSensitivity slider covers exactly that range, and a "
                            + "value outside it has no vanilla meaning.");
        }
    }

    private static void requireCmPer360(double targetCmPer360) {
        if (!isFinite(targetCmPer360) || targetCmPer360 <= 0.0) {
            throw new XsozContractException(
                    "cmPer360 must be a finite positive number; was " + targetCmPer360
                            + ". Zero or negative travel per full turn has no physical meaning.");
        }
    }

    private static void requireMouseDpi(int mouseDpi) {
        if (mouseDpi <= 0) {
            throw new XsozContractException(
                    "mouseDpi must be strictly positive counts per inch; was " + mouseDpi
                            + ". A mouse with a non-positive resolution does not exist, and dividing "
                            + "by it would silently produce an infinite cm/360.");
        }
    }

    private static void requireFovVerticalDeg(int fovVerticalDeg) {
        if (fovVerticalDeg < MIN_FOV_VERTICAL_DEG || fovVerticalDeg > MAX_FOV_VERTICAL_DEG) {
            throw new XsozContractException(
                    "fovVerticalDeg must be within [" + MIN_FOV_VERTICAL_DEG + ","
                            + MAX_FOV_VERTICAL_DEG + "] degrees; was " + fovVerticalDeg
                            + ". Vanilla's own FOV slider stops at 110, and below 30 the projection "
                            + "is degenerate.");
        }
    }

    private static boolean isInsideSliderRange(double sensRatio) {
        return sensRatio >= VANILLA_SENS_MIN - SLIDER_BOUND_TOLERANCE
                && sensRatio <= VANILLA_SENS_MAX + SLIDER_BOUND_TOLERANCE;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
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
        if (!(o instanceof SensitivityModel)) {
            return false;
        }
        SensitivityModel other = (SensitivityModel) o;
        return mouseDpi == other.mouseDpi
                && fovVerticalDeg == other.fovVerticalDeg
                && Double.compare(cmPer360, other.cmPer360) == 0
                && fovRelativeMode == other.fovRelativeMode;
    }

    /**
     * Hash consistent with {@link #equals(Object)}.
     *
     * @return a hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(cmPer360, mouseDpi, fovVerticalDeg, fovRelativeMode);
    }

    /**
     * Diagnostics only. Names every field with its unit, per contracts.md 0.6.
     *
     * @return a debug string
     */
    @Override
    public String toString() {
        return String.format(
                Locale.ROOT,
                "SensitivityModel[cmPer360=%.9f, mouseDpi=%d, fovVerticalDeg=%d, mode=%s, s=%.6f]",
                cmPer360, mouseDpi, fovVerticalDeg, fovRelativeMode, s());
    }
}
