package dev.xsoz.core.compliance;

/**
 * The closed set of local actions a prediction feature is permitted to take
 * (contracts.md C7.8).
 *
 * <p><strong>The absence of a constant is the enforcement.</strong> There is no
 * {@code DELAY_PACKET}, no {@code SUPPRESS_USE_STATE}, no {@code ALTER_PLACEMENT}, no
 * {@code ALTER_TICK_RATE}, and there is no "mechanics" constant of any kind. A feature whose
 * local action is not one of these <strong>does not compile</strong>, which is a stronger
 * guarantee than a review checklist.</p>
 *
 * <p>Every constant here describes something that changes only what the client displays or
 * what it believes locally. None of them changes the number, order, timing or content of a
 * packet, and none of them discards one the server sent.</p>
 */
public enum PredictionAction {

    /** Stop drawing it. Nothing else changes. */
    HIDE_ENTITY_FROM_RENDER,

    /** Stop it participating in LOCAL targeting. */
    HIDE_ENTITY_FROM_CROSSHAIR_TARGET,

    /** Swap a LOCAL blockstate for a replaceable ghost. */
    REPLACE_LOCAL_BLOCK_WITH_GHOST,

    /**
     * LOCAL only. Must not change what the server thinks is placed - asserted by
     * {@code AnchorGhostIsReplaceableTest}.
     */
    HIDE_BLOCK_FROM_LOCAL_COLLISION,

    /** Hide a HUD element, change nothing else. */
    SUPPRESS_LOCAL_COOLDOWN_OVERLAY
}
