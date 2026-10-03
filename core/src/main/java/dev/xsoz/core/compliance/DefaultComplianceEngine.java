package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The eight-step {@link ComplianceEngine} (contracts.md C7.5).
 *
 * <p><strong>The step order is a contract.</strong> "No other order is permitted, and a coder
 * who reorders two of these steps has changed the product." The single ordering that matters
 * most is step 3 before step 4: a module that cannot run on this version is refused
 * <em>before</em> the context is consulted, so a 1.8.9 log never says "unavailable in this
 * context" when the truth is "unavailable on this version".</p>
 *
 * <p><strong>Unknown module id means DENY, not ALLOW.</strong> The posture is default-deny:
 * an absent row is not an absence of refusal, and no branch of this method has a fall-through
 * that reaches "allowed".</p>
 *
 * <p><strong>Staleness is surfaced, never silently upgraded.</strong> When the resolved
 * profile's snapshot predates the running build, the reason line says so on every decision
 * and {@link #profileStaleness()} reports it; nothing here re-resolves to a newer profile by
 * itself.</p>
 */
public final class DefaultComplianceEngine implements ComplianceEngine {

    private final ComplianceProfileSet bundled;
    private final FeatureGate features;
    private final ComplianceSubjectIndex subjects;
    private final CapabilityView capabilities;
    private final LocalDate clientReleaseDate;
    private final AcknowledgementView acknowledgements;

    private ComplianceProfile resolvedProfile;
    private HostBinding matchedBinding;

    private final Map<String, OptOutState> optOuts = new LinkedHashMap<String, OptOutState>();
    private final Map<String, StaffClearance> clearances = new LinkedHashMap<String, StaffClearance>();
    private final List<SessionLogEntry> sessionLog = new ArrayList<SessionLogEntry>();

    private DefaultComplianceEngine(Builder builder) {
        this.bundled = builder.bundled;
        this.features = builder.features;
        this.subjects = builder.subjects;
        this.capabilities = builder.capabilities;
        this.clientReleaseDate = builder.clientReleaseDate;
        this.acknowledgements = builder.acknowledgements;
        this.resolvedProfile = bundled.defaultDeny();
    }

    /** @return a builder with the bundled profiles, an empty feature gate and no capabilities */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Decision authorize(String moduleId, Context context) {
        if (context == null) {
            throw new XsozContractException(
                    "authorize(moduleId, context) requires a context. contracts.md C7.6: Context is a "
                            + "single-value enum resolved by a total function, not an optional.");
        }
        String id = moduleId == null ? "" : moduleId;
        if (id.trim().isEmpty()) {
            return refuseUnknownModule(
                    "A blank feature id names nothing, and nothing is allowed. Default-deny.");
        }

        // ---- step 1: Tier C is never compiled, so it can never be authorized ----------------
        if (features.isTierC(id)) {
            return new Decision(false, ComplianceTier.C, Verdict.DENIED,
                    withStaleness("Tier C: " + id + " is never compiled. It is absent from the binary, "
                            + "not merely disabled - " + describeProhibitingCitation(id)),
                    citationsForTierC(id),
                    Collections.<String>emptySet(),
                    DecisionSource.TIER_C_ABSENT);
        }

        // ---- step 2: not compiled -------------------------------------------------------------
        ComplianceSubject subject = subjects.byId(id).orElse(null);
        if (!features.isCompiled(id) || subject == null) {
            return new Decision(false, tierOrDefault(id, subject), Verdict.DENIED,
                    withStaleness("Not compiled: no module with id \"" + id + "\" is in this build, so "
                            + "there is no row and no evidence for it. Default-deny."),
                    ComplianceCitations.strictestKnown(),
                    Collections.<String>emptySet(),
                    DecisionSource.PROFILE_DENIED);
        }

        // ---- step 3: capabilities, BEFORE the context ----------------------------------------
        Set<String> missing = missingCapabilities(subject);
        if (!missing.isEmpty()) {
            return new Decision(false, subject.complianceTier(), Verdict.UNVERIFIED,
                    withStaleness("This build cannot run " + id + ": missing capability " + missing
                            + ". The feature is fine; this version cannot run it."),
                    Collections.singletonList(ComplianceCitations.STRICTEST_KNOWN),
                    Collections.<String>emptySet(),
                    DecisionSource.CAPABILITY_MISSING);
        }

        // ---- step 4: context ------------------------------------------------------------------
        ComplianceRow contextRow = resolvedProfile.rowFor(id);
        if (context == Context.WORLD_ABSENT || !subject.allowedContexts().contains(context)) {
            return new Decision(false, subject.complianceTier(), verdictOf(contextRow),
                    withStaleness("Not permitted in " + context + ": inert here, not denied on the "
                            + "network. " + summaryOf(contextRow)),
                    citationsOf(contextRow),
                    Collections.<String>emptySet(),
                    DecisionSource.CONTEXT_NOT_ALLOWED);
        }

        // ---- step 5: an absent row is a refusal ------------------------------------------------
        if (contextRow == null) {
            return new Decision(false, subject.complianceTier(), Verdict.UNVERIFIED,
                    withStaleness("Profile \"" + resolvedProfile.id() + "\" has no row for " + id
                            + ", so it is excluded rather than allowed by omission."),
                    ComplianceCitations.strictestKnown(),
                    Collections.<String>emptySet(),
                    DecisionSource.PROFILE_DENIED);
        }

        // ---- step 6: a standing prohibition is terminal -----------------------------------------
        if (contextRow.isHardDenied()) {
            return new Decision(false, contextRow.tier(), Verdict.DENIED,
                    withStaleness("Standing prohibition: " + contextRow.prohibitionsAlways()
                            + ". No profile and no user edit can unlock this. " + contextRow.summary()),
                    contextRow.citations(),
                    Collections.<String>emptySet(),
                    DecisionSource.PROFILE_DENIED);
        }

        // ---- the add-only allow list: a (host, featureId) grant resolves a Tier B row ----------
        // The grant is a USER grant, so the SERVER still has the last word: a registered
        // opt-out outranks it, exactly as it outranks an ALLOWED row.
        if (contextRow.tier() == ComplianceTier.B
                && contextRow.verdict() != Verdict.ALLOWED
                && resolvedProfile.allowList().contains(id)) {
            if (isOptedOut(id)) {
                return new Decision(false, contextRow.tier(), contextRow.verdict(),
                        withStaleness("Granted by an allow-list entry on profile \""
                                + resolvedProfile.id() + "\", but a server asked us to disable it: "
                                + optOutState(id) + ". The server has the last word."),
                        contextRow.citations(),
                        Collections.<String>emptySet(),
                        DecisionSource.OPT_OUT);
            }
            return new Decision(true, contextRow.tier(), Verdict.ALLOWED,
                    withStaleness("Granted by an explicit (host, featureId) allow-list entry on "
                            + resolvedProfile.id() + ". Still honouring xsoz:opt_out."),
                    contextRow.citations(),
                    Collections.singleton("allow-list:" + id),
                    DecisionSource.STAFF_CLEARANCE);
        }

        // ---- step 7: the verdict switch --------------------------------------------------------
        Decision decision = applyVerdict(id, subject, contextRow);
        return withStalenessIn(decision);
    }

    private Decision applyVerdict(String id, ComplianceSubject subject, ComplianceRow row) {
        boolean optedOut = isOptedOut(id);
        switch (row.verdict()) {
            case ALLOWED:
                if (optedOut) {
                    return new Decision(false, row.tier(), Verdict.ALLOWED,
                            "Allowed by profile \"" + resolvedProfile.id() + "\", but a server asked us "
                                    + "to disable it: " + optOutState(id) + ".",
                            row.citations(), Collections.<String>emptySet(), DecisionSource.OPT_OUT);
                }
                return new Decision(true, row.tier(), Verdict.ALLOWED,
                        "Allowed by profile \"" + resolvedProfile.id() + "\". " + row.summary(),
                        row.citations(), Collections.<String>emptySet(),
                        DecisionSource.PROFILE_ALLOWED);

            case CONDITIONAL:
                if (optedOut) {
                    return new Decision(false, row.tier(), Verdict.CONDITIONAL,
                            "Conditional, but a server asked us to disable it: " + optOutState(id) + ".",
                            row.citations(), Collections.<String>emptySet(), DecisionSource.OPT_OUT);
                }
                Set<String> unsatisfied = unsatisfiedConditions(row);
                if (unsatisfied.isEmpty()) {
                    return new Decision(true, row.tier(), Verdict.CONDITIONAL,
                            "Conditional and the conditions hold: " + row.conditions() + ". "
                                    + row.summary(),
                            row.citations(), row.conditions(), DecisionSource.PROFILE_CONDITIONAL);
                }
                return new Decision(false, row.tier(), Verdict.CONDITIONAL,
                        "Condition not satisfied: " + unsatisfied + ". " + row.summary(),
                        row.citations(), Collections.<String>emptySet(),
                        DecisionSource.PROFILE_CONDITIONAL);

            case CONTESTED:
                if (clearances.containsKey(id)) {
                    return new Decision(true, row.tier(), Verdict.CONTESTED,
                            "CONTESTED across sources and cleared by " + clearances.get(id)
                                    + ". Both verdicts stay on record.",
                            concat(row.citations(), clearances.get(id).citation()),
                            Collections.<String>emptySet(), DecisionSource.STAFF_CLEARANCE);
                }
                if (optedOut) {
                    return new Decision(false, row.tier(), Verdict.CONTESTED,
                            "CONTESTED, and a server asked us to disable it: " + optOutState(id) + ".",
                            row.citations(), Collections.<String>emptySet(), DecisionSource.OPT_OUT);
                }
                return new Decision(false, row.tier(), Verdict.CONTESTED,
                        "CONTESTED across sources; both verdicts are shown and this is not resolved "
                                + "silently. Staff clearance is required. " + row.summary(),
                        row.citations(), Collections.<String>emptySet(),
                        DecisionSource.PROFILE_CONTESTED);

            case OWN_RISK:
                if (optedOut) {
                    return new Decision(false, row.tier(), Verdict.OWN_RISK,
                            "At your own risk, and a server asked us to disable it: "
                                    + optOutState(id) + ".",
                            row.citations(), Collections.<String>emptySet(), DecisionSource.OPT_OUT);
                }
                if (acknowledgements.isAcknowledged(id)) {
                    return new Decision(true, row.tier(), Verdict.OWN_RISK,
                            "At your own risk, and you acknowledged it on this join. " + row.summary(),
                            row.citations(), Collections.singleton("acknowledged-on-this-join"),
                            DecisionSource.PROFILE_OWN_RISK);
                }
                return new Decision(false, row.tier(), Verdict.OWN_RISK,
                        "At your own risk: requires an explicit acknowledgement on this join. "
                                + row.summary(),
                        row.citations(), Collections.<String>emptySet(),
                        DecisionSource.PROFILE_OWN_RISK);

            case DENIED:
                return new Decision(false, row.tier(), Verdict.DENIED,
                        "Denied by profile \"" + resolvedProfile.id() + "\": " + row.summary(),
                        row.citations(), Collections.<String>emptySet(),
                        DecisionSource.PROFILE_DENIED);

            case UNVERIFIED:
            default:
                return new Decision(false, row.tier(), Verdict.UNVERIFIED,
                        "No source was found for " + id + ", so it is excluded until confirmed. "
                                + row.summary(),
                        row.citations(), Collections.<String>emptySet(),
                        DecisionSource.PROFILE_UNVERIFIED);
        }
    }

    // ---- resolution --------------------------------------------------------------------------

    /**
     * Re-resolves the profile for a host. C2.4 row 7 and C7.9: a host change is a full
     * re-resolve followed by a re-evaluation of every module, and the resolved profile is
     * the only thing cached.
     *
     * @param serverAddress exactly what the player typed, or {@code null}/empty for no server
     * @return the resolved profile
     */
    public ComplianceProfile resolveFor(String serverAddress) {
        if (serverAddress == null || serverAddress.trim().isEmpty()) {
            resolvedProfile = bundled.defaultDeny();
            matchedBinding = null;
            return resolvedProfile;
        }
        ComplianceProfileSet.Resolution resolution = bundled.resolve(serverAddress);
        resolvedProfile = resolution.profile();
        matchedBinding = resolution.binding().orElse(null);
        return resolvedProfile;
    }

    /**
     * Staleness of the resolved profile against the running build.
     *
     * @return CURRENT, STALE, or UNKNOWN when there is no date to compare. A stale profile is
     *         <strong>surfaced on every decision's reason line</strong> and never upgraded.
     */
    public ComplianceProfile.Staleness profileStaleness() {
        return ComplianceProfile.Staleness.of(resolvedProfile, clientReleaseDate);
    }

    /** @return the running build's release date, which staleness is measured against */
    public LocalDate clientReleaseDate() {
        return clientReleaseDate;
    }

    @Override
    public ComplianceProfile resolvedProfile() {
        return resolvedProfile;
    }

    @Override
    public Optional<HostBinding> matchedBinding() {
        return Optional.ofNullable(matchedBinding);
    }

    @Override
    public ComplianceProfileSet bundledProfiles() {
        return bundled;
    }

    @Override
    public LocalDate rulesSnapshotDate() {
        return bundled.rulesSnapshotDate();
    }

    @Override
    public FeatureGate featureGate() {
        return features;
    }

    @Override
    public boolean isCompiled(String moduleId) {
        return features.isCompiled(moduleId);
    }

    // ---- opt-out -----------------------------------------------------------------------------

    @Override
    public void registerOptOut(Set<String> disabledFeatureIds, String sourceChannel,
                               LocalDateTime receivedAt) {
        if (sourceChannel == null || sourceChannel.trim().isEmpty()) {
            throw new XsozContractException(
                    "An opt-out must name the channel it arrived on. contracts.md C7.10: a client that "
                            + "cannot say where a server's instruction came from cannot honour it "
                            + "honestly.");
        }
        if (receivedAt == null) {
            throw new XsozContractException("An opt-out must carry the time it arrived.");
        }
        if (disabledFeatureIds == null) {
            throw new XsozContractException("An opt-out must carry its feature-id list, possibly empty.");
        }
        for (String featureId : disabledFeatureIds) {
            if (featureId == null || featureId.trim().isEmpty()) {
                // C7.10 rule 5: an unknown feature id is ignored and logged, never read as "all".
                continue;
            }
            optOuts.put(featureId, OptOutState.optedOut(sourceChannel, receivedAt));
        }
    }

    /**
     * Records the blanket instruction, which is what a {@code count == 0} payload means.
     *
     * @param sourceChannel where the instruction arrived
     * @param receivedAt    when it arrived
     */
    public void registerOptOutEverything(String sourceChannel, LocalDateTime receivedAt) {
        for (String featureId : features.compiledIds()) {
            if (features.tierOf(featureId).orElse(ComplianceTier.A) == ComplianceTier.B) {
                optOuts.put(featureId, OptOutState.optedOutEverything(sourceChannel, receivedAt));
            }
        }
    }

    @Override
    public boolean isOptedOut(String moduleId) {
        OptOutState state = optOuts.get(moduleId);
        return state != null && state.optedOut();
    }

    @Override
    public Set<String> effectiveDisabled() {
        Set<String> disabled = new LinkedHashSet<String>(optOuts.keySet());
        for (String featureId : features.compiledIds()) {
            if (!authorize(featureId, Context.SERVER).allow()) {
                disabled.add(featureId);
            }
        }
        return Collections.unmodifiableSet(disabled);
    }

    @Override
    public OptOutState optOutState(String moduleId) {
        OptOutState state = moduleId == null ? null : optOuts.get(moduleId);
        return state == null ? OptOutState.notOptedOut() : state;
    }

    // ---- clearance ---------------------------------------------------------------------------

    @Override
    public Optional<StaffClearance> clearanceFor(String moduleId) {
        return Optional.ofNullable(moduleId == null ? null : clearances.get(moduleId));
    }

    @Override
    public void recordClearance(StaffClearance clearance) {
        if (clearance == null) {
            throw new XsozContractException("A clearance record must not be null.");
        }
        clearances.put(clearance.featureId(), clearance);
    }

    // ---- audit -------------------------------------------------------------------------------

    @Override
    public List<Decision> allDecisions() {
        List<String> ids = new ArrayList<String>(features.compiledIds());
        Collections.sort(ids);
        List<Decision> decisions = new ArrayList<Decision>(ids.size());
        for (String id : ids) {
            decisions.add(authorize(id, Context.SERVER));
        }
        return Collections.unmodifiableList(decisions);
    }

    @Override
    public void appendSessionLog(SessionLogEntry entry) {
        if (entry == null) {
            throw new XsozContractException("A session log entry must not be null.");
        }
        sessionLog.add(entry);
    }

    @Override
    public List<SessionLogEntry> sessionLog() {
        return SessionLogEntry.immutable(sessionLog);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private Set<String> missingCapabilities(ComplianceSubject subject) {
        Set<String> missing = new LinkedHashSet<String>();
        for (String required : subject.requiredCapabilityNames()) {
            if (required == null) {
                continue;
            }
            if (!capabilities.has(required)) {
                missing.add(required);
            }
        }
        return missing;
    }

    private Set<String> unsatisfiedConditions(ComplianceRow row) {
        Set<String> unsatisfied = new LinkedHashSet<String>();
        for (String condition : row.conditions()) {
            if (!capabilities.conditionHolds(condition)) {
                unsatisfied.add(condition);
            }
        }
        return unsatisfied;
    }

    private ComplianceTier tierOrDefault(String moduleId, ComplianceSubject subject) {
        if (subject != null) {
            return subject.complianceTier();
        }
        return features.tierOf(moduleId).orElse(ComplianceTier.A);
    }

    private Decision refuseUnknownModule(String why) {
        return new Decision(false, ComplianceTier.A, Verdict.DENIED, withStaleness(why),
                ComplianceCitations.strictestKnown(), Collections.<String>emptySet(),
                DecisionSource.PROFILE_DENIED);
    }

    private List<Citation> citationsForTierC(String id) {
        ComplianceRow row = resolvedProfile.rowFor(id);
        return row == null ? ComplianceCitations.strictestKnown() : row.citations();
    }

    private String describeProhibitingCitation(String id) {
        ComplianceRow row = resolvedProfile.rowFor(id);
        return row == null
                ? "no per-feature evidence was found, so the strictest studied clause is cited."
                : "Evidence: " + row.citations() + ".";
    }

    private List<Citation> citationsOf(ComplianceRow row) {
        return row == null ? ComplianceCitations.strictestKnown() : row.citations();
    }

    private Verdict verdictOf(ComplianceRow row) {
        return row == null ? Verdict.UNVERIFIED : row.verdict();
    }

    private String summaryOf(ComplianceRow row) {
        return row == null ? "No row in the resolved profile." : row.summary();
    }

    private static List<Citation> concat(List<Citation> a, Citation b) {
        List<Citation> out = new ArrayList<Citation>(a);
        out.add(b);
        return out;
    }

    /**
     * A decision whose reason does not yet carry the staleness note.
     *
     * @param reason the reason line
     * @return the same line with a staleness suffix when the snapshot is stale
     */
    private String withStaleness(String reason) {
        ComplianceProfile.Staleness staleness = profileStaleness();
        if (staleness == ComplianceProfile.Staleness.STALE) {
            return reason + " [STALE: this profile's evidence is dated " + resolvedProfile.retrievedOn()
                    + ", older than this build (" + clientReleaseDate
                    + "). Surfaced, not upgraded.]";
        }
        if (staleness == ComplianceProfile.Staleness.UNKNOWN) {
            return reason + " [STALENESS UNKNOWN: no release date to compare the snapshot against.]";
        }
        return reason;
    }

    /**
     * The same suffix applied to a decision that has already been built. Every decision the
     * engine produces therefore carries the note, whichever branch built it.
     *
     * @param decision the decision to annotate
     * @return an equal decision with the note in its reason
     */
    private Decision withStalenessIn(Decision decision) {
        String reason = withStaleness(decision.reason());
        if (reason.equals(decision.reason())) {
            return decision;
        }
        return new Decision(decision.allow(), decision.tier(), decision.verdict(), reason,
                decision.citations(), decision.appliedConditions(), decision.source());
    }

    /** Where the engine looks up a compiled module's compliance-relevant properties. */
    public interface ComplianceSubjectIndex {

        /**
         * @param moduleId the feature id
         * @return the registered module's compliance view, or empty if the id is not
         *         registered
         */
        Optional<ComplianceSubject> byId(String moduleId);
    }

    /** What this build can do, for C7.5 step 3 and for CONDITIONAL conditions. */
    public interface CapabilityView {

        /**
         * @param capabilityName a C1.2 capability enum name
         * @return {@code true} if the capability is present on this pole
         */
        boolean has(String capabilityName);

        /**
         * @param conditionId a {@code ComplianceRow} condition id
         * @return {@code true} if the condition currently holds
         */
        boolean conditionHolds(String conditionId);
    }

    /** Whether an OWN_RISK feature has been acknowledged on this join. */
    public interface AcknowledgementView {

        /**
         * @param featureId the feature id
         * @return {@code true} if the player acknowledged it for this connection
         */
        boolean isAcknowledged(String featureId);
    }

    /** Assembles a {@link DefaultComplianceEngine}. */
    public static final class Builder {

        private ComplianceProfileSet bundled = ComplianceProfileSet.bundled();
        private FeatureGate features = FeatureGate.empty();
        private ComplianceSubjectIndex subjects = new ComplianceSubjectIndex() {
            @Override
            public Optional<ComplianceSubject> byId(String moduleId) {
                return Optional.empty();
            }
        };
        private CapabilityView capabilities = new CapabilityView() {
            @Override
            public boolean has(String capabilityName) {
                return false;
            }

            @Override
            public boolean conditionHolds(String conditionId) {
                return false;
            }
        };
        private LocalDate clientReleaseDate = ComplianceCitations.SOURCES_RETRIEVED_ON;
        private AcknowledgementView acknowledgements = new AcknowledgementView() {
            @Override
            public boolean isAcknowledged(String featureId) {
                return false;
            }
        };

        private Builder() {
        }

        /** @param bundled the profile set to resolve against; must not be {@code null} */
        public Builder bundledProfiles(ComplianceProfileSet bundled) {
            if (bundled == null) {
                throw new XsozContractException("ComplianceProfileSet must not be null.");
            }
            this.bundled = bundled;
            return this;
        }

        /** @param features what is compiled; must not be {@code null} */
        public Builder featureGate(FeatureGate features) {
            if (features == null) {
                throw new XsozContractException("FeatureGate must not be null.");
            }
            this.features = features;
            return this;
        }

        /** @param subjects the registered-module lookup; must not be {@code null} */
        public Builder subjectIndex(ComplianceSubjectIndex subjects) {
            if (subjects == null) {
                throw new XsozContractException("ComplianceSubjectIndex must not be null.");
            }
            this.subjects = subjects;
            return this;
        }

        /** @param capabilities what this build can do; must not be {@code null} */
        public Builder capabilityView(CapabilityView capabilities) {
            if (capabilities == null) {
                throw new XsozContractException("CapabilityView must not be null.");
            }
            this.capabilities = capabilities;
            return this;
        }

        /**
         * @param clientReleaseDate the running build's date; a profile older than this is
         *                          STALE, which is surfaced and never upgraded
         */
        public Builder clientReleaseDate(LocalDate clientReleaseDate) {
            this.clientReleaseDate = clientReleaseDate;
            return this;
        }

        /** @param acknowledgements the OWN_RISK acknowledgement state; must not be {@code null} */
        public Builder acknowledgementView(AcknowledgementView acknowledgements) {
            if (acknowledgements == null) {
                throw new XsozContractException("AcknowledgementView must not be null.");
            }
            this.acknowledgements = acknowledgements;
            return this;
        }

        /** @return the assembled engine, resolved to {@code default-deny} */
        public DefaultComplianceEngine build() {
            return new DefaultComplianceEngine(this);
        }
    }
}
