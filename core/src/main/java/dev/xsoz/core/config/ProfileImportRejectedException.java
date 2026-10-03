package dev.xsoz.core.config;

/**
 * Thrown when an import is refused, and <strong>nothing is changed</strong>
 * (contracts.md C5.5).
 *
 * <p>The seven ordered import steps are a transaction: a document that fails at step 3
 * has not created a profile, has not moved the default and has not switched the active
 * profile. Named in the contract.</p>
 */
public class ProfileImportRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Returned by {@link #step()} for a document that will not parse. */
    public static final int STEP_PARSE = 1;

    /** Returned by {@link #step()} for a wrong {@code format} or a too-new {@code formatVersion}. */
    public static final int STEP_FORMAT = 1;

    /** Returned by {@link #step()} for a profile whose embedded schema is newer than this build. */
    public static final int STEP_SCHEMA = 2;

    /** Returned by {@link #step()} for a setting, keybind or HUD element that fails validation. */
    public static final int STEP_VALIDATE = 3;

    /** Returned by {@link #step()} for a Tier C module key. */
    public static final int STEP_TIER_C = 4;

    /** Returned by {@link #step()} for a compliance profile id not present locally. */
    public static final int STEP_COMPLIANCE = 5;

    /** Returned by {@link #step()} for a document that parsed but carried no profile. */
    public static final int STEP_SHAPE = 6;

    private final int step;

    /**
     * @param step        which of C5.5's ordered checks refused it
     * @param explanation what was wrong and what the caller must do
     */
    public ProfileImportRejectedException(int step, String explanation) {
        super("Import refused at step " + step + ": " + explanation);
        this.step = step;
    }

    /** @return which of C5.5's ordered checks refused the document */
    public int step() {
        return step;
    }
}
