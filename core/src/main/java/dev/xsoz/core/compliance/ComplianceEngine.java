package dev.xsoz.core.compliance;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The gate (contracts.md C7.5). The exact signature set, frozen.
 *
 * <p><strong>{@code allow == false} is the whole answer.</strong> {@link #authorize} never
 * throws for a missing feature: a feature nobody has researched is a user-facing state and
 * user-facing states are values (contracts.md 0.4).</p>
 *
 * <p><strong>Decisions are computed, never persisted</strong> ({@code [CONTRACT DECISION] D5}).
 * The only thing cached is the resolved {@link ComplianceProfile}, keyed by (host, bundle
 * date); {@code authorize} is called fresh on every gate consultation.</p>
 */
public interface ComplianceEngine {

    /**
     * THE GATE.
     *
     * @param moduleId the feature id, which is the module id (C7.7, one namespace)
     * @param context  the single-value context
     * @return the decision; {@code allow == false} refuses the feature in this context
     */
    Decision authorize(String moduleId, Context context);

    // ---- resolution state -----------------------------------------------------------------------

    /** @return the currently resolved profile, never {@code null} */
    ComplianceProfile resolvedProfile();

    /** @return the host binding that produced {@link #resolvedProfile()}, if any */
    Optional<HostBinding> matchedBinding();

    /** @return the bundled profile set this engine resolves against */
    ComplianceProfileSet bundledProfiles();

    /** @return the date the bundled rules snapshot was taken */
    LocalDate rulesSnapshotDate();

    // ---- feature identity -----------------------------------------------------------------------

    /** @return what is compiled into this build and at what tier */
    FeatureGate featureGate();

    /**
     * @param moduleId the feature id
     * @return {@code true} if the feature exists in this binary
     */
    boolean isCompiled(String moduleId);

    // ---- server opt-out (C7.10) -----------------------------------------------------------------

    /**
     * Records a server's instruction. A payload naming every optimising feature is expressed
     * by including each id, or by an empty id set with {@code appliesToEveryOptimisingFeature}
     * handled by the caller; an unknown id is ignored and logged, never read as "all".
     *
     * @param disabledFeatureIds the feature ids to disable
     * @param sourceChannel      where the instruction arrived
     * @param receivedAt         when it arrived
     */
    void registerOptOut(Set<String> disabledFeatureIds, String sourceChannel, LocalDateTime receivedAt);

    /**
     * @param moduleId the feature id
     * @return {@code true} if a server has asked us to disable it
     */
    boolean isOptedOut(String moduleId);

    /**
     * @return profile-deny UNION server-opt-out UNION acknowledged, for the readiness screen
     */
    Set<String> effectiveDisabled();

    /**
     * @param moduleId the feature id
     * @return the opt-out state, never {@code null}
     */
    OptOutState optOutState(String moduleId);

    // ---- staff clearance (CONTESTED resolution) ------------------------------------------------

    /**
     * @param moduleId the feature id
     * @return the clearance on record, if any
     */
    Optional<StaffClearance> clearanceFor(String moduleId);

    /**
     * @param clearance the clearance to record; records who cleared it, when, and the citation
     */
    void recordClearance(StaffClearance clearance);

    // ---- audit ----------------------------------------------------------------------------------

    /** @return one {@link Decision} per compiled feature, for the inventory screen */
    List<Decision> allDecisions();

    /**
     * @param entry the entry to append; the log is hash-chained and append-only
     */
    void appendSessionLog(SessionLogEntry entry);

    /** @return the session log, oldest first; exposed for the exporter and its tests */
    List<SessionLogEntry> sessionLog();
}
