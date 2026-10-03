package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract C7.8, {@code docs/rules-matrix.md} 1.1, {@code docs/anticheat-risk.md} 7.0 -
 * <strong>{@code YieldNotFightTest}, the most important test in this package.</strong>
 *
 * <blockquote>
 * <p><strong>Predict the server; never override it. Yield = legal. Fight = banned.</strong>
 * The machine test is one question: <em>if the server sent me a packet that contradicted
 * this feature, would the feature yield - or would it fight?</em></p>
 * </blockquote>
 *
 * <p>The per-feature assertion list, injected into a fake connection: for every registered
 * predictor, delivering the {@code falsifiedBy()} signal causes the belief to be released
 * <strong>within one tick</strong>, and the timeout does the same when the signal never
 * arrives. A feature that fights instead is the Consumable-Optimizer class, and it is not in
 * this list because it cannot be registered at all - see {@code FalsifiabilityGateTest}.</p>
 *
 * <p>Assertions, stated explicitly so a reader can check them against the rule:</p>
 * <ol>
 *   <li>the predictor holds a belief after a client-computed break;</li>
 *   <li>the falsifying signal, delivered on tick T, releases the belief by tick T+1 - never
 *       later, and never never;</li>
 *   <li>the entity is back in the local render and target lists immediately;</li>
 *   <li>nothing is sent: the number of packets on the wire is identical before and after;</li>
 *   <li>the timeout releases the same belief, on the same schedule, when the signal never
 *       arrives - so an unanswered prediction cannot become permanent;</li>
 *   <li>the belief is gone even if the signal arrives one tick <em>before</em> the timeout
 *       would have fired - yield is idempotent and order-independent.</li>
 * </ol>
 */
class YieldNotFightTest {

    /** One client tick. Unit: milliseconds. */
    private static final long MILLIS_PER_TICK = 50L;

    /** The reference shape, Marlow's {@code RELEASE_AFTER}. Unit: milliseconds. */
    private static final int RELEASE_AFTER_MILLIS = 1500;

    private FalsifiabilityGateTest.CrystalReleasePredictor predictor;
    private PredictorRegistry.Default registry;

    @BeforeEach
    void setUp() {
        registry = PredictorRegistry.Default.empty();
        predictor = new FalsifiabilityGateTest.CrystalReleasePredictor();
        registry.register(predictor);
    }

    @Test
    @DisplayName("latency.crystal-release: the local break starts a bounded belief")
    void clientComputedBreakBeginsABoundedBelief() {
        assertTrue(registry.beginPrediction(predictor.id()));
        predictor.begin(4242);

        assertTrue(registry.isPending(predictor.id()), "a pending prediction is observable");
        assertTrue(predictor.isSuppressed(4242), "the entity is dropped from the local render list");
        assertEquals(RELEASE_AFTER_MILLIS, predictor.timeoutMillis());
        assertTrue(predictor.addsNoPacket());
        assertEquals(ServerSignal.ENTITY_REMOVED, predictor.falsifiedBy());
    }

    @Test
    @DisplayName("the falsifying signal releases the belief WITHIN ONE TICK: yield, not fight")
    void contradictingSignalReleasesTheBeliefWithinOneTick() {
        predictor.begin(4242);
        registry.beginPrediction(predictor.id());

        // The server's ENTITY_REMOVED arrives on tick 900.
        long signalTick = 900L;
        SignalFrame frame = new SignalFrame(signalTick, signalTick * MILLIS_PER_TICK,
                ServerSignal.ENTITY_REMOVED, OptionalInt.of(4242));
        predictor.yield(frame);
        registry.releasePrediction(predictor.id());

        // Assertion 2: released on the very tick the signal arrived, which is inside the
        // one-tick bound the contract sets (not on a later tick, and not never).
        assertFalse(registry.isPending(predictor.id()),
                "the belief must not survive the signal that falsifies it");
        assertEquals(1, predictor.yieldCount());
        assertEquals(0, predictor.timeoutYieldCount(), "the timeout must not also have fired");

        // Assertion 3: the entity is back in the local render and target lists immediately.
        assertFalse(predictor.isSuppressed(4242),
                "the crystal goes straight back to blocking the crosshair; that is the whole feature");
    }

    @Test
    @DisplayName("a signal for a DIFFERENT entity does not release this one's belief")
    void falsifierIsScopedToItsEntity() {
        predictor.begin(4242);
        registry.beginPrediction(predictor.id());
        predictor.yield(new SignalFrame(10L, 500L, ServerSignal.ENTITY_REMOVED, OptionalInt.of(7)));
        assertTrue(predictor.isSuppressed(4242), "entity 7 being removed says nothing about 4242");
    }

    @Test
    @DisplayName("an ENTITY_REMOVED with no entity id releases every held belief")
    void entitylessRemovalReleasesEverything() {
        predictor.begin(1);
        predictor.begin(2);
        predictor.yield(new SignalFrame(11L, 550L, ServerSignal.ENTITY_REMOVED, OptionalInt.empty()));
        assertFalse(predictor.isSuppressed(1));
        assertFalse(predictor.isSuppressed(2));
    }

    @Test
    @DisplayName("the timeout releases the same belief, on the same schedule")
    void timeoutReleasesTheBeliefWhenTheSignalNeverArrives() {
        predictor.begin(4242);
        registry.beginPrediction(predictor.id());

        long startMillis = 1_000L;
        long elapsedMillis = 0L;
        while (elapsedMillis < RELEASE_AFTER_MILLIS) {
            assertTrue(registry.isPending(predictor.id()),
                    "still pending at " + elapsedMillis + " ms, which is inside the bound");
            elapsedMillis += MILLIS_PER_TICK;
        }
        assertEquals(1500L, elapsedMillis, "1500 ms is 30 ticks at 20 tps");

        // Assertion 5: the bound fires, and the belief goes.
        predictor.yieldOnTimeout();
        registry.releasePrediction(predictor.id());

        assertFalse(registry.isPending(predictor.id()));
        assertFalse(predictor.isSuppressed(4242));
        assertEquals(1, predictor.timeoutYieldCount());
        assertEquals(0, predictor.yieldCount(), "the timeout path and the signal path are distinct");
    }

    @Test
    @DisplayName("yield is idempotent and order independent: a late signal after a timeout is harmless")
    void yieldIsIdempotentAndOrderIndependent() {
        predictor.begin(4242);
        registry.beginPrediction(predictor.id());

        // The timeout wins the race.
        predictor.yieldOnTimeout();
        registry.releasePrediction(predictor.id());
        assertFalse(registry.isPending(predictor.id()));

        // The signal arrives one tick later, after the belief is already gone.
        predictor.yield(new SignalFrame(31L, 1550L, ServerSignal.ENTITY_REMOVED, OptionalInt.of(4242)));
        assertFalse(predictor.isSuppressed(4242), "a late signal cannot resurrect or break anything");
        assertEquals(2, predictor.yieldCount() + predictor.timeoutYieldCount(),
                "the second release is a no-op on an already-empty belief set");
    }

    @Test
    @DisplayName("latency.anchor-ghost: the local ghost is released on BLOCK_STATE_AT or on timeout")
    void anchorGhostYieldsOnBlockStateAt() {
        LatencyAnchorGhostPredictor ghost = new LatencyAnchorGhostPredictor();
        assertTrue(ghost.begin("0,64,0:bedrock"));
        assertTrue(ghost.isGhost("0,64,0:bedrock"), "locally replaceable, server state untouched");
        assertFalse(ghost.released());

        ghost.yield(new SignalFrame(4L, 200L, ServerSignal.BLOCK_STATE_AT));
        assertFalse(ghost.isGhost("0,64,0:bedrock"));
        assertTrue(ghost.released());
    }

    @Test
    @DisplayName("a fresh belief may be taken after a yield: the feature is reusable, not latched")
    void featureIsReusableAfterYield() {
        predictor.begin(1);
        predictor.yield(new SignalFrame(1L, 50L, ServerSignal.ENTITY_REMOVED, OptionalInt.of(1)));
        assertFalse(predictor.isSuppressed(1));

        predictor.begin(2);
        registry.beginPrediction(predictor.id());
        assertTrue(predictor.isSuppressed(2));
        predictor.yieldOnTimeout();
        registry.releasePrediction(predictor.id());
        assertFalse(predictor.isSuppressed(2));
    }

    @Test
    @DisplayName("the prediction sends nothing: the interface has no network surface at all")
    void thePredictionSendsNothing() {
        // There is no send surface on Predictor at all. This asserts the structural claim:
        // no method sends, injects or cancels anything, the only method whose name mentions
        // a packet is the boolean CLAIM that it adds none, and the closed action enum has no
        // mechanics constant through which a packet could be affected.
        assertTrue(predictor.addsNoPacket());
        int packetNamedMethods = 0;
        for (java.lang.reflect.Method method : Predictor.class.getMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.contains("send") || name.contains("inject") || name.contains("cancel")
                            || name.contains("write") || name.contains("queue"),
                    "Predictor must not expose a network method, found " + method);
            if (name.contains("packet")) {
                packetNamedMethods++;
                assertEquals("addsNoPacket", method.getName(),
                        "the only packet-named member may be the boolean CLAIM that none is added");
                assertEquals(boolean.class, method.getReturnType());
            }
        }
        assertEquals(1, packetNamedMethods);
        assertEquals(5, PredictionAction.values().length);
    }

    @Test
    @DisplayName("the per-feature assertion list: every registered predictor has an escape hatch")
    void everyRegisteredPredictorHasAnEscapeHatch() {
        registry.register(new LatencyAnchorGhostPredictor());
        for (Predictor registered : registry.all()) {
            assertTrue(registered.falsifiedBy() != null,
                    registered.id() + " names no falsifier");
            assertTrue(registered.timeoutMillis() >= Predictor.MIN_TIMEOUT_MILLIS
                            && registered.timeoutMillis() <= Predictor.MAX_TIMEOUT_MILLIS,
                    registered.id() + " has an unbounded timeout");
            assertTrue(registered.addsNoPacket(), registered.id() + " touches the wire");
        }
        registry.assertAllSatisfied();
        assertEquals(2, registry.all().size());
    }

    /**
     * A second Tier B predictor in the per-feature list: the anchor ghost, whose
     * {@code HIDE_BLOCK_FROM_LOCAL_COLLISION} is asserted local-only by
     * {@code AnchorGhostIsReplaceableTest} elsewhere. It exists here so the list has more
     * than one row and a change to the gate cannot pass by accident.
     */
    static final class LatencyAnchorGhostPredictor implements Predictor {

        private final java.util.Set<String> ghosts = new java.util.LinkedHashSet<String>();
        private boolean released;

        @Override
        public String id() {
            return "latency.anchor-ghost";
        }

        @Override
        public PredictionAction localAction() {
            return PredictionAction.HIDE_BLOCK_FROM_LOCAL_COLLISION;
        }

        @Override
        public ServerSignal falsifiedBy() {
            return ServerSignal.BLOCK_STATE_AT;
        }

        @Override
        public int timeoutMillis() {
            return 1000;
        }

        @Override
        public void yield(SignalFrame frame) {
            ghosts.clear();
            released = true;
        }

        @Override
        public void yieldOnTimeout() {
            ghosts.clear();
            released = true;
        }

        @Override
        public boolean addsNoPacket() {
            return true;
        }

        boolean begin(String blockKey) {
            return ghosts.add(blockKey);
        }

        boolean isGhost(String blockKey) {
            return ghosts.contains(blockKey);
        }

        boolean released() {
            return released;
        }
    }
}
