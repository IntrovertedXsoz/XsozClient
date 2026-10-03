package dev.xsoz.core.keybind;

/**
 * A held modifier (contracts.md C1.4 {@code InputModifiers}, C7.12 {@code Keybind}).
 *
 * <p>Closed enum. A modifier is what makes a bind a <em>chord</em>, and a chord on a combat
 * action is rejected by {@code BindPolicy} as {@link BindRejection#KEY_SEQUENCE_BIND}.</p>
 */
public enum ModifierKey {

    /** Either shift key. */
    SHIFT,

    /** Either control key. */
    CTRL,

    /** Either alt key. */
    ALT,

    /** The super / command / windows key. */
    SUPER
}
