package dev.xsoz.core.compliance;

/**
 * The compliance tier of a feature (contracts.md C7.1).
 *
 * <p>This is a closed enum. Adding a constant is a change request and requires a
 * {@code docs/rules-matrix.md} amendment, because the tier is not an internal detail -
 * it is the enforcement rule the whole product is judged by.</p>
 */
public enum ComplianceTier {

    /**
     * Legal everywhere studied: performance, visual clarity, own-state HUD, cosmetics,
     * keybinds that stay vanilla-semantic.
     *
     * <p><strong>Ships ON by default.</strong> Present in the {@code ALLOWED} set of every
     * bundled profile.</p>
     */
    A,

    /**
     * Allowlisted by at least one network but contested elsewhere, or reliant on
     * client/server divergence.
     *
     * <p><strong>Ships DEFAULT-OFF</strong>, per-server profile, and honours the
     * {@code xsoz:*} server opt-out protocol from day one. {@link Verdict#CONTESTED} is
     * surfaced in the UI and never silently resolved.</p>
     */
    B,

    /**
     * Automates, manipulates ping/movement/reach, reveals opponents, or alters PvP
     * interaction: ESP, radar, reach, auto-clickers, auto-totem, click crystals, x-ray,
     * freecam, and the rest.
     *
     * <p><strong>NEVER COMPILED INTO THE PRODUCT.</strong> Not "disabled" - absent from
     * the binary, absent from the config schema, absent from the capability list we
     * advertise to servers, and covered by a test that greps for the identifiers.
     * A disabled ESP module living in the jar is a liability: CubeCraft bans clients
     * with cheat modules even if the player never uses them.</p>
     */
    C
}
