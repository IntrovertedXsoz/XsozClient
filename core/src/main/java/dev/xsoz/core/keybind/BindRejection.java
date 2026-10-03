package dev.xsoz.core.keybind;

/**
 * Why a bind request was refused (contracts.md C7.12).
 *
 * <p>Closed enum, verbatim from the contract. Each constant has a named test in
 * {@code docs/rules-matrix.md} 4.</p>
 */
public enum BindRejection {

    /** The action is bound to the "unknown / unbound" key. */
    NULL_BIND,

    /** More than one key for one action. */
    MULTI_KEY_BIND,

    /** The same key is held by a second action. */
    DOUBLE_BIND,

    /** attack / use bound to a non-mouse key. */
    COMBAT_ACTION_ON_KEYBOARD,

    /** attack / use / pickBlock / drop moved off its vanilla key. */
    COMBAT_ACTION_REBOUND,

    /** A chord / macro bound to a combat action. */
    KEY_SEQUENCE_BIND,

    /** Not in the action registry. */
    UNKNOWN_ACTION
}
