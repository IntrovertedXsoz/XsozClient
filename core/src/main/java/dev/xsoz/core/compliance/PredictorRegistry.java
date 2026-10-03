package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The yield/fight registration gate (contracts.md C7.8).
 *
 * <p><strong>{@link #register(Predictor)} throws {@link PredictionRegistrationException} -
 * the feature cannot ship - on any of six conditions.</strong> They are enumerated in
 * {@link #check} and each has a case in {@code FalsifiabilityGateTest}.</p>
 *
 * <p>The gate is also re-run by {@link #assertAllSatisfied()}, which the startup self-test
 * calls before the player can queue (C7.9 step 5). Re-running matters because two of the
 * six conditions depend on the <em>pole</em>: observability and capability set both change
 * across a version change or a dimension change, so a registration that was legal at boot
 * can become unsatisfiable later.</p>
 */
public interface PredictorRegistry {

    /**
     * Registers a prediction feature, refusing it if any part of the yield/fight contract is
     * missing.
     *
     * @param predictor the feature
     * @throws PredictionRegistrationException if the feature cannot be shown to yield
     * @throws XsozContractException           if the id is not a valid module id, or is
     *                                         already registered
     */
    void register(Predictor predictor);

    /** @return every registered predictor, in registration order */
    List<Predictor> all();

    /**
     * @param predictorId the predictor id
     * @return {@code true} if the predictor currently holds a belief; always {@code false} if
     *         the id is not registered
     */
    boolean isPending(String predictorId);

    /**
     * The C7.9 step-5 self-test: re-verify every registered predictor against the current
     * pole.
     *
     * @throws PredictionRegistrationException if any registered predictor is now
     *                                          unsatisfiable
     */
    void assertAllSatisfied();

    /** The C1.2 capability / observability seam, declared locally for the same reason as {@link ServerSignal}. */
    interface SignalObservability {

        /**
         * @param signal the signal a predictor names as its falsifier
         * @return whether this pole's facade can actually observe that signal
         */
        boolean canObserve(ServerSignal signal);

        /**
         * @return an observability that sees every signal, for a pole whose facade implements
         *         all of C1.5
         */
        static SignalObservability all() {
            return new SignalObservability() {
                @Override
                public boolean canObserve(ServerSignal signal) {
                    return true;
                }
            };
        }
    }

    /** The module-identity seam, declared locally for the same reason. */
    interface OwnerTierLookup {

        /**
         * @param moduleId the predictor's id, which is also its owning module's id
         * @return the owning module's tier, or empty if no such module is registered
         */
        java.util.Optional<ComplianceTier> tierOf(String moduleId);
    }

    /**
     * The default registry. It enforces all six conditions and holds a flag per predictor
     * that the owner module maintains - {@code beginPrediction} and {@code releasePrediction}
     * are the owner's half of the loop, and {@link #isPending(String)} reads the result.
     */
    final class Default implements PredictorRegistry {

        private final Map<String, Predictor> registered = new LinkedHashMap<String, Predictor>();
        private final Set<String> pending = new LinkedHashSet<String>();
        private final OwnerTierLookup ownerTiers;
        private final SignalObservability observability;

        /**
         * @param ownerTiers    resolves a module id to its compliance tier
         * @param observability reports whether this pole can observe a given signal
         * @throws XsozContractException if either is {@code null}
         */
        public Default(OwnerTierLookup ownerTiers, SignalObservability observability) {
            if (ownerTiers == null) {
                throw new XsozContractException("PredictorRegistry needs an owner-tier lookup.");
            }
            if (observability == null) {
                throw new XsozContractException("PredictorRegistry needs a signal-observability view.");
            }
            this.ownerTiers = ownerTiers;
            this.observability = observability;
        }

        /** @return a registry that refuses nothing observable and knows no owners, for tests */
        public static Default empty() {
            return new Default(new OwnerTierLookup() {
                @Override
                public java.util.Optional<ComplianceTier> tierOf(String moduleId) {
                    return java.util.Optional.of(ComplianceTier.B);
                }
            }, SignalObservability.all());
        }

        @Override
        public void register(Predictor predictor) {
            if (predictor == null) {
                throw new XsozContractException("PredictorRegistry.register requires a predictor.");
            }
            String id = requireModuleId(predictor);
            if (registered.containsKey(id)) {
                throw new XsozContractException(
                        "Predictor \"" + id + "\" is already registered. Two predictors for one "
                                + "module means two beliefs about the same server state, and only one "
                                + "of them will be released when the falsifier arrives.");
            }
            check(predictor);
            registered.put(id, predictor);
        }

        @Override
        public List<Predictor> all() {
            return Collections.unmodifiableList(new ArrayList<Predictor>(registered.values()));
        }

        @Override
        public boolean isPending(String predictorId) {
            return predictorId != null && pending.contains(predictorId);
        }

        @Override
        public void assertAllSatisfied() {
            for (Predictor predictor : registered.values()) {
                check(predictor);
            }
        }

        /**
         * Records that a predictor is currently holding a belief. The owner calls this when it
         * predicts; the falsifier or the timeout must clear it within one tick.
         *
         * @param predictorId the predictor id
         * @return {@code true} if the id is a registered predictor
         */
        public boolean beginPrediction(String predictorId) {
            if (predictorId == null || !registered.containsKey(predictorId)) {
                return false;
            }
            pending.add(predictorId);
            return true;
        }

        /**
         * Clears a predictor's belief, the registry's half of the yield path. Called after the
         * owner's {@code yield} or {@code yieldOnTimeout} has run.
         *
         * @param predictorId the predictor id
         * @return {@code true} if the predictor had a belief to release
         */
        public boolean releasePrediction(String predictorId) {
            return predictorId != null && pending.remove(predictorId);
        }

        /** @return the number of predictors currently holding a belief */
        public int pendingCount() {
            return pending.size();
        }

        private static String requireModuleId(Predictor predictor) {
            String id = predictor.id();
            if (id == null || id.trim().isEmpty()) {
                throw new XsozContractException(
                        "A Predictor must carry a stable id equal to its owning module's id "
                                + "(contracts.md C7.7, C7.8).");
            }
            if (!ModuleId.isValid(id)) {
                throw new XsozContractException(
                        "\"" + id + "\" is not a valid module id. contracts.md C7.7: the grammar is "
                                + ModuleId.GRAMMAR + ".");
            }
            return id;
        }

        /**
         * The six refusals, in the contract's order. Each throws with the number and the
         * reason, because "registration refused" without a reason is the silent failure the
         * whole gate exists to prevent.
         *
         * @param predictor the candidate
         * @throws PredictionRegistrationException on any of the six conditions
         */
        private void check(Predictor predictor) {
            String id = predictor.id();

            // 1. localAction() null or not in the closed enum. The enum half does not compile;
            //    the null half is caught here.
            if (predictor.localAction() == null) {
                throw new PredictionRegistrationException(
                        "Predictor \"" + id + "\" refused, condition 1: localAction() is null. contracts.md "
                                + "C7.8: a prediction feature must state what the client does differently, "
                                + "and the answer must be one of the five PredictionAction constants. "
                                + "There is no constant for a mechanics change, so a mechanics change "
                                + "does not compile - and a null gets past the compiler, so it is caught "
                                + "here.");
            }

            // 2. falsifiedBy() null.
            if (predictor.falsifiedBy() == null) {
                throw new PredictionRegistrationException(
                        "Predictor \"" + id + "\" refused, condition 2: falsifiedBy() is null. A "
                                + "prediction that names no server signal cannot be shown to be false, "
                                + "and an unfalsifiable prediction is a permanent desync generator.");
            }

            // 3. the facade cannot observe the falsifier on this pole.
            ServerSignal falsifier = predictor.falsifiedBy();
            if (!observability.canObserve(falsifier)) {
                throw new PredictionRegistrationException(
                        "Predictor \"" + id + "\" refused, condition 3: this pole cannot observe "
                                + falsifier + ", the signal the feature claims falsifies it. A "
                                + "prediction that cannot be falsified here is not falsifiable, and a "
                                + "feature that names a falsifier it can never see is naming a lie.");
            }

            // 4. timeout out of range. Unbounded is refused.
            int timeout = predictor.timeoutMillis();
            if (timeout < Predictor.MIN_TIMEOUT_MILLIS || timeout > Predictor.MAX_TIMEOUT_MILLIS) {
                throw new PredictionRegistrationException(
                        "Predictor \"" + id + "\" refused, condition 4: timeoutMillis() is " + timeout
                                + ", outside the permitted " + Predictor.MIN_TIMEOUT_MILLIS + ".."
                                + Predictor.MAX_TIMEOUT_MILLIS + " ms. A prediction with no finite bound "
                                + "holds its belief until something else stops it, which is the shape "
                                + "the Consumable-Optimizer class has.");
            }

            // 5. addsNoPacket() must be true.
            if (!predictor.addsNoPacket()) {
                throw new PredictionRegistrationException(
                        "Predictor \"" + id + "\" refused, condition 5: addsNoPacket() returned false. "
                                + "A feature that adds, removes, reorders, delays or alters a packet is "
                                + "on the wrong side of the round trip, and by docs/anticheat-risk.md 7.0 "
                                + "that is the banned half. This is Tier C behaviour wearing a "
                                + "prediction's name.");
            }

            // 6. the owning module is not Tier B.
            ComplianceTier ownerTier = ownerTiers.tierOf(id).orElse(null);
            if (ownerTier == null) {
                throw new PredictionRegistrationException(
                        "Predictor \"" + id + "\" refused, condition 6: no registered module owns that "
                                + "id. contracts.md C7.7: a module id and a compliance feature id are the "
                                + "same string, and C7.8: the owner must be Tier B.");
            }
            if (ownerTier != ComplianceTier.B) {
                throw new PredictionRegistrationException(
                        "Predictor \"" + id + "\" refused, condition 6: the owning module is Tier "
                                + ownerTier + ". contracts.md C7.8: a Predictor on a Tier A module is "
                                + "refused - Tier A is legal everywhere studied, which means it does not "
                                + "act on client/server divergence at all. Registering a prediction here "
                                + "would be a claim the tier contradicts.");
            }
        }
    }
}
