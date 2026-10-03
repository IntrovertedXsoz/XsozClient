package dev.xsoz.core.compliance;

/**
 * Where a {@link Decision} came from (contracts.md C7.3).
 *
 * <p>Closed enum, verbatim from the contract. The purpose of the set is diagnostic: a
 * player looking at a refused feature needs to know <em>which rule refused it</em>, and
 * "DENIED" alone cannot tell them whether it was the profile, the server, or a missing
 * capability on their game version.</p>
 */
public enum DecisionSource {

    /** The resolved profile's row is ALLOWED and nothing overrode it. */
    PROFILE_ALLOWED,

    /** The row is CONDITIONAL and every stated condition currently holds. */
    PROFILE_CONDITIONAL,

    /** The row is CONTESTED. Both source verdicts are shown and neither is resolved silently. */
    PROFILE_CONTESTED,

    /** The row is OWN_RISK and an explicit acknowledgement is on record for this join. */
    PROFILE_OWN_RISK,

    /** The row is DENIED, the row is absent, or the feature is not compiled. Terminal. */
    PROFILE_DENIED,

    /** No source was found for this feature. Excluded until confirmed; not permissive. */
    PROFILE_UNVERIFIED,

    /** The feature is Tier C. It is never compiled; this branch exists so a typo cannot allow it. */
    TIER_C_ABSENT,

    /** The build cannot run the feature: a required capability is absent on this pole. */
    CAPABILITY_MISSING,

    /** A server asked us to disable it through the {@code xsoz:opt_out} channel. */
    OPT_OUT,

    /** The feature is not permitted in the current {@link Context}. Inert, not denied. */
    CONTEXT_NOT_ALLOWED,

    /** A staff member cleared a CONTESTED feature, and who/when/with what citation is on record. */
    STAFF_CLEARANCE,

    /** The compliance path itself failed; the feature is disabled rather than trusted. */
    RECOVERY_DISABLED
}
