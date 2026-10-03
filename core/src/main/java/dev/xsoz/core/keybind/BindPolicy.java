package dev.xsoz.core.keybind;

/**
 * The keybind validator (contracts.md C7.12).
 *
 * <p><strong>This is the user's own hard rule, and it is enforced in code rather than by
 * omitting the option from the UI.</strong> The client must never permit rebinding an
 * attack/click action to a non-mouse key, a multi-key bind, a double bind, or a null bind.
 * "Just a QoL rebind" is the most plausible bad commit in the entire history of the project
 * ({@code docs/rules-matrix.md} 4), so the boundary is a validator every load runs through,
 * not a UI omission.</p>
 *
 * <p>The source: "Internal or external modifications that allow abnormal keybinding or
 * double-binding (except standard mouse-required bindings)" - PvPHQ, and "Double Key Binds"
 * at PvP Land. {@link #validate} never throws for a bad bind; a bad bind is a user-facing
 * state and user-facing states are values (contracts.md 0.4).</p>
 */
public interface BindPolicy {

    /**
     * @param bind the single bind being installed, possibly {@code null}
     * @param req  what the editor is asking for
     * @return the verdict; {@link BindVerdict#allowed()} is the whole answer
     */
    BindVerdict validate(Keybind bind, BindRequest req);
}
