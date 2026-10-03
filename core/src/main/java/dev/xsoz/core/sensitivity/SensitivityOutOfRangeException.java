package dev.xsoz.core.sensitivity;

import dev.xsoz.core.XsozContractException;

/**
 * Thrown when a sensitivity request cannot be expressed inside vanilla's slider
 * (contracts.md C6.2, and the "hard limits" table in C6.1).
 *
 * <p>This is a value-shaped failure, not a bug: the caller is told the slider value
 * that <em>would</em> be required, and the explanation sentence the UI must show.
 * Nothing is ever clamped silently - clamping a sensitivity is how a player's aim
 * moves without anyone telling them.</p>
 */
public final class SensitivityOutOfRangeException extends XsozContractException {

    private static final long serialVersionUID = 1L;

    private final double requiredSensRatio;
    private final int mouseDpi;
    private final int minDpiForVanillaRange;
    private final String explanation;

    /**
     * Creates an exception that cannot offer a DPI remedy.
     *
     * @param requiredSensRatio the vanilla slider value the request would need, in [0,1]
     * @param mouseDpi          the DPI the request was made at
     * @param explanation       the sentence the UI shows the player
     */
    public SensitivityOutOfRangeException(
            double requiredSensRatio, int mouseDpi, String explanation) {
        this(requiredSensRatio, mouseDpi, Integer.MIN_VALUE, explanation);
    }

    /**
     * Creates an exception that carries a DPI remedy.
     *
     * @param requiredSensRatio     the vanilla slider value the request would need, in [0,1]
     * @param mouseDpi              the DPI the request was made at
     * @param minDpiForVanillaRange the DPI at which the target becomes reachable at all
     * @param explanation           the sentence the UI shows the player
     */
    public SensitivityOutOfRangeException(
            double requiredSensRatio, int mouseDpi, int minDpiForVanillaRange, String explanation) {
        super(explanation);
        this.requiredSensRatio = requiredSensRatio;
        this.mouseDpi = mouseDpi;
        this.minDpiForVanillaRange = minDpiForVanillaRange;
        this.explanation = explanation;
    }

    /**
     * The vanilla slider value the request would need, in the range [0,1].
     *
     * <p>Values outside [0,1] are legal here precisely because reporting "you would
     * need 1.1693, which vanilla cannot do" is the useful answer.</p>
     *
     * @return the required slider value, dimensionless ratio
     */
    public double requiredS() {
        return requiredSensRatio;
    }

    /**
     * The DPI the request was made at.
     *
     * @return counts per inch
     */
    public int mouseDpi() {
        return mouseDpi;
    }

    /**
     * The DPI at which the target becomes reachable inside the vanilla slider at all,
     * or {@link Integer#MIN_VALUE} when this exception carries no DPI remedy.
     *
     * @return counts per inch
     */
    public int minDpiForVanillaRange() {
        return minDpiForVanillaRange;
    }

    /**
     * The full sentence explaining the violation, suitable for direct display.
     *
     * @return a non-null, non-empty explanation
     */
    public String explanation() {
        return explanation;
    }
}
