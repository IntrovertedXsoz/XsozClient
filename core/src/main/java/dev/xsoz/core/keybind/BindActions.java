package dev.xsoz.core.keybind;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The closed action registry and the two closed sets the policy is built on
 * (contracts.md C7.12).
 *
 * <p><strong>These sets are defined in code, not in configuration.</strong> A configurable
 * "which actions are combat actions" list is a file a user edits to make a rebind legal, and
 * the whole point of the validator is that it cannot be edited into permitting an attack
 * rebind.</p>
 *
 * <p>The registry is closed: vanilla binding ids plus ids under our own module-id namespace.
 * Anything else is {@link BindRejection#UNKNOWN_ACTION}, which is a refusal rather than a
 * pass - a validator that allows an action it does not recognise is a validator that has no
 * opinion about the action it was built to police.</p>
 */
public final class BindActions {

    /**
     * Actions that may <strong>only</strong> be bound to a physical mouse button.
     * Violating this is {@link BindRejection#COMBAT_ACTION_ON_KEYBOARD}.
     */
    public static final Set<String> MOUSE_ONLY_ACTIONS = closedSet("attack", "use");

    /**
     * Actions that may not be moved off their vanilla key at all. Violating this is
     * {@link BindRejection#COMBAT_ACTION_REBOUND}.
     */
    public static final Set<String> NO_REMAP_ACTIONS = closedSet("attack", "use", "pickBlock", "drop");

    /**
     * Aliases onto the registry's canonical id. {@code key.use} is documented by the game as
     * "Use Item / Place Block", and both the matrix and the contract spell that one action
     * "use" and "place" in different rows. Aliasing makes the validator treat the two spellings
     * as the single action they are, instead of leaving a spelling that slips through.
     */
    private static final Set<String> ALIASES = closedSet("place");

    /**
     * Vanilla keybind ids the editor is allowed to touch. Anything not here and not under one
     * of our module namespaces is {@link BindRejection#UNKNOWN_ACTION}.
     */
    private static final Set<String> VANILLA_ACTIONS = closedSet(
            "attack", "use", "pickBlock", "drop",
            "forward", "back", "left", "right", "jump", "sneak", "sprint",
            "inventory", "swapOffhand", "dropper", "hotbar.1", "hotbar.2", "hotbar.3",
            "hotbar.4", "hotbar.5", "hotbar.6", "hotbar.7", "hotbar.8", "hotbar.9",
            "command", "socialInteractions", "playerList", "chat", "commandSuggestions",
            "fullscreen", "perspective", "smoothCamera", "toggleTab", "screenshot",
            "toggleFullscreen", "saveToolbarActivator", "loadToolbarActivator", "advancements");

    /** First-segment namespaces our own module ids live under, per the C7.7 module-id grammar. */
    private static final Set<String> XSOZ_NAMESPACES = closedSet(
            "hud", "latency", "perf", "visual", "cosmetic", "qol", "coach", "diag");

    /** The vanilla key each no-remap action is bound to out of the box. */
    private static final String VANILLA_ATTACK = "Mouse1";
    private static final String VANILLA_USE = "Mouse3";
    private static final String VANILLA_PICK_BLOCK = "Mouse2";
    private static final String VANILLA_DROP = "Q";

    private BindActions() {
        throw new AssertionError("BindActions is a closed registry and is not instantiable.");
    }

    /**
     * @param actionId the id, possibly an alias; may be {@code null}
     * @return the canonical action id, or {@code null} if {@code actionId} is {@code null}
     */
    public static String canonical(String actionId) {
        if (actionId == null) {
            return null;
        }
        String trimmed = actionId.trim();
        return ALIASES.contains(trimmed) ? "use" : trimmed;
    }

    /**
     * @param actionId the id, possibly an alias
     * @return {@code true} if the id is in the closed action registry
     */
    public static boolean isKnown(String actionId) {
        String id = canonical(actionId);
        if (id == null || id.isEmpty()) {
            return false;
        }
        if (VANILLA_ACTIONS.contains(id)) {
            return true;
        }
        int dot = id.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        return XSOZ_NAMESPACES.contains(id.substring(0, dot)) && isOurModuleId(id);
    }

    /**
     * @param actionId the id
     * @return {@code true} if the action is in {@link #MOUSE_ONLY_ACTIONS}
     */
    public static boolean isMouseOnlyAction(String actionId) {
        return MOUSE_ONLY_ACTIONS.contains(canonical(actionId));
    }

    /**
     * @param actionId the id
     * @return {@code true} if the action is in {@link #NO_REMAP_ACTIONS}
     */
    public static boolean isNoRemapAction(String actionId) {
        return NO_REMAP_ACTIONS.contains(canonical(actionId));
    }

    /**
     * @param actionId the id
     * @return {@code true} if the action is a combat action, which is the union of the two
     *         closed sets above
     */
    public static boolean isCombatAction(String actionId) {
        return isMouseOnlyAction(actionId) || isNoRemapAction(actionId);
    }

    /**
     * The bind a no-remap action ships with. A request that differs from this is
     * {@link BindRejection#COMBAT_ACTION_REBOUND}.
     *
     * @param actionId the id, possibly an alias
     * @return the vanilla bind, or {@code null} if the action is not a no-remap action
     */
    public static Keybind vanillaDefaultBind(String actionId) {
        String id = canonical(actionId);
        if (id == null) {
            return null;
        }
        if ("attack".equals(id)) {
            return Keybind.of(mouseButton(1, VANILLA_ATTACK));
        }
        if ("use".equals(id)) {
            return Keybind.of(mouseButton(3, VANILLA_USE));
        }
        if ("pickBlock".equals(id)) {
            return Keybind.of(mouseButton(2, VANILLA_PICK_BLOCK));
        }
        if ("drop".equals(id)) {
            return Keybind.of(keyboardKey(81, VANILLA_DROP));
        }
        return null;
    }

    /** @return every vanilla action id, in declaration order */
    public static Set<String> vanillaActions() {
        return VANILLA_ACTIONS;
    }

    /** @return our own module-id namespaces, in declaration order */
    public static Set<String> xsozNamespaces() {
        return XSOZ_NAMESPACES;
    }

    /**
     * @param button the 1-based mouse button number
     * @param name   the vanilla label
     * @return the mouse key, so a test and the registry agree on the code
     */
    public static InputKey mouseButton(int button, String name) {
        return InputKey.mouse(button, name);
    }

    /**
     * @param code the key code
     * @param name the vanilla label
     * @return the keyboard key
     */
    public static InputKey keyboardKey(int code, String name) {
        return InputKey.keyboard(code, name);
    }

    private static boolean isOurModuleId(String id) {
        // The C7.7 grammar, dashed form: the contract's prose says the canonical form is
        // "lower-case-with-dashes inside segments" and gives hud.totem-counter as the
        // example, so the dashed form is what the registry accepts. camelCase is rejected.
        return id.matches("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*$");
    }

    private static Set<String> closedSet(String... values) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(Arrays.asList(values)));
    }
}
