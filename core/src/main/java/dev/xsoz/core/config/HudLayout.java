package dev.xsoz.core.config;

import dev.xsoz.core.XsozContractException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The HUD scale and the ordered element list (contracts.md C5.2, the {@code hud} object).
 *
 * <p><strong>Elements are stored sorted by {@code (order, id)}</strong>, so two layouts
 * that mean the same thing iterate in the same order and a diff of two saved layouts
 * shows a real change rather than a reshuffle.</p>
 *
 * <p>Deeply immutable.</p>
 */
public final class HudLayout {

    /** The scale a fresh layout starts at. Unit: dimensionless ratio. */
    public static final double DEFAULT_SCALE = 1.0d;

    /** The lowest legal scale. Unit: dimensionless ratio. */
    public static final double MIN_SCALE = 0.5d;

    /** The highest legal scale. Unit: dimensionless ratio. */
    public static final double MAX_SCALE = 4.0d;

    private static final Comparator<HudElement> BY_ORDER_THEN_ID =
            new Comparator<HudElement>() {
                @Override
                public int compare(HudElement left, HudElement right) {
                    int byOrder = Integer.compare(left.order(), right.order());
                    return byOrder != 0 ? byOrder : left.id().compareTo(right.id());
                }
            };

    private final double scale;
    private final List<HudElement> elements;

    private HudLayout(double scale, List<HudElement> elements) {
        this.scale = scale;
        this.elements = Collections.unmodifiableList(new ArrayList<HudElement>(elements));
    }

    /**
     * @param scale    the interface scale. Unit: dimensionless ratio
     * @param elements the elements; defensively copied and sorted by {@code (order, id)}
     * @return the layout
     * @throws XsozContractException if the scale is outside
     *                                [{@value #MIN_SCALE}, {@value #MAX_SCALE}], an
     *                                element is {@code null}, or two elements share an id
     */
    public static HudLayout of(double scale, List<HudElement> elements) {
        if (elements == null) {
            throw new XsozContractException(
                    "A HUD layout needs an element list, possibly empty. null is not a list.");
        }
        if (Double.isNaN(scale) || Double.isInfinite(scale)
                || scale < MIN_SCALE || scale > MAX_SCALE) {
            throw new XsozContractException(
                    "HUD scale must be within [" + MIN_SCALE + ", " + MAX_SCALE + "], a "
                            + "dimensionless ratio; was " + scale + ".");
        }
        List<HudElement> sorted = new ArrayList<HudElement>(elements);
        for (HudElement element : sorted) {
            if (element == null) {
                throw new XsozContractException(
                        "A HUD layout cannot hold a null element. A hole in the list is a hole on "
                                + "the screen.");
            }
        }
        Collections.sort(sorted, BY_ORDER_THEN_ID);
        Set<String> ids = new LinkedHashSet<String>();
        for (HudElement element : sorted) {
            if (!ids.add(element.id())) {
                throw new XsozContractException(
                        "HUD element \"" + element.id() + "\" appears twice in one layout. An "
                                + "element id appears once, and a duplicate is a row the player "
                                + "can move but not delete.");
            }
        }
        return new HudLayout(scale, sorted);
    }

    /** @return a layout with the default scale and no elements */
    public static HudLayout empty() {
        return new HudLayout(DEFAULT_SCALE, Collections.<HudElement>emptyList());
    }

    /** @return the interface scale. Unit: dimensionless ratio. */
    public double scale() {
        return scale;
    }

    /** @return the elements, sorted by {@code (order, id)}, unmodifiable */
    public List<HudElement> elements() {
        return elements;
    }

    /**
     * @param element the element to add or replace
     * @return a copy with the element placed, replacing any element with the same id
     */
    public HudLayout with(HudElement element) {
        List<HudElement> next = new ArrayList<HudElement>();
        boolean replaced = false;
        for (HudElement existing : elements) {
            if (existing.id().equals(element.id())) {
                next.add(element);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) {
            next.add(element);
        }
        return of(scale, next);
    }

    /**
     * @param id the element id to remove
     * @return a copy without that element
     */
    public HudLayout without(String id) {
        List<HudElement> next = new ArrayList<HudElement>();
        for (HudElement existing : elements) {
            if (!existing.id().equals(id)) {
                next.add(existing);
            }
        }
        return of(scale, next);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof HudLayout)) {
            return false;
        }
        HudLayout that = (HudLayout) other;
        return Double.compare(scale, that.scale) == 0 && elements.equals(that.elements);
    }

    @Override
    public int hashCode() {
        return 31 * Double.valueOf(scale).hashCode() + elements.hashCode();
    }

    @Override
    public String toString() {
        return "HudLayout[scale=" + scale + ", " + elements.size() + " elements]";
    }
}
