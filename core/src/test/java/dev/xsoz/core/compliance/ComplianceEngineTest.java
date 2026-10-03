package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract C7.5, C7.6, C7.7 - the gate.
 *
 * <p><strong>The step order is a contract</strong>, and these tests walk it in the order the
 * contract states it, because "a coder who reorders two of these steps has changed the
 * product".</p>
 *
 * <p>Shared fixtures for the verdict cases live in {@link ComplianceEngineVerdictTest}'s
 * sibling {@link TestSubjects}, so the two files cannot disagree about what a module is.</p>
 */
class ComplianceEngineTest {

    /** The date the bundled sources were retrieved. */
    protected static final LocalDate ON = ComplianceCitations.SOURCES_RETRIEVED_ON;

    protected final Map<String, ComplianceSubject> subjects = new LinkedHashMap<String, ComplianceSubject>();
    protected final Set<String> capabilities = new LinkedHashSet<String>();
    protected final Set<String> satisfiedConditions = new LinkedHashSet<String>();
    protected final Set<String> acknowledged = new LinkedHashSet<String>();

    @BeforeEach
    void setUp() {
        subjects.put("hud.totem-counter", TestSubjects.totemCounter());
        subjects.put("latency.crystal-release", TestSubjects.crystalRelease());
        subjects.put("coach.gate-overlay", TestSubjects.coachingGate());
        capabilities.add("TOTEM_OF_UNDYING");
        capabilities.add("END_CRYSTAL");
    }

    /** The standard fixture: all three modules compiled, full capability set, no grants. */
    protected DefaultComplianceEngine engine() {
        return engine(ON);
    }

    /**
     * @param clientReleaseDate the running build's date, for the staleness assertions
     * @return an engine over the bundled profiles with this fixture's modules compiled
     */
    protected DefaultComplianceEngine engine(LocalDate clientReleaseDate) {
        Map<String, ComplianceTier> compiled = new LinkedHashMap<String, ComplianceTier>();
        for (ComplianceSubject subject : subjects.values()) {
            compiled.put(subject.moduleId(), subject.complianceTier());
        }
        return DefaultComplianceEngine.builder()
                .featureGate(FeatureGate.of(compiled,
                        Collections.singleton("latency.consumable-optimizer")))
                .subjectIndex(index(subjects))
                .capabilityView(capabilities(capabilities, satisfiedConditions))
                .acknowledgementView(acknowledgement(acknowledged))
                .clientReleaseDate(clientReleaseDate)
                .build();
    }

    // ---- step 1: Tier C is never compiled -----------------------------------------------------

    @Test
    @DisplayName("step 1 - a Tier C id is denied with TIER_C_ABSENT and its own citation")
    void tierCIsAbsent() {
        Decision decision = engine().authorize("latency.consumable-optimizer", Context.SERVER);
        assertFalse(decision.allow());
        assertEquals(ComplianceTier.C, decision.tier());
        assertEquals(Verdict.DENIED, decision.verdict());
        assertEquals(DecisionSource.TIER_C_ABSENT, decision.source());
        assertTrue(decision.reason().contains("never compiled"), decision.reason());
        assertEquals(ComplianceCitations.GRIM_FIGHTING_PREDICTION_DETECTION,
                decision.citations().get(0));
    }

    @Test
    @DisplayName("a Tier C id is not compiled, so the gate cannot report it as available")
    void tierCIsNotCompiled() {
        DefaultComplianceEngine engine = engine();
        assertTrue(engine.featureGate().isTierC("latency.consumable-optimizer"));
        assertFalse(engine.isCompiled("latency.consumable-optimizer"));
    }

    // ---- step 2: unknown module ids ------------------------------------------------------------

    @Test
    @DisplayName("an unknown module id DENIES; it is never allowed by omission")
    void unknownModuleDenies() {
        Decision decision = engine().authorize("some.feature-nobody-researched", Context.SERVER);
        assertFalse(decision.allow());
        assertEquals(Verdict.DENIED, decision.verdict());
        assertEquals(DecisionSource.PROFILE_DENIED, decision.source());
        assertFalse(decision.citations().isEmpty(), "even a refusal carries dated evidence");
    }

    @Test
    @DisplayName("a blank or absent module id DENIES")
    void blankModuleIdDenies() {
        DefaultComplianceEngine engine = engine();
        assertFalse(engine.authorize("", Context.SERVER).allow());
        assertFalse(engine.authorize("   ", Context.SERVER).allow());
        assertFalse(engine.authorize(null, Context.SERVER).allow());
    }

    @Test
    @DisplayName("authorize never throws for a missing feature; it returns a value")
    void authorizeNeverThrowsForMissingFeatures() {
        DefaultComplianceEngine engine = engine();
        String[] ids = {null, "", "nope", "hud.totemCounter", "...", "Hud.Totem-Counter"};
        for (String id : ids) {
            assertFalse(engine.authorize(id, Context.WORLD).allow(), String.valueOf(id));
        }
    }

    @Test
    @DisplayName("a null Context is a caller bug, not a user-facing state, so it throws")
    void nullContextThrows() {
        assertThrows(XsozContractException.class,
                () -> engine().authorize("hud.totem-counter", null));
    }

    @Test
    @DisplayName("a feature in the gate with no registered module also DENIES")
    void compiledButUnregisteredDenies() {
        Map<String, ComplianceTier> compiled = new LinkedHashMap<String, ComplianceTier>();
        compiled.put("perf.render-distance", ComplianceTier.A);
        DefaultComplianceEngine engine = DefaultComplianceEngine.builder()
                .featureGate(FeatureGate.of(compiled, Collections.<String>emptySet()))
                .subjectIndex(index(subjects))
                .build();
        Decision decision = engine.authorize("perf.render-distance", Context.SERVER);
        assertFalse(decision.allow());
        assertEquals(DecisionSource.PROFILE_DENIED, decision.source());
    }

    // ---- step 3: capabilities, BEFORE the context -----------------------------------------------

    @Test
    @DisplayName("step 3 - a missing capability is UNVERIFIED, and it is checked before the context")
    void missingCapabilityIsUnverifiedAndPrecedesContext() {
        capabilities.remove("END_CRYSTAL");
        Decision decision = engine().authorize("latency.crystal-release", Context.WORLD_ABSENT);
        assertFalse(decision.allow());
        assertEquals(Verdict.UNVERIFIED, decision.verdict());
        assertEquals(DecisionSource.CAPABILITY_MISSING, decision.source());
        assertTrue(decision.reason().contains("END_CRYSTAL"), decision.reason());
    }

    // ---- step 4: context -----------------------------------------------------------------------

    @Test
    @DisplayName("step 4 - a context outside allowedContexts is inert, not denied on the network")
    void contextGating() {
        DefaultComplianceEngine engine = engine();
        assertTrue(engine.authorize("hud.totem-counter", Context.SERVER).allow());
        Decision inMenuOnly = engine.authorize("coach.gate-overlay", Context.SERVER);
        assertFalse(inMenuOnly.allow());
        assertEquals(DecisionSource.CONTEXT_NOT_ALLOWED, inMenuOnly.source());
        assertTrue(engine.authorize("coach.gate-overlay", Context.SCREEN_OPEN).allow());
    }

    @Test
    @DisplayName("step 4 - a per-context verdict: the same module differs by context")
    void perContextVerdicts() {
        DefaultComplianceEngine engine = engine();
        assertTrue(engine.authorize("coach.gate-overlay", Context.SCREEN_OPEN).allow());
        assertFalse(engine.authorize("coach.gate-overlay", Context.WORLD).allow());
        assertFalse(engine.authorize("coach.gate-overlay", Context.SERVER).allow());
    }

    @Test
    @DisplayName("step 4 - WORLD_ABSENT is always refused, whatever the module allows")
    void worldAbsentAlwaysRefuses() {
        DefaultComplianceEngine engine = engine();
        for (String id : subjects.keySet()) {
            assertFalse(engine.authorize(id, Context.WORLD_ABSENT).allow(),
                    id + " must be inert with no world loaded");
        }
    }

    @Test
    @DisplayName("Context is a single-value enum resolved by the documented total function")
    void contextResolutionPriority() {
        assertEquals(Context.WORLD_ABSENT, Context.resolve(false, false, false));
        assertEquals(Context.WORLD_ABSENT, Context.resolve(false, true, true));
        assertEquals(Context.SCREEN_OPEN, Context.resolve(true, true, false));
        assertEquals(Context.SCREEN_OPEN, Context.resolve(true, true, true));
        assertEquals(Context.SERVER, Context.resolve(true, false, true));
        assertEquals(Context.WORLD, Context.resolve(true, false, false));
        assertEquals(4, Context.values().length, "C7.6: WORLD_ABSENT, WORLD, SCREEN_OPEN, SERVER");
    }

    // ---- the add-only allow list ---------------------------------------------------------------

    @Test
    @DisplayName("the empty allow list is why Tier B ships off; one grant resolves it")
    void allowListGrantResolvesTierB() {
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
                .subjectIndex(index(subjects))
                .capabilityView(capabilities(capabilities, satisfiedConditions))
                .build();
        assertFalse(engine.authorize("latency.crystal-release", Context.SERVER).allow(),
                "unmatched host is default-deny, whose allow list is empty");
        engine.resolveFor("pvphq.com");
        Decision allowed = engine.authorize("latency.crystal-release", Context.SERVER);
        assertTrue(allowed.allow(), "an explicit (host, featureId) grant resolves the row");
        assertEquals(DecisionSource.STAFF_CLEARANCE, allowed.source());
        assertTrue(allowed.reason().contains("allow-list"), allowed.reason());
    }

    // ---- staleness -----------------------------------------------------------------------------

    @Test
    @DisplayName("a snapshot older than the running build is STALE, and is surfaced on every decision")
    void staleSnapshotIsSurfacedNotUpgraded() {
        DefaultComplianceEngine engine = engine(ON.plusYears(1));
        assertEquals(ComplianceProfile.Staleness.STALE, engine.profileStaleness());
        assertTrue(engine.resolvedProfile().isStale(ON.plusYears(1)));

        Decision allowed = engine.authorize("hud.totem-counter", Context.SERVER);
        assertTrue(allowed.allow(), "staleness must not silently change a verdict");
        assertTrue(allowed.reason().contains("STALE"), allowed.reason());
        assertTrue(allowed.reason().contains("Surfaced, not upgraded"), allowed.reason());

        Decision refused = engine.authorize("latency.crystal-release", Context.SERVER);
        assertTrue(refused.reason().contains("STALE"), refused.reason());
    }

    @Test
    @DisplayName("a current snapshot carries no staleness note")
    void currentSnapshotIsSilent() {
        Decision decision = engine().authorize("hud.totem-counter", Context.SERVER);
        assertFalse(decision.reason().contains("STALE"), decision.reason());
    }

    @Test
    @DisplayName("staleness is a three-state answer, and UNKNOWN is not CURRENT")
    void stalenessStates() {
        ComplianceProfile profile = ComplianceProfileSet.bundled().byId("pvphq");
        assertEquals(ComplianceProfile.Staleness.CURRENT,
                ComplianceProfile.Staleness.of(profile, ON));
        assertEquals(ComplianceProfile.Staleness.STALE,
                ComplianceProfile.Staleness.of(profile, ON.plusDays(1)));
        assertEquals(ComplianceProfile.Staleness.UNKNOWN,
                ComplianceProfile.Staleness.of(profile, null));
    }

    // ---- resolution ----------------------------------------------------------------------------

    @Test
    @DisplayName("resolveFor re-resolves on a host change, and no server means default-deny")
    void hostResolutionDrivesTheProfile() {
        DefaultComplianceEngine engine = engine();
        assertEquals(ComplianceProfile.DEFAULT_DENY_ID, engine.resolvedProfile().id());
        assertFalse(engine.matchedBinding().isPresent());

        engine.resolveFor("PvPHQ.com:25565");
        assertEquals("pvphq", engine.resolvedProfile().id());
        assertTrue(engine.matchedBinding().isPresent());
        assertEquals("pvphq.com", engine.matchedBinding().get().hostPattern());

        engine.resolveFor(null);
        assertEquals(ComplianceProfile.DEFAULT_DENY_ID, engine.resolvedProfile().id());
        assertFalse(engine.matchedBinding().isPresent());
        assertEquals(ON, engine.rulesSnapshotDate());
    }

    // ---- the audit surface ---------------------------------------------------------------------

    @Test
    @DisplayName("allDecisions returns one decision per compiled feature, and every one is cited")
    void allDecisionsIsSortedAndComplete() {
        java.util.List<Decision> decisions = engine().allDecisions();
        assertEquals(3, decisions.size(), "one decision per compiled feature");
        for (Decision decision : decisions) {
            assertFalse(decision.citations().isEmpty(),
                    "an inventory-screen decision still needs its citation and its date");
        }
        assertThrows(UnsupportedOperationException.class, () -> decisions.clear());
    }

    // ---- fixture builders -----------------------------------------------------------------------

    static DefaultComplianceEngine.ComplianceSubjectIndex index(
            final Map<String, ComplianceSubject> subjects) {
        return new DefaultComplianceEngine.ComplianceSubjectIndex() {
            @Override
            public Optional<ComplianceSubject> byId(String moduleId) {
                return Optional.ofNullable(subjects.get(moduleId));
            }
        };
    }

    static DefaultComplianceEngine.CapabilityView capabilities(final Set<String> present,
                                                               final Set<String> conditions) {
        return new DefaultComplianceEngine.CapabilityView() {
            @Override
            public boolean has(String capabilityName) {
                return present.contains(capabilityName);
            }

            @Override
            public boolean conditionHolds(String conditionId) {
                return conditions.contains(conditionId);
            }
        };
    }

    static DefaultComplianceEngine.AcknowledgementView acknowledgement(final Set<String> acknowledged) {
        return new DefaultComplianceEngine.AcknowledgementView() {
            @Override
            public boolean isAcknowledged(String featureId) {
                return acknowledged.contains(featureId);
            }
        };
    }

    /** The three {@link ComplianceSubject}s every engine test shares. */
    static final class TestSubjects {

        private TestSubjects() {
        }

        /** Tier A own-state HUD element; needs the totem capability. */
        static ComplianceSubject totemCounter() {
            return new ComplianceSubject() {
                @Override
                public String moduleId() {
                    return "hud.totem-counter";
                }

                @Override
                public ComplianceTier complianceTier() {
                    return ComplianceTier.A;
                }

                @Override
                public Set<Context> allowedContexts() {
                    return EnumSet.of(Context.WORLD, Context.SCREEN_OPEN, Context.SERVER);
                }

                @Override
                public Set<String> requiredCapabilityNames() {
                    return Collections.singleton("TOTEM_OF_UNDYING");
                }
            };
        }

        /** Tier B optimising feature; needs the end-crystal capability. */
        static ComplianceSubject crystalRelease() {
            return new ComplianceSubject() {
                @Override
                public String moduleId() {
                    return "latency.crystal-release";
                }

                @Override
                public ComplianceTier complianceTier() {
                    return ComplianceTier.B;
                }

                @Override
                public Set<Context> allowedContexts() {
                    return EnumSet.of(Context.WORLD, Context.SERVER);
                }

                @Override
                public Set<String> requiredCapabilityNames() {
                    return Collections.singleton("END_CRYSTAL");
                }
            };
        }

        /** Tier A menu-only overlay; no capability requirement. */
        static ComplianceSubject coachingGate() {
            return new ComplianceSubject() {
                @Override
                public String moduleId() {
                    return "coach.gate-overlay";
                }

                @Override
                public ComplianceTier complianceTier() {
                    return ComplianceTier.A;
                }

                @Override
                public Set<Context> allowedContexts() {
                    return EnumSet.of(Context.SCREEN_OPEN, Context.WORLD_ABSENT);
                }

                @Override
                public Set<String> requiredCapabilityNames() {
                    return Collections.<String>emptySet();
                }
            };
        }
    }
}
