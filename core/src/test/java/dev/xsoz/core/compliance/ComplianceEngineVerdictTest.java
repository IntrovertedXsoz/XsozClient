package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract C7.5 steps 5-7, C7.10, C7.12 - the verdict table, the opt-out and the clearance.
 *
 * <p>Split out of {@link ComplianceEngineTest} only for file size; it uses the same fixtures
 * so the two cannot disagree about what a module is.</p>
 */
class ComplianceEngineVerdictTest {

    private static final LocalDate ON = ComplianceCitations.SOURCES_RETRIEVED_ON;

    // ---- step 5: an absent row is a refusal, not an absence of refusal ------------------------

    @Test
    @DisplayName("step 5 - a compiled feature with no row is UNVERIFIED, never allowed by omission")
    void absentRowIsUnverified() {
        Map<String, ComplianceProfile> profiles = new LinkedHashMap<String, ComplianceProfile>();
        profiles.put(ComplianceProfile.DEFAULT_DENY_ID, new ComplianceProfile(
                ComplianceProfile.DEFAULT_DENY_ID, "Empty table",
                ruleset("derivation"), true, Collections.<String, ComplianceRow>emptyMap(),
                Collections.<String>emptySet(), ON));
        DefaultComplianceEngine engine = DefaultComplianceEngine.builder()
                .bundledProfiles(ComplianceProfileSet.of(profiles,
                        Collections.<HostBinding>emptyList()))
                .featureGate(FeatureGate.of(
                        Collections.singletonMap("hud.totem-counter", ComplianceTier.A),
                        Collections.<String>emptySet()))
                .subjectIndex(ComplianceEngineTest.index(
                        Collections.singletonMap("hud.totem-counter",
                                ComplianceEngineTest.TestSubjects.totemCounter())))
                .capabilityView(ComplianceEngineTest.capabilities(
                        Collections.singleton("TOTEM_OF_UNDYING"), new LinkedHashSet<String>()))
                .build();
        Decision decision = engine.authorize("hud.totem-counter", Context.SERVER);
        assertFalse(decision.allow());
        assertEquals(Verdict.UNVERIFIED, decision.verdict());
        assertEquals(DecisionSource.PROFILE_DENIED, decision.source());
    }

    // ---- step 6: the standing prohibitions -----------------------------------------------------

    @Test
    @DisplayName("step 6 - a row carrying a standing prohibition is a hard deny")
    void standingProhibitionIsTerminal() {
        Map<String, ComplianceRow> rows = new LinkedHashMap<String, ComplianceRow>();
        rows.put("visual.log-filter", new ComplianceRow(Verdict.ALLOWED, ComplianceTier.A,
                Collections.singletonList(ComplianceCitations.LOG_FILTER_OWN_RISK),
                Collections.<String>emptySet(),
                Collections.singleton(GlobalProhibition.LOG_FILTER.id()),
                "a log filter is never permitted"));
        Map<String, ComplianceProfile> profiles = new LinkedHashMap<String, ComplianceProfile>();
        profiles.put(ComplianceProfile.DEFAULT_DENY_ID, new ComplianceProfile(
                ComplianceProfile.DEFAULT_DENY_ID, "Prohibition table", ruleset("pvphq-sheet"), true,
                rows, Collections.<String>emptySet(), ON));
        DefaultComplianceEngine engine = DefaultComplianceEngine.builder()
                .bundledProfiles(ComplianceProfileSet.of(profiles,
                        Collections.<HostBinding>emptyList()))
                .featureGate(FeatureGate.of(
                        Collections.singletonMap("visual.log-filter", ComplianceTier.A),
                        Collections.<String>emptySet()))
                .subjectIndex(ComplianceEngineTest.index(Collections.singletonMap(
                        "visual.log-filter", anySubject("visual.log-filter", ComplianceTier.A))))
                .build();
        Decision decision = engine.authorize("visual.log-filter", Context.SERVER);
        assertFalse(decision.allow());
        assertEquals(Verdict.DENIED, decision.verdict());
        assertEquals(DecisionSource.PROFILE_DENIED, decision.source());
        assertTrue(decision.reason().contains("log-filter"), decision.reason());
        assertTrue(decision.reason().contains("No profile and no user edit can unlock"),
                decision.reason());
    }

    // ---- step 7: the verdict switch ------------------------------------------------------------

    @Test
    @DisplayName("step 7 - ALLOWED allows and names the profile that said so")
    void allowedRowAllows() {
        Decision decision = engineWithDefaultDeny().authorize("hud.totem-counter", Context.SERVER);
        assertTrue(decision.allow());
        assertEquals(Verdict.ALLOWED, decision.verdict());
        assertEquals(ComplianceTier.A, decision.tier());
        assertEquals(DecisionSource.PROFILE_ALLOWED, decision.source());
        assertTrue(decision.reason().contains("default-deny"), decision.reason());
    }

    @Test
    @DisplayName("step 7 - DENIED is terminal: allow = false, source = PROFILE_DENIED")
    void deniedTierBRowIsTerminal() {
        Decision decision = engineWithDefaultDeny().authorize("latency.crystal-release", Context.SERVER);
        assertFalse(decision.allow());
        assertEquals(Verdict.DENIED, decision.verdict());
        assertEquals(DecisionSource.PROFILE_DENIED, decision.source());
        assertTrue(decision.reason().contains("Denied by profile"), decision.reason());
    }

    @Test
    @DisplayName("step 7 - CONDITIONAL allows only while every stated condition holds")
    void conditionalVerdict() {
        final Set<String> conditions = new LinkedHashSet<String>();
        DefaultComplianceEngine engine = engineFor("perf.render-distance", ComplianceTier.A,
                new ComplianceRow(Verdict.CONDITIONAL, ComplianceTier.A,
                        Collections.singletonList(ComplianceCitations.PERF_ALLOWED),
                        new LinkedHashSet<String>(Arrays.asList(
                                "render-distance-checks-server-value",
                                "simulation-distance-checks-server-value")),
                        Collections.<String>emptySet(),
                        "clamped to the server's own join-packet values"),
                conditions, new LinkedHashSet<String>());

        Decision refused = engine.authorize("perf.render-distance", Context.SERVER);
        assertFalse(refused.allow());
        assertEquals(DecisionSource.PROFILE_CONDITIONAL, refused.source());
        assertTrue(refused.reason().contains("not satisfied"), refused.reason());
        assertTrue(refused.appliedConditions().isEmpty());

        conditions.add("render-distance-checks-server-value");
        assertFalse(engine.authorize("perf.render-distance", Context.SERVER).allow(),
                "one of two conditions is not enough");
        conditions.add("simulation-distance-checks-server-value");
        Decision allowed = engine.authorize("perf.render-distance", Context.SERVER);
        assertTrue(allowed.allow());
        assertEquals(2, allowed.appliedConditions().size());
    }

    @Test
    @DisplayName("step 7 - OWN_RISK needs an acknowledgement on THIS join")
    void ownRiskNeedsAnAcknowledgement() {
        final Set<String> acknowledged = new LinkedHashSet<String>();
        DefaultComplianceEngine engine = engineFor("visual.mouse-debounce", ComplianceTier.A,
                new ComplianceRow(Verdict.OWN_RISK, ComplianceTier.A,
                        Collections.singletonList(ComplianceCitations.PERF_ALLOWED),
                        Collections.<String>emptySet(), Collections.<String>emptySet(),
                        "conflicting rules between networks"),
                new LinkedHashSet<String>(), acknowledged);

        Decision refused = engine.authorize("visual.mouse-debounce", Context.SERVER);
        assertFalse(refused.allow());
        assertEquals(DecisionSource.PROFILE_OWN_RISK, refused.source());
        assertTrue(refused.reason().contains("acknowledgement on this join"), refused.reason());

        acknowledged.add("visual.mouse-debounce");
        assertTrue(engine.authorize("visual.mouse-debounce", Context.SERVER).allow());
    }

    @Test
    @DisplayName("step 7 - UNVERIFIED excludes; it is not permissive")
    void unverifiedIsTerminal() {
        DefaultComplianceEngine engine = engineFor("diag.some-thing", ComplianceTier.A,
                new ComplianceRow(Verdict.UNVERIFIED, ComplianceTier.A,
                        Collections.singletonList(ComplianceCitations.STRICTEST_KNOWN),
                        Collections.<String>emptySet(), Collections.<String>emptySet(),
                        "no source found"),
                new LinkedHashSet<String>(), new LinkedHashSet<String>());
        Decision decision = engine.authorize("diag.some-thing", Context.SERVER);
        assertFalse(decision.allow());
        assertEquals(DecisionSource.PROFILE_UNVERIFIED, decision.source());
    }

    // ---- C7.10: the server opt-out -------------------------------------------------------------

    @Test
    @DisplayName("a server opt-out wins over an ALLOWED row, and the verdict stays ALLOWED")
    void serverOptOutBeatsAnAllowedRow() {
        DefaultComplianceEngine engine = engineWithDefaultDeny();
        assertTrue(engine.authorize("hud.totem-counter", Context.SERVER).allow());
        engine.registerOptOut(Collections.singleton("hud.totem-counter"), "xsoz:opt_out",
                LocalDateTime.of(2026, 9, 29, 12, 0));
        Decision decision = engine.authorize("hud.totem-counter", Context.SERVER);
        assertFalse(decision.allow());
        assertEquals(Verdict.ALLOWED, decision.verdict());
        assertEquals(DecisionSource.OPT_OUT, decision.source());
        assertTrue(decision.reason().contains("xsoz:opt_out"), decision.reason());
        assertTrue(engine.isOptedOut("hud.totem-counter"));
    }

    @Test
    @DisplayName("a server opt-out also beats an allow-list grant: the server has the last word")
    void optOutBeatsAnAllowListGrant() {
        ComplianceProfileSet bundled = ComplianceProfileSet.bundled();
        ComplianceProfile granted = bundled.byId("pvphq").withAllowGrant("latency.crystal-release");
        Map<String, ComplianceProfile> profiles = new LinkedHashMap<String, ComplianceProfile>();
        for (ComplianceProfile profile : bundled.all()) {
            profiles.put(profile.id(), "pvphq".equals(profile.id()) ? granted : profile);
        }
        DefaultComplianceEngine engine = DefaultComplianceEngine.builder()
                .bundledProfiles(ComplianceProfileSet.of(profiles, bundled.bindings()))
                .featureGate(FeatureGate.of(
                        Collections.singletonMap("latency.crystal-release", ComplianceTier.B),
                        Collections.<String>emptySet()))
                .subjectIndex(ComplianceEngineTest.index(Collections.singletonMap(
                        "latency.crystal-release",
                        ComplianceEngineTest.TestSubjects.crystalRelease())))
                .capabilityView(ComplianceEngineTest.capabilities(
                        Collections.singleton("END_CRYSTAL"), new LinkedHashSet<String>()))
                .build();
        engine.resolveFor("pvphq.com");
        assertTrue(engine.authorize("latency.crystal-release", Context.SERVER).allow());
        engine.registerOptOut(Collections.singleton("latency.crystal-release"), "xsoz:opt_out",
                LocalDateTime.of(2026, 9, 29, 12, 0));
        Decision decision = engine.authorize("latency.crystal-release", Context.SERVER);
        assertFalse(decision.allow());
        assertEquals(DecisionSource.OPT_OUT, decision.source());
    }

    @Test
    @DisplayName("the blanket opt-out covers every optimising feature and nothing else")
    void blanketOptOut() {
        DefaultComplianceEngine engine = engineWithDefaultDeny();
        engine.registerOptOutEverything("xsoz:opt_out", LocalDateTime.of(2026, 9, 29, 12, 0));
        assertTrue(engine.isOptedOut("latency.crystal-release"));
        assertFalse(engine.isOptedOut("hud.totem-counter"),
                "a blanket opt-out covers optimising features, not Tier A");
        assertTrue(engine.optOutState("latency.crystal-release")
                .appliesToEveryOptimisingFeature());
        assertFalse(engine.optOutState("hud.totem-counter").optedOut());
    }

    @Test
    @DisplayName("effectiveDisabled is profile-deny UNION server-opt-out")
    void effectiveDisabled() {
        DefaultComplianceEngine engine = engineWithDefaultDeny();
        Set<String> before = engine.effectiveDisabled();
        assertTrue(before.contains("latency.crystal-release"), "denied by the profile");
        assertFalse(before.contains("latency.consumable-optimizer"),
                "a Tier C id is not a compiled feature, so it is not in the disabled set");
        assertFalse(before.contains("hud.totem-counter"), "allowed by the profile");

        engine.registerOptOut(Collections.singleton("hud.totem-counter"), "xsoz:opt_out",
                LocalDateTime.of(2026, 9, 29, 12, 0));
        assertTrue(engine.effectiveDisabled().contains("hud.totem-counter"));
    }

    @Test
    @DisplayName("an opt-out without provenance is refused: the client must say where it came from")
    void optOutNeedsProvenance() {
        DefaultComplianceEngine engine = engineWithDefaultDeny();
        assertThrows(XsozContractException.class, () -> engine.registerOptOut(
                Collections.singleton("hud.totem-counter"), "   ", LocalDateTime.now()));
        assertThrows(XsozContractException.class, () -> engine.registerOptOut(
                Collections.singleton("hud.totem-counter"), "xsoz:opt_out", null));
    }

    @Test
    @DisplayName("an unknown feature id in an opt-out payload is ignored, never read as 'all'")
    void unknownOptOutIdIsIgnored() {
        DefaultComplianceEngine engine = engineWithDefaultDeny();
        engine.registerOptOut(new LinkedHashSet<String>(Arrays.asList("a-feature-we-do-not-have")),
                "xsoz:opt_out", LocalDateTime.of(2026, 9, 29, 12, 0));
        assertEquals(Collections.emptySet(), engine.effectiveDisabled()
                .stream().filter(id -> "hud.totem-counter".equals(id))
                .collect(java.util.stream.Collectors.toSet()));
        assertFalse(engine.isOptedOut("hud.totem-counter"),
                "one unknown id must never disable everything else");
    }

    // ---- C7.12: staff clearance ----------------------------------------------------------------

    @Test
    @DisplayName("a CONTESTED feature needs a clearance on record, and the record carries a citation")
    void staffClearance() {
        DefaultComplianceEngine engine = engineFor("hitreg.improved", ComplianceTier.B,
                new ComplianceRow(Verdict.CONTESTED, ComplianceTier.B,
                        Collections.singletonList(ComplianceCitations.STRICTEST_KNOWN),
                        Collections.<String>emptySet(), Collections.<String>emptySet(),
                        "Better Hitreg is CONTESTED across networks"),
                new LinkedHashSet<String>(), new LinkedHashSet<String>());

        Decision refused = engine.authorize("hitreg.improved", Context.SERVER);
        assertFalse(refused.allow());
        assertEquals(DecisionSource.PROFILE_CONTESTED, refused.source());
        assertTrue(refused.reason().contains("not resolved silently"), refused.reason());

        engine.recordClearance(new StaffClearance("hitreg.improved", "@PvPHQ-Admin", ON,
                ComplianceCitations.TOTEM_POP_COUNTER_ALLOWED, "ranked only"));
        Decision cleared = engine.authorize("hitreg.improved", Context.SERVER);
        assertTrue(cleared.allow());
        assertEquals(DecisionSource.STAFF_CLEARANCE, cleared.source());
        assertEquals("@PvPHQ-Admin",
                engine.clearanceFor("hitreg.improved").get().staffHandle());
        assertEquals(2, cleared.citations().size(),
                "a clearance carries the row's evidence AND the staff member's stated authority");
    }

    @Test
    @DisplayName("a clearance with no stated authority is refused: 'the staff said so' is not evidence")
    void clearanceNeedsACitation() {
        assertThrows(XsozContractException.class,
                () -> new StaffClearance("hitreg.improved", "@Admin", ON, null, ""));
        assertThrows(XsozContractException.class,
                () -> new StaffClearance("hitreg.improved", "  ", ON,
                        ComplianceCitations.STRICTEST_KNOWN, ""));
    }

    // ---- the session log ------------------------------------------------------------------------

    @Test
    @DisplayName("the session log is hash-chained, and any edit breaks verification")
    void sessionLogIsHashChained() {
        DefaultComplianceEngine engine = engineWithDefaultDeny();
        SessionLogEntry first = SessionLogEntry.append(null, 0L, 0L, "PROFILE_RESOLVED",
                "resolved default-deny, snapshot 2026-09-28");
        engine.appendSessionLog(first);
        engine.appendSessionLog(SessionLogEntry.append(first, 1L, 50L, "FEATURE_STATE",
                "hud.totem-counter enabled"));

        java.util.List<SessionLogEntry> log = engine.sessionLog();
        assertEquals(2, log.size());
        assertTrue(SessionLogEntry.verifyChain(log));

        java.util.List<SessionLogEntry> tampered = new java.util.ArrayList<SessionLogEntry>(log);
        tampered.set(0, SessionLogEntry.append(null, 0L, 0L, "PROFILE_RESOLVED", "edited after the fact"));
        assertFalse(SessionLogEntry.verifyChain(tampered),
                "an edited line must break the chain - a log that can be edited is not evidence");
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private DefaultComplianceEngine engineWithDefaultDeny() {
        Map<String, ComplianceSubject> subjects = new LinkedHashMap<String, ComplianceSubject>();
        subjects.put("hud.totem-counter", ComplianceEngineTest.TestSubjects.totemCounter());
        subjects.put("latency.crystal-release", ComplianceEngineTest.TestSubjects.crystalRelease());
        Map<String, ComplianceTier> compiled = new LinkedHashMap<String, ComplianceTier>();
        compiled.put("hud.totem-counter", ComplianceTier.A);
        compiled.put("latency.crystal-release", ComplianceTier.B);
        return DefaultComplianceEngine.builder()
                .featureGate(FeatureGate.of(compiled,
                        Collections.singleton("latency.consumable-optimizer")))
                .subjectIndex(ComplianceEngineTest.index(subjects))
                .capabilityView(ComplianceEngineTest.capabilities(
                        new LinkedHashSet<String>(Arrays.asList("TOTEM_OF_UNDYING", "END_CRYSTAL")),
                        new LinkedHashSet<String>()))
                .build();
    }

    private DefaultComplianceEngine engineFor(String featureId, ComplianceTier tier, ComplianceRow row,
                                              final Set<String> conditions,
                                              final Set<String> acknowledgements) {
        Map<String, ComplianceRow> rows = new LinkedHashMap<String, ComplianceRow>();
        rows.put(featureId, row);
        Map<String, ComplianceProfile> profiles = new LinkedHashMap<String, ComplianceProfile>();
        profiles.put(ComplianceProfile.DEFAULT_DENY_ID, new ComplianceProfile(
                ComplianceProfile.DEFAULT_DENY_ID, "Fixture", ruleset("derivation"), true, rows,
                Collections.<String>emptySet(), ON));
        return DefaultComplianceEngine.builder()
                .bundledProfiles(ComplianceProfileSet.of(profiles,
                        Collections.<HostBinding>emptyList()))
                .featureGate(FeatureGate.of(Collections.singletonMap(featureId, tier),
                        Collections.<String>emptySet()))
                .subjectIndex(ComplianceEngineTest.index(Collections.singletonMap(
                        featureId, anySubject(featureId, tier))))
                .capabilityView(ComplianceEngineTest.capabilities(
                        new LinkedHashSet<String>(), conditions))
                .acknowledgementView(ComplianceEngineTest.acknowledgement(acknowledgements))
                .build();
    }

    private static ComplianceSubject anySubject(final String moduleId, final ComplianceTier tier) {
        return new ComplianceSubject() {
            @Override
            public String moduleId() {
                return moduleId;
            }

            @Override
            public ComplianceTier complianceTier() {
                return tier;
            }

            @Override
            public Set<Context> allowedContexts() {
                return EnumSet.of(Context.WORLD, Context.SERVER, Context.SCREEN_OPEN);
            }

            @Override
            public Set<String> requiredCapabilityNames() {
                return Collections.<String>emptySet();
            }
        };
    }

    private static Set<String> ruleset(String id) {
        return new LinkedHashSet<String>(Collections.singletonList(id));
    }
}
