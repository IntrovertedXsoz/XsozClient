package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract C7.8 - {@code FalsifiabilityGateTest}.
 *
 * <p><strong>The registration gate, and each of its six refusals.</strong> A prediction
 * feature is only shippable if it can be shown to yield: a stated local action, a named
 * falsifier this pole can actually observe, a finite bound, a way to give up, no packet, and
 * a Tier B owner. A feature that cannot state how it gives up has answered the yield/fight
 * question, and the answer it has given by construction is <em>fight</em>.</p>
 *
 * <p>Worked examples from C7.8 are implemented in full:
 * {@link CrystalReleasePredictor} is example A (LEGAL) and
 * {@link ConsumableOptimizerPredictor} is example B (ILLEGAL, and refused).</p>
 */
class FalsifiabilityGateTest {

    private static final String CRYSTAL_RELEASE = "latency.crystal-release";

    // ---- example A: the LEGAL Marlow-style predictor ------------------------------------------

    @Test
    @DisplayName("example A - a complete, legal Tier B predictor registers")
    void completeLegalPredictorRegisters() {
        PredictorRegistry.Default registry = registry();
        CrystalReleasePredictor predictor = new CrystalReleasePredictor();

        registry.register(predictor);

        assertEquals(1, registry.all().size());
        assertEquals(CRYSTAL_RELEASE, registry.all().get(0).id());
        assertEquals(PredictionAction.HIDE_ENTITY_FROM_RENDER, predictor.localAction());
        assertEquals(ServerSignal.ENTITY_REMOVED, predictor.falsifiedBy());
        assertEquals(1500, predictor.timeoutMillis());
        assertTrue(predictor.addsNoPacket());
        registry.assertAllSatisfied();
    }

    // ---- refusals 1 to 6: each one ------------------------------------------------------------

    @Test
    @DisplayName("condition 1 - a null localAction is refused")
    void nullLocalActionIsRefused() {
        Predictor predictor = new CrystalReleasePredictor() {
            @Override
            public PredictionAction localAction() {
                return null;
            }
        };
        assertRefused(predictor, "condition 1");
    }

    @Test
    @DisplayName("condition 2 - a null falsifiedBy is refused")
    void nullFalsifierIsRefused() {
        Predictor predictor = new CrystalReleasePredictor() {
            @Override
            public ServerSignal falsifiedBy() {
                return null;
            }
        };
        assertRefused(predictor, "condition 2");
    }

    @Test
    @DisplayName("condition 3 - a falsifier this pole cannot observe is refused")
    void unobservableFalsifierIsRefused() {
        PredictorRegistry.SignalObservability blind = new PredictorRegistry.SignalObservability() {
            @Override
            public boolean canObserve(ServerSignal signal) {
                return signal != ServerSignal.ENTITY_REMOVED;
            }
        };
        PredictorRegistry.Default registry = new PredictorRegistry.Default(
                tierBOwner(), blind);
        PredictionRegistrationException thrown = assertThrows(
                PredictionRegistrationException.class,
                () -> registry.register(new CrystalReleasePredictor()));
        assertTrue(thrown.getMessage().contains("condition 3"), thrown.getMessage());
        assertTrue(registry.all().isEmpty(), "a refused predictor is not half-registered");
    }

    @ParameterizedTest(name = "condition 4 - timeoutMillis {0} is refused")
    @ValueSource(ints = {0, -1, 2001, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void outOfRangeTimeoutIsRefused(int timeoutMillis) {
        final int timeout = timeoutMillis;
        Predictor predictor = new CrystalReleasePredictor() {
            @Override
            public int timeoutMillis() {
                return timeout;
            }
        };
        assertRefused(predictor, "condition 4");
    }

    @Test
    @DisplayName("condition 4 - the boundary values 1 and 2000 ms are inside the range")
    void timeoutBoundariesAreInside() {
        for (int boundary : new int[] {Predictor.MIN_TIMEOUT_MILLIS, Predictor.MAX_TIMEOUT_MILLIS}) {
            final int timeout = boundary;
            PredictorRegistry.Default registry = registry();
            registry.register(new CrystalReleasePredictor() {
                @Override
                public int timeoutMillis() {
                    return timeout;
                }
            });
            assertEquals(1, registry.all().size(), "timeout " + boundary + " must be legal");
        }
    }

    @Test
    @DisplayName("condition 5 - a predictor that adds a packet is refused")
    void addsPacketIsRefused() {
        Predictor predictor = new CrystalReleasePredictor() {
            @Override
            public boolean addsNoPacket() {
                return false;
            }
        };
        assertRefused(predictor, "condition 5");
    }

    @Test
    @DisplayName("condition 6 - a predictor on a Tier A owner is refused")
    void tierAOwnerIsRefused() {
        PredictorRegistry.Default registry = new PredictorRegistry.Default(
                new PredictorRegistry.OwnerTierLookup() {
                    @Override
                    public Optional<ComplianceTier> tierOf(String moduleId) {
                        return Optional.of(ComplianceTier.A);
                    }
                },
                PredictorRegistry.SignalObservability.all());
        PredictionRegistrationException thrown = assertThrows(
                PredictionRegistrationException.class,
                () -> registry.register(new CrystalReleasePredictor()));
        assertTrue(thrown.getMessage().contains("condition 6"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Tier A"), thrown.getMessage());
    }

    @Test
    @DisplayName("condition 6 - a predictor with no registered owner is refused")
    void orphanPredictorIsRefused() {
        PredictorRegistry.Default registry = new PredictorRegistry.Default(
                new PredictorRegistry.OwnerTierLookup() {
                    @Override
                    public Optional<ComplianceTier> tierOf(String moduleId) {
                        return Optional.empty();
                    }
                },
                PredictorRegistry.SignalObservability.all());
        PredictionRegistrationException thrown = assertThrows(
                PredictionRegistrationException.class,
                () -> registry.register(new CrystalReleasePredictor()));
        assertTrue(thrown.getMessage().contains("condition 6"), thrown.getMessage());
    }

    // ---- example B: the ILLEGAL Consumable-Optimizer class -------------------------------------

    @Test
    @DisplayName("example B - the Consumable-Optimizer class is REJECTED at registration")
    void consumableOptimizerIsRejected() {
        PredictorRegistry.Default registry = registry();
        ConsumableOptimizerPredictor illegal = new ConsumableOptimizerPredictor();

        PredictionRegistrationException thrown = assertThrows(
                PredictionRegistrationException.class, () -> registry.register(illegal));

        assertTrue(registry.all().isEmpty(),
                "There is no class, no config key and no profile entry: the refusal IS the point.");
        // Condition 1 is the one that fires first, because a mechanics change is not a
        // PredictionAction and the honest answer to localAction() is null. The other three
        // violations are asserted in consumableOptimizerViolatesEveryCondition below.
        assertTrue(thrown.getMessage().contains("condition 1"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("does not compile"), thrown.getMessage());
    }

    @Test
    @DisplayName("example B - it is refused for every reason it violates, not just the first")
    void consumableOptimizerViolatesEveryCondition() {
        ConsumableOptimizerPredictor illegal = new ConsumableOptimizerPredictor();
        // (a) the local action is a mechanics change, which no PredictionAction names.
        assertTrue(illegal.localAction() == null,
                "keeping eating is a mechanics change; there is no constant for it, which is why "
                        + "it does not compile as a Prediction");
        // (b) it has a falsifier, and its whole function is to discard that falsifier's packet.
        // Naming a falsifier you discard is naming a lie, and no constant in PredictionAction
        // lets you say otherwise - which is why the sibling test below shows that even a
        // perfectly well-formed wrapper around this feature is still refused.
        assertEquals(ServerSignal.ITEM_USE_STATE_RESET, illegal.falsifiedBy());
        // (c) no finite bound exists: it holds the belief until the item is consumed.
        assertTrue(illegal.timeoutMillis() < Predictor.MIN_TIMEOUT_MILLIS
                        || illegal.timeoutMillis() > Predictor.MAX_TIMEOUT_MILLIS,
                "an unbounded prediction has no legal timeout value");
        // (d) there is no yield path: yield() throws rather than releasing the belief.
        assertThrows(UnsupportedOperationException.class,
                () -> illegal.yield(new SignalFrame(1L, 50L, ServerSignal.ITEM_USE_STATE_RESET)));
        // and the evidence is Grim's own page, retrieved on the date the docs record.
        assertEquals("grim-wiki", ComplianceCitations.GRIM_FIGHTING_PREDICTION_DETECTION.rulesetId());
        assertTrue(ComplianceCitations.GRIM_FIGHTING_PREDICTION_DETECTION.clause()
                .contains("Ignores the server's item use state"));
        assertEquals(LocalDate.of(2026, 9, 28),
                ComplianceCitations.GRIM_FIGHTING_PREDICTION_DETECTION.retrievedOn());
    }

    @Test
    @DisplayName("example B - even a well-formed wrapper is refused, because the tier is the point")
    void consumableOptimizerIsRefusedEvenWhenWellFormed() {
        // A Prediction that names ITEM_USE_STATE_RESET, claims a 1500 ms bound, claims no
        // packet, and yields properly - and whose owner is Tier B. It still registers here,
        // which is precisely why the tier check and the feature-gate are load bearing: this
        // feature must never BE Tier B, because at Tier B it would pass the gate while
        // doing the one thing the gate exists to prevent. The gate cannot be the only defence.
        PredictorRegistry.Default registry = registry();
        PredictionRegistrationException tierRefusal = assertThrows(
                PredictionRegistrationException.class,
                () -> registry.register(new WellFormedConsumableOptimizer()));
        assertTrue(tierRefusal.getMessage().contains("condition 6"), tierRefusal.getMessage());
        assertTrue(tierRefusal.getMessage().contains("Tier C"), tierRefusal.getMessage());
    }

    // ---- structural properties ------------------------------------------------------------------

    @Test
    @DisplayName("PredictionAction is closed: five rendering and local-belief constants, no mechanics")
    void predictionActionIsClosed() {
        assertEquals(5, PredictionAction.values().length);
        for (String constant : Arrays.asList("HIDE_ENTITY_FROM_RENDER",
                "HIDE_ENTITY_FROM_CROSSHAIR_TARGET", "REPLACE_LOCAL_BLOCK_WITH_GHOST",
                "HIDE_BLOCK_FROM_LOCAL_COLLISION", "SUPPRESS_LOCAL_COOLDOWN_OVERLAY")) {
            assertEquals(constant, PredictionAction.valueOf(constant).name());
        }
        for (String banned : Arrays.asList("DELAY_PACKET", "SUPPRESS_USE_STATE", "ALTER_PLACEMENT",
                "ALTER_TICK_RATE", "SEND_PACKET", "CANCEL_PACKET")) {
            assertThrows(IllegalArgumentException.class, () -> PredictionAction.valueOf(banned),
                    banned + " must not exist: its absence is the enforcement");
        }
    }

    @Test
    @DisplayName("a duplicate registration is refused: two beliefs, one falsifier")
    void duplicateIdIsRefused() {
        PredictorRegistry.Default registry = registry();
        registry.register(new CrystalReleasePredictor());
        assertThrows(XsozContractException.class,
                () -> registry.register(new CrystalReleasePredictor()));
    }

    @ParameterizedTest(name = "{0} is not a valid module id and cannot be registered")
    @ValueSource(strings = {"latency.crystalRelease", "Latency.Crystal", "latency..crystal",
            ".crystal", "crystal.", "1crystal", "latency.crystal-release!"})
    void invalidModuleIdIsRefused(String id) {
        final String predictorId = id;
        Predictor predictor = new CrystalReleasePredictor() {
            @Override
            public String id() {
                return predictorId;
            }
        };
        assertThrows(XsozContractException.class, () -> registry().register(predictor));
    }

    @Test
    @DisplayName("assertAllSatisfied re-checks at startup, and throws when a pole stops observing")
    void assertAllSatisfiedRechecksObservability() {
        final Set<ServerSignal> observable = new LinkedHashSet<ServerSignal>(
                EnumSet.allOf(ServerSignal.class));
        PredictorRegistry.Default registry = new PredictorRegistry.Default(
                tierBOwner(),
                new PredictorRegistry.SignalObservability() {
                    @Override
                    public boolean canObserve(ServerSignal signal) {
                        return observable.contains(signal);
                    }
                });
        registry.register(new CrystalReleasePredictor());
        registry.assertAllSatisfied();

        // A dimension or version change removed the capability this pole relies on.
        observable.remove(ServerSignal.ENTITY_REMOVED);
        assertThrows(PredictionRegistrationException.class, registry::assertAllSatisfied);
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private static PredictorRegistry.Default registry() {
        return new PredictorRegistry.Default(tierBOwner(),
                PredictorRegistry.SignalObservability.all());
    }

    private static PredictorRegistry.OwnerTierLookup tierBOwner() {
        final Map<String, ComplianceTier> tiers = new LinkedHashMap<String, ComplianceTier>();
        tiers.put(CRYSTAL_RELEASE, ComplianceTier.B);
        // Grim names the Consumable-Optimizer class, so it is Tier C and is never compiled.
        // Recording it here is what makes the "well-formed wrapper" case fail on the tier.
        tiers.put("latency.consumable-optimizer", ComplianceTier.C);
        return new PredictorRegistry.OwnerTierLookup() {
            @Override
            public Optional<ComplianceTier> tierOf(String moduleId) {
                return Optional.ofNullable(tiers.get(moduleId));
            }
        };
    }

    private static void assertRefused(Predictor predictor, String expectedCondition) {
        PredictorRegistry.Default registry = registry();
        PredictionRegistrationException thrown = assertThrows(
                PredictionRegistrationException.class, () -> registry.register(predictor));
        assertTrue(thrown.getMessage().contains(expectedCondition),
                "expected the refusal to name " + expectedCondition + ", got: " + thrown.getMessage());
        assertTrue(registry.all().isEmpty(), "a refused predictor must not be registered");
        assertFalse(thrown.getMessage().isEmpty(), "a refusal must state why");
    }

    // ---- example A: LEGAL, the Marlow-style post-break crystal release -------------------------

    /**
     * Worked example A from contracts.md C7.8, implemented.
     *
     * <p>On a client-computed break of an End-crystal entity whose base block the local
     * player placed, drop that entity from the local render list and from the local target
     * list. <strong>Nothing else.</strong> The vanilla attack was already sent at vanilla
     * timing; the place action, the place cooldown, reach and damage are untouched. The
     * belief is released on the server's {@code ENTITY_REMOVED} for that entity id, or after
     * 1500 ms, whichever comes first.</p>
     */
    static class CrystalReleasePredictor implements Predictor {

        private final Set<Integer> suppressed = new LinkedHashSet<Integer>();
        private int yieldCount;
        private int timeoutYieldCount;

        @Override
        public String id() {
            return CRYSTAL_RELEASE;
        }

        @Override
        public PredictionAction localAction() {
            return PredictionAction.HIDE_ENTITY_FROM_RENDER;
        }

        @Override
        public ServerSignal falsifiedBy() {
            return ServerSignal.ENTITY_REMOVED;
        }

        @Override
        public int timeoutMillis() {
            return 1500;
        }

        @Override
        public void yield(SignalFrame frame) {
            if (frame.entityId().isPresent()) {
                suppressed.remove(Integer.valueOf(frame.entityId().getAsInt()));
            } else {
                suppressed.clear();
            }
            yieldCount++;
        }

        @Override
        public void yieldOnTimeout() {
            suppressed.clear();
            timeoutYieldCount++;
        }

        @Override
        public boolean addsNoPacket() {
            return true;
        }

        /** Owner half of the loop: the local client-computed break begins the prediction. */
        boolean begin(int entityId) {
            return suppressed.add(Integer.valueOf(entityId));
        }

        /** @return whether the entity is currently hidden locally */
        boolean isSuppressed(int entityId) {
            return suppressed.contains(Integer.valueOf(entityId));
        }

        int yieldCount() {
            return yieldCount;
        }

        int timeoutYieldCount() {
            return timeoutYieldCount;
        }
    }

    // ---- example B: ILLEGAL, the Consumable-Optimizer class -------------------------------------

    /**
     * Worked example B from contracts.md C7.8, implemented so the refusal can be demonstrated
     * rather than asserted.
     *
     * <p><strong>THIS CLASS IS NOT A FEATURE AND MUST NEVER BE REGISTERED OR SHIPPED.</strong>
     * It exists so {@code FalsifiabilityGateTest} can drive a real object through
     * {@code register} and watch it be refused. The method bodies are the feature's real
     * behaviour, which is why they are written the way they are: the local action is a
     * mechanics change, the falsifier is the packet the feature discards, there is no finite
     * timeout, and there is no way to give up.</p>
     */
    static final class ConsumableOptimizerPredictor implements Predictor {

        @Override
        public String id() {
            return "latency.consumable-optimizer";
        }

        /**
         * "Keep eating when the server's item-use state says stop." No
         * {@link PredictionAction} names a mechanics change, so the honest answer is
         * {@code null} - and the gate refuses {@code null}.
         *
         * @return always {@code null}, because there is no constant for this
         */
        @Override
        public PredictionAction localAction() {
            return null;
        }

        /**
         * It <em>has</em> a falsifier, and that is exactly the problem: the feature's entire
         * function is to discard the falsifier's packet. Naming a falsifier you discard is
         * naming a lie.
         *
         * @return always {@link ServerSignal#ITEM_USE_STATE_RESET}
         */
        @Override
        public ServerSignal falsifiedBy() {
            return ServerSignal.ITEM_USE_STATE_RESET;
        }

        /**
         * No finite bound exists: it holds the belief until the item is consumed.
         *
         * @return {@code 0}, which the gate reads as "unbounded"
         */
        @Override
        public int timeoutMillis() {
            return 0;
        }

        /**
         * There is no yield path. Throwing is not yielding; it is a crash.
         *
         * @param frame the falsifying signal
         */
        @Override
        public void yield(SignalFrame frame) {
            throw new UnsupportedOperationException(
                    "This class has no yield path. A feature that cannot state how it gives up has "
                            + "not answered the yield/fight question, and the answer it has given by "
                            + "construction is fight.");
        }

        /** Never reached; the timeout is unbounded so it has no timeout path either. */
        @Override
        public void yieldOnTimeout() {
            throw new UnsupportedOperationException("unbounded: there is no timeout to fire");
        }

        /** It adds nothing to the wire, which is the one thing about it that is not disqualifying. */
        @Override
        public boolean addsNoPacket() {
            return true;
        }
    }

    /**
     * The strongest version of example B: it answers all four questions <em>correctly</em> and
     * adds no packet. It is refused for the sixth reason alone - its owner is not Tier B.
     *
     * <p>This case is the reason the tier check exists as an independent condition rather than
     * as a comment: a well-formed wrapper around a banned mechanic passes conditions 1-5, and
     * only condition 6 stops it.</p>
     */
    static final class WellFormedConsumableOptimizer implements Predictor {

        @Override
        public String id() {
            return "latency.consumable-optimizer";
        }

        @Override
        public PredictionAction localAction() {
            return PredictionAction.SUPPRESS_LOCAL_COOLDOWN_OVERLAY;
        }

        @Override
        public ServerSignal falsifiedBy() {
            return ServerSignal.ITEM_USE_STATE_RESET;
        }

        @Override
        public int timeoutMillis() {
            return 1500;
        }

        @Override
        public void yield(SignalFrame frame) {
            // Would "release the belief" - if there were a belief to release.
        }

        @Override
        public void yieldOnTimeout() {
            // Same.
        }

        @Override
        public boolean addsNoPacket() {
            return true;
        }
    }
}
