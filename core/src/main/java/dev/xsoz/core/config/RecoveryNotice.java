package dev.xsoz.core.config;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * One line of the recovery log (contracts.md C5.4 step 5), and one row of the
 * mod-inventory screen's recovery notice.
 *
 * <p><strong>Evidence first: the bytes of an unreadable file are preserved before
 * anything is written over it</strong> (C5.4 step 2). This record names where they went,
 * because a rejected copy nobody can find is not evidence.</p>
 *
 * <p>Deeply immutable.</p>
 */
public final class RecoveryNotice {

    private final ConfigRecoveryOutcome outcome;
    private final String profileId;
    private final String detail;
    private final Instant atUtc;
    private final String evidencePath;

    private RecoveryNotice(ConfigRecoveryOutcome outcome, String profileId, String detail,
                           Instant atUtc, String evidencePath) {
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        this.profileId = profileId;
        this.detail = Objects.requireNonNull(detail, "detail must not be null");
        this.atUtc = Objects.requireNonNull(atUtc, "atUtc must not be null");
        this.evidencePath = evidencePath;
    }

    /**
     * @param outcome  what happened
     * @param profileId the profile it happened to, or {@code null} for a store-level event
     * @param detail   the one line explaining it, written for a player reading it aloud
     * @param atUtc    when it happened. Unit: ISO-8601 instant, UTC.
     * @return the notice
     */
    public static RecoveryNotice of(ConfigRecoveryOutcome outcome, String profileId, String detail,
                                    Instant atUtc) {
        return new RecoveryNotice(outcome, profileId, detail, atUtc, null);
    }

    /**
     * @param outcome      what happened
     * @param profileId    the profile it happened to
     * @param detail       the one line explaining it
     * @param atUtc        when it happened. Unit: ISO-8601 instant, UTC.
     * @param evidencePath where the unreadable bytes were preserved, or {@code null}
     * @return the notice
     */
    public static RecoveryNotice withEvidence(ConfigRecoveryOutcome outcome, String profileId,
                                              String detail, Instant atUtc, String evidencePath) {
        return new RecoveryNotice(outcome, profileId, detail, atUtc, evidencePath);
    }

    /** @return what happened */
    public ConfigRecoveryOutcome outcome() {
        return outcome;
    }

    /** @return the profile it happened to, or {@code null} for a store-level event */
    public String profileId() {
        return profileId;
    }

    /** @return the one line explaining it */
    public String detail() {
        return detail;
    }

    /** @return when it happened. Unit: ISO-8601 instant, UTC. */
    public Instant atUtc() {
        return atUtc;
    }

    /** @return where the unreadable bytes were preserved, or {@code null} */
    public String evidencePath() {
        return evidencePath;
    }

    /**
     * @return the one line appended to {@code config/recovery.log} and shown on the
     *         mod-inventory screen
     */
    public String logLine() {
        return String.format(Locale.ROOT, "%s %s %s%s", atUtc, outcome,
                profileId == null ? "-" : profileId,
                evidencePath == null ? " " + detail : " " + detail
                        + " Unreadable bytes preserved at " + evidencePath + ".");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RecoveryNotice)) {
            return false;
        }
        RecoveryNotice that = (RecoveryNotice) other;
        return outcome == that.outcome
                && Objects.equals(profileId, that.profileId)
                && detail.equals(that.detail)
                && atUtc.equals(that.atUtc)
                && Objects.equals(evidencePath, that.evidencePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(outcome, profileId, detail, atUtc, evidencePath);
    }

    @Override
    public String toString() {
        return logLine();
    }
}
