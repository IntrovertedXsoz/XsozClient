package dev.xsoz.core.config;

/**
 * Where a HUD element hangs (contracts.md C5.2, the {@code anchor} field).
 *
 * <p>Closed enum of the nine anchors, matching the names C5.2's example uses. Offsets
 * are applied from the anchor, so a caller does not need to know the framebuffer size
 * to place an element.</p>
 */
public enum HudAnchor {

    /** Top edge, left third. */
    TOP_LEFT,

    /** Top edge, centre. */
    TOP_CENTER,

    /** Top edge, right third. */
    TOP_RIGHT,

    /** Vertical centre, left third. */
    CENTER_LEFT,

    /** Dead centre. */
    CENTER,

    /** Vertical centre, right third. */
    CENTER_RIGHT,

    /** Bottom edge, left third. */
    BOTTOM_LEFT,

    /** Bottom edge, centre. */
    BOTTOM_CENTER,

    /** Bottom edge, right third. */
    BOTTOM_RIGHT
}
