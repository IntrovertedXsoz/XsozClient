package dev.xsoz.core.config;

import dev.xsoz.core.XsozContractException;

import java.util.Locale;

/**
 * One HUD element's placement (contracts.md C5.2, {@code hud.elements[]} entries).
 *
 * <p>Field contracts from C5.2, enforced here rather than left to the UI:</p>
 * <ul>
 *   <li>{@code z} in 0..1000 and {@code order} in 0..9999. Both are bounded because an
 *       unbounded z-index is how one element ends up under the crosshair forever.</li>
 *   <li>{@code id} is a module id. An id the client does not know is dropped by the
 *       loader and reported (C5.2), because an element that draws nothing is a
 *       permanently broken HUD row with no way to remove it in the UI.</li>
 *   <li>The offsets are in GUI-scaled pixels ({@code Gu}), not real framebuffer pixels,
 *       so a layout survives a GUI-scale change. Unit: Gu.</li>
 * </ul>
 *
 * <p>Deeply immutable.</p>
 */
public final class HudElement {

    /** Lowest legal z index. Unit: dimensionless ordering slot. */
    public static final int MIN_Z = 0;

    /** Highest legal z index. Unit: dimensionless ordering slot. */
    public static final int MAX_Z = 1000;

    /** Lowest legal draw order. Unit: dimensionless ordering slot. */
    public static final int MIN_ORDER = 0;

    /** Highest legal draw order. Unit: dimensionless ordering slot. */
    public static final int MAX_ORDER = 9999;

    private final String id;
    private final HudAnchor anchor;
    private final double offsetXGu;
    private final double offsetYGu;
    private final int z;
    private final int order;
    private final boolean visible;

    private HudElement(String id, HudAnchor anchor, double offsetXGu, double offsetYGu,
                       int z, int order, boolean visible) {
        this.id = id;
        this.anchor = anchor;
        this.offsetXGu = offsetXGu;
        this.offsetYGu = offsetYGu;
        this.z = z;
        this.order = order;
        this.visible = visible;
    }

    /**
     * @param id        a module id implementing the HUD element provider
     * @param anchor    the anchor, non-null
     * @param offsetXGu horizontal offset from the anchor. Unit: GUI-scaled pixels
     * @param offsetYGu vertical offset from the anchor. Unit: GUI-scaled pixels
     * @param z         depth, within [0, 1000]
     * @param order     draw order, within [0, 9999]
     * @param visible   whether the element is drawn
     * @return the element
     * @throws XsozContractException if any field is outside its declared range
     */
    public static HudElement of(String id, HudAnchor anchor, double offsetXGu, double offsetYGu,
                                int z, int order, boolean visible) {
        if (id == null || id.trim().isEmpty()) {
            throw new XsozContractException(
                    "A HUD element needs the id of the module that draws it. An element with no "
                            + "owner is a rectangle nobody fills.");
        }
        if (anchor == null) {
            throw new XsozContractException("HUD element \"" + id + "\" needs an anchor.");
        }
        if (z < MIN_Z || z > MAX_Z) {
            throw new XsozContractException(
                    "HUD element \"" + id + "\" has z " + z + ", outside the legal range ["
                            + MIN_Z + ", " + MAX_Z + "] (C5.2).");
        }
        if (order < MIN_ORDER || order > MAX_ORDER) {
            throw new XsozContractException(
                    "HUD element \"" + id + "\" has order " + order + ", outside the legal range ["
                            + MIN_ORDER + ", " + MAX_ORDER + "] (C5.2).");
        }
        if (Double.isNaN(offsetXGu) || Double.isInfinite(offsetXGu)
                || Double.isNaN(offsetYGu) || Double.isInfinite(offsetYGu)) {
            throw new XsozContractException(
                    "HUD element \"" + id + "\" has a non-finite offset. Unit: GUI-scaled pixels.");
        }
        return new HudElement(id.trim(), anchor, offsetXGu, offsetYGu, z, order, visible);
    }

    /** @return the owning module id */
    public String id() {
        return id;
    }

    /** @return the anchor */
    public HudAnchor anchor() {
        return anchor;
    }

    /** @return the horizontal offset from the anchor. Unit: GUI-scaled pixels. */
    public double offsetXGu() {
        return offsetXGu;
    }

    /** @return the vertical offset from the anchor. Unit: GUI-scaled pixels. */
    public double offsetYGu() {
        return offsetYGu;
    }

    /** @return the depth, within [0, 1000] */
    public int z() {
        return z;
    }

    /** @return the draw order, within [0, 9999] */
    public int order() {
        return order;
    }

    /** @return whether the element is drawn */
    public boolean visible() {
        return visible;
    }

    /**
     * @param newVisible whether the element should be drawn
     * @return a copy with only {@code visible} changed
     */
    public HudElement withVisible(boolean newVisible) {
        return new HudElement(id, anchor, offsetXGu, offsetYGu, z, order, newVisible);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof HudElement)) {
            return false;
        }
        HudElement that = (HudElement) other;
        return z == that.z
                && order == that.order
                && visible == that.visible
                && Double.compare(offsetXGu, that.offsetXGu) == 0
                && Double.compare(offsetYGu, that.offsetYGu) == 0
                && id.equals(that.id)
                && anchor == that.anchor;
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + anchor.hashCode();
        long bits = Double.doubleToLongBits(offsetXGu);
        result = 31 * result + (int) (bits ^ (bits >>> 32));
        bits = Double.doubleToLongBits(offsetYGu);
        result = 31 * result + (int) (bits ^ (bits >>> 32));
        result = 31 * result + z;
        result = 31 * result + order;
        return 31 * result + Boolean.valueOf(visible).hashCode();
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "HudElement[%s at %s offset=(%.1f, %.1f) Gu, z=%d, order=%d, visible=%s]",
                id, anchor, offsetXGu, offsetYGu, z, order, visible);
    }
}
