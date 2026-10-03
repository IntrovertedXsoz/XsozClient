package dev.xsoz.core.compliance;

/**
 * A feature that predicts a decision the server has already made (contracts.md C7.8).
 *
 * <p><strong>Predict the server; never override it. Yield = legal. Fight = banned.</strong>
 * A principle in a README is worth nothing, so this is a registration gate: a class that
 * implements this interface cannot be registered unless it answers four questions, and its
 * owner is Tier B and it adds no packet.</p>
 *
 * <p>The four questions, and what each absence means:</p>
 * <ol>
 *   <li>{@link #localAction()} - what the client does differently. There is no constant for a
 *       mechanics change, so a mechanics change does not compile.</li>
 *   <li>{@link #falsifiedBy()} - the server signal that makes the prediction false. A
 *       prediction that cannot be falsified here is a permanent desync generator.</li>
 *   <li>{@link #timeoutMillis()} - a hard upper bound on how long the belief may stay
 *       pending. Unbounded is refused.</li>
 *   <li>{@link #yield(SignalFrame)} / {@link #yieldOnTimeout()} - how the belief is released.
 *       A feature that cannot state how it gives up is fighting by construction.</li>
 * </ol>
 *
 * <p>Plus two standing claims: {@link #addsNoPacket()} is always {@code true} and the owning
 * module is Tier B. A Predictor on a Tier A module is refused, because Tier A is legal
 * everywhere studied, which means it does not act on divergence at all.</p>
 */
public interface Predictor {

    /** The lowest permitted {@link #timeoutMillis()}. Unit: milliseconds. */
    int MIN_TIMEOUT_MILLIS = 1;

    /**
     * The highest permitted {@link #timeoutMillis()}. Unit: milliseconds. The reference shape
     * is Marlow's {@code RELEASE_AFTER = 1500 ms}.
     */
    int MAX_TIMEOUT_MILLIS = 2000;

    /** @return a stable id, equal to the owning module's id (C7.7, one namespace) */
    String id();

    /**
     * (a) The local action. A closed enum: anything not in {@link PredictionAction} does not
     * compile.
     *
     * @return the local action
     */
    PredictionAction localAction();

    /**
     * (b) The server signal that FALSIFIES this prediction. Must be non-null and must be
     * observable by this pole's facade, or registration is refused.
     *
     * @return the falsifying signal
     */
    ServerSignal falsifiedBy();

    /**
     * (c) A hard upper bound on how long the prediction may stay pending, in
     * {@code 1..2000} milliseconds. Finite and bounded, always.
     *
     * @return the timeout in milliseconds
     */
    int timeoutMillis();

    /**
     * (d) The yield path. Must release the belief, and must complete within one tick of being
     * called.
     *
     * @param frame the falsifying signal frame
     */
    void yield(SignalFrame frame);

    /**
     * (d, continued) The timeout path. Must release the belief, and is identical in effect to
     * {@link #yield(SignalFrame)}.
     */
    void yieldOnTimeout();

    /**
     * Always returns {@code true}. Exists so the "adds no packet" property is a stated,
     * testable claim rather than an omission: a {@code Predictor} whose body returned
     * {@code false} is Tier C.
     *
     * @return whether this feature adds, removes, reorders, delays or alters a packet
     */
    boolean addsNoPacket();
}
