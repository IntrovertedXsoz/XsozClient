package dev.xsoz.core.config;

import dev.xsoz.core.setting.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of running the migration chain (contracts.md C4.5).
 *
 * <p><strong>A migration that throws is a FAILED result, not a propagating
 * exception.</strong> A hand-edited config is untrusted input, and a load path that dies
 * on untrusted input is a load path that will not start. The failure travels back as a
 * value, the caller routes it to the recovery path of C5.4, and the original bytes are
 * preserved (C5.4 step 2).</p>
 *
 * <p>Deeply immutable.</p>
 */
public final class MigrationResult {

    private final JsonObject output;
    private final List<String> appliedDescriptions;
    private final boolean failed;
    private final String failureReason;

    private MigrationResult(JsonObject output, List<String> appliedDescriptions,
                            boolean failed, String failureReason) {
        this.output = output;
        this.appliedDescriptions = Collections.unmodifiableList(
                new ArrayList<String>(appliedDescriptions));
        this.failed = failed;
        this.failureReason = failureReason;
    }

    /**
     * @param output               the migrated document
     * @param appliedDescriptions  one line per migration applied, in the order applied
     * @return a successful result
     */
    public static MigrationResult success(JsonObject output, List<String> appliedDescriptions) {
        if (output == null) {
            throw new IllegalArgumentException("A successful migration result needs its output.");
        }
        return new MigrationResult(output, appliedDescriptions, false, null);
    }

    /**
     * @param fromVersion    the version the chain started at
     * @param toVersion      the version it was asked to reach
     * @param failureReason  what went wrong
     * @return a failed result. {@code output()} is the last document the chain produced,
     *         or {@code null} when the first step failed.
     */
    public static MigrationResult failure(JsonObject lastOutput, int fromVersion, int toVersion,
                                          String failureReason) {
        String reason = "migration from schema " + fromVersion + " to " + toVersion + " failed: "
                + failureReason;
        List<String> none = Collections.emptyList();
        return new MigrationResult(lastOutput, none, true, reason);
    }

    /**
     * @return the document at {@link MigrationRegistry#currentVersion()}; {@code null}
     *         only on a failure
     */
    public JsonObject output() {
        return output;
    }

    /** @return the one-line description of each migration applied, in order */
    public List<String> appliedDescriptions() {
        return appliedDescriptions;
    }

    /** @return whether the chain failed */
    public boolean failed() {
        return failed;
    }

    /**
     * @return why the chain failed, or {@code null} when it did not. Present exactly when
     *         {@link #failed()} is true.
     */
    public String failureReason() {
        return failureReason;
    }

    @Override
    public String toString() {
        return failed
                ? "MigrationResult[FAILED " + failureReason + "]"
                : "MigrationResult[applied=" + appliedDescriptions + "]";
    }
}
