package dev.xsoz.core.keybind;

/**
 * Whether a key is a keyboard key or a mouse button (contracts.md C1.4 {@code InputKeyKind}).
 *
 * <p>Closed enum, mirrored locally for the same reason as {@link InputKey}: C1 lives in the
 * platform package, owned by another agent. The names are frozen by C1.4.</p>
 */
public enum InputKeyKind {

    /** A key on the keyboard. */
    KEYBOARD,

    /** A physical mouse button, numbered 1..N from the left. */
    MOUSE
}
