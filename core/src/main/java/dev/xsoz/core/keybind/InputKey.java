package dev.xsoz.core.keybind;

import dev.xsoz.core.XsozContractException;

import java.util.Objects;

/**
 * One physical key: a keyboard key or a mouse button (contracts.md C1.4, C7.12).
 *
 * <p><strong>This is the C7.12-local value type</strong>, declared here rather than imported
 * from the C1 platform package, which another agent owns. It is dependency-free on purpose:
 * the keybind validator is the one piece of the product a player can trigger from a keybind
 * editor with no game loaded, and it must not be able to reach anything.</p>
 *
 * <p><strong>Equality is on {@code (kind, code)} only.</strong> {@link #displayName()} is a
 * UI string: it changes when a translation changes, and a bind whose identity moved because
 * a label changed is a bind that silently duplicates.</p>
 *
 * <p><strong>There is no "unbound" key.</strong> An unbound action is
 * {@link Keybind#unbound()}, whose {@code key()} is {@code null} - the one place null is
 * deliberate, because "bound to the unknown key" and "not bound at all" are the same fact
 * and the type says so by having no key rather than a sentinel one (contracts.md 0.4).</p>
 *
 * <p>Deeply immutable.</p>
 */
public final class InputKey {

    private final InputKeyKind kind;
    private final int code;
    private final String displayName;

    private InputKey(InputKeyKind kind, int code, String displayName) {
        this.kind = kind;
        this.code = code;
        this.displayName = displayName;
    }

    /**
     * @param code        the keyboard key code; must be non-negative
     * @param displayName the label the keybind editor shows, e.g. {@code "R"}; non-blank
     * @return a keyboard key
     * @throws XsozContractException if {@code code} is negative or the name is blank
     */
    public static InputKey keyboard(int code, String displayName) {
        if (code < 0) {
            throw new XsozContractException(
                    "A keyboard key code must be non-negative. Got " + code + ".");
        }
        return new InputKey(InputKeyKind.KEYBOARD, code, requireName(displayName, "keyboard key"));
    }

    /**
     * @param button      the mouse button number, 1..N counting from the left; must be
     *                    positive
     * @param displayName the label the keybind editor shows, e.g. {@code "Mouse4"}; non-blank
     * @return a mouse button
     * @throws XsozContractException if {@code button} is not positive or the name is blank
     */
    public static InputKey mouse(int button, String displayName) {
        if (button < 1) {
            throw new XsozContractException(
                    "A mouse button number starts at 1, counting from the left. Got " + button
                            + ". 0 is not a usable sentinel for \"no mouse key\" (contracts.md 0.4).");
        }
        return new InputKey(InputKeyKind.MOUSE, button, requireName(displayName, "mouse button"));
    }

    /** @return whether this is a keyboard key or a mouse button */
    public InputKeyKind kind() {
        return kind;
    }

    /** @return the key code, or the mouse button number */
    public int code() {
        return code;
    }

    /** @return the UI label; never part of equality */
    public String displayName() {
        return displayName;
    }

    /** @return {@code true} if this is a physical mouse button */
    public boolean isMouseButton() {
        return kind == InputKeyKind.MOUSE;
    }

    private static String requireName(String displayName, String what) {
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new XsozContractException(
                    "A " + what + " needs a non-blank display name. The keybind editor shows this "
                            + "string, and a blank row is a row the player cannot undo.");
        }
        return displayName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof InputKey)) {
            return false;
        }
        InputKey that = (InputKey) other;
        return code == that.code && kind == that.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, code);
    }

    @Override
    public String toString() {
        return displayName + "(" + kind + ":" + code + ")";
    }
}
