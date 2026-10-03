package dev.xsoz.core.compliance;

/**
 * The resolved verdict on one feature under one ruleset (contracts.md C7.1).
 *
 * <p>Verdict values and their meanings are copied verbatim from
 * {@code docs/rules-matrix.md}. <strong>Adding a value is a change request</strong> and
 * requires a matrix amendment.</p>
 *
 * <p>A {@code Citation} is never carried without its {@code retrievedOn} date. A verdict
 * without a date is a lie about how fresh it is.</p>
 */
public enum Verdict {

    /** Permitted by every ruleset studied, no condition. */
    ALLOWED,

    /** Permitted under a stated condition, which is written in full. */
    CONDITIONAL,

    /**
     * ALLOWED on one network, DISALLOWED or UNSUPPORTED on another.
     *
     * <p><strong>BOTH verdicts are shown. NEVER resolved silently.</strong></p>
     */
    CONTESTED,

    /** In an "at your own risk" tier; requires an explicit acknowledged opt-in. */
    OWN_RISK,

    /** Banned. Terminal. */
    DENIED,

    /** No source found. Excluded until confirmed. */
    UNVERIFIED
}
