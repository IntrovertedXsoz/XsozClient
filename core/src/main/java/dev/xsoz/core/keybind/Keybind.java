package dev.xsoz.core.keybind;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One action's bind: exactly one key, plus any held modifiers (contracts.md C7.12).
 *
 * <p><strong>There is no representation of a multi-key bind.</strong> That is the point: a
 * {@code Keybind} holds one {@link InputKey}, so {@code MULTI_KEY_BIND} is a rejection of the
 * <em>request</em> - a list of length two or more - and not a state the running client can be
 * put into. A macro has nowhere to live.</p>
 *
 * <p>An unbound action is {@link #unbound()}, whose {@link #key()} is {@code null}. That is
 * the single deliberate null in the keybind package, and it is semantic: "bound to the
 * unknown / unbound key" and "not bound at all" are the same fact.</p>
 *
 * <p>Deeply immutable: final fields, defensive copy of the modifier set, unmodifiable
 * accessor.</p>
 */
public final class Keybind {

    private static final Keybind UNBOUND = new Keybind(null, Collections.<ModifierKey>emptySet());

    private final InputKey key;
    private final Set<ModifierKey> modifiers;

    private Keybind(InputKey key, Set<ModifierKey> modifiers) {
        this.key = key;
        this.modifiers = modifiers;
    }

    /**
     * @param key the key; must not be {@code null} (use {@link #unbound()} for "not bound")
     * @return a bind with no modifiers
     * @throws NullPointerException if {@code key} is {@code null}; an unbound action is
     *                              {@link #unbound()}, not a bind with a null key
     */
    public static Keybind of(InputKey key) {
        Objects.requireNonNull(key, "Use Keybind.unbound() for an unbound action.");
        return new Keybind(key, Collections.<ModifierKey>emptySet());
    }

    /**
     * @param key       the key; must not be {@code null}
     * @param modifiers the held modifiers; defensively copied, duplicates removed
     * @return a bind with modifiers, which is a chord
     * @throws NullPointerException if {@code key} or any modifier is {@code null}
     */
    public static Keybind of(InputKey key, ModifierKey... modifiers) {
        Objects.requireNonNull(key, "Use Keybind.unbound() for an unbound action.");
        Objects.requireNonNull(modifiers, "modifiers");
        if (modifiers.length == 0) {
            return new Keybind(key, Collections.<ModifierKey>emptySet());
        }
        Set<ModifierKey> copy = EnumSet.noneOf(ModifierKey.class);
        for (ModifierKey modifier : modifiers) {
            copy.add(Objects.requireNonNull(modifier, "modifier"));
        }
        return new Keybind(key, Collections.unmodifiableSet(copy));
    }

    /**
     * @param key       the key; must not be {@code null}
     * @param modifiers the held modifiers; defensively copied, duplicates removed
     * @return a bind with modifiers
     * @throws NullPointerException if {@code key} or any modifier is {@code null}
     */
    public static Keybind of(InputKey key, Collection<ModifierKey> modifiers) {
        Objects.requireNonNull(key, "Use Keybind.unbound() for an unbound action.");
        Objects.requireNonNull(modifiers, "modifiers");
        if (modifiers.isEmpty()) {
            return new Keybind(key, Collections.<ModifierKey>emptySet());
        }
        Set<ModifierKey> copy = EnumSet.noneOf(ModifierKey.class);
        for (ModifierKey modifier : modifiers) {
            copy.add(Objects.requireNonNull(modifier, "modifier"));
        }
        return new Keybind(key, Collections.unmodifiableSet(copy));
    }

    /**
     * @return the shared unbound bind: no key, no modifiers. {@code NULL_BIND} is what the
     *         policy returns for it, so this value can never be installed.
     */
    public static Keybind unbound() {
        return UNBOUND;
    }

    /** @return the key, or {@code null} for {@link #unbound()} */
    public InputKey key() {
        return key;
    }

    /** @return the held modifiers, unmodifiable and possibly empty */
    public Set<ModifierKey> modifiers() {
        return modifiers;
    }

    /** @return {@code true} when this bind names a real key */
    public boolean isBound() {
        return key != null;
    }

    /**
     * @return {@code true} when this bind is a single unmodified key. A bind with a modifier
     *         is a chord, and a chord on a combat action is {@code KEY_SEQUENCE_BIND}.
     */
    public boolean isSingleKey() {
        return key != null && modifiers.isEmpty();
    }

    /** @return {@code true} when the key is a physical mouse button */
    public boolean isMouseButton() {
        return key != null && key.isMouseButton();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Keybind)) {
            return false;
        }
        Keybind that = (Keybind) other;
        return Objects.equals(key, that.key) && modifiers.equals(that.modifiers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, modifiers);
    }

    @Override
    public String toString() {
        if (key == null) {
            return "Keybind[UNBOUND]";
        }
        StringBuilder out = new StringBuilder("Keybind[");
        for (ModifierKey modifier : modifiers) {
            out.append(modifier).append('+');
        }
        return out.append(key.displayName()).append(']').toString();
    }
}
