package dev.xsoz.core.keybind;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The default {@link BindPolicy} (contracts.md C7.12).
 *
 * <p><strong>Check order is part of the behaviour,</strong> and is the order of the
 * contract's own example table:</p>
 * <ol>
 *   <li>{@link BindRejection#UNKNOWN_ACTION} - there is no point applying combat rules to an
 *       action the registry does not know.</li>
 *   <li>{@link BindRejection#NULL_BIND} - including a literal {@code null} bind, so the
 *       validator refuses one rather than throwing on it.</li>
 *   <li>{@link BindRejection#MULTI_KEY_BIND} - the request carries two or more binds, or the
 *       action already holds two distinct binds.</li>
 *   <li>{@link BindRejection#KEY_SEQUENCE_BIND} - a chord, which is only a problem on a
 *       combat action.</li>
 *   <li>{@link BindRejection#DOUBLE_BIND} - the key is already held by another action. This
 *       precedes the combat checks because the contract's own example for it is a request
 *       for {@code attack} on a key another action already holds.</li>
 *   <li>{@link BindRejection#COMBAT_ACTION_ON_KEYBOARD} - a mouse-only action on a keyboard
 *       key.</li>
 *   <li>{@link BindRejection#COMBAT_ACTION_REBOUND} - a no-remap action moved off its
 *       vanilla key, including onto a different <em>mouse</em> button.</li>
 * </ol>
 *
 * <p><strong>A validator that rejects everything is not a validator</strong>
 * ({@code docs/rules-matrix.md} 4), so a non-combat rebind is allowed: a module toggle to
 * any key, {@code key.forward} to W, {@code fullscreen} to F11, and a non-combat action to a
 * chord, because a chord on a non-combat action is an ordinary vanilla hotkey.</p>
 */
public final class DefaultBindPolicy implements BindPolicy {

    @Override
    public BindVerdict validate(Keybind bind, BindRequest req) {
        if (req == null) {
            throw new dev.xsoz.core.XsozContractException(
                    "BindPolicy.validate requires a request. A caller with no request has nothing "
                            + "to validate against, and guessing would be how an attack rebind gets "
                            + "in.");
        }
        String actionId = req.canonicalActionId();

        if (!req.isKnownAction()) {
            return BindVerdict.reject(BindRejection.UNKNOWN_ACTION,
                    "\"" + req.actionId() + "\" is not in the action registry, so this client has no "
                            + "rules for it. Nothing that is not in the registry is bindable, because "
                            + "the registry is what says which actions are combat actions.");
        }

        if (bind == null || !bind.isBound()) {
            return BindVerdict.reject(BindRejection.NULL_BIND,
                    req.actionId() + " is not bound to a real key. An action bound to the unknown "
                            + "key is not a bind; it is a row in the editor with nothing in it.");
        }

        if (req.requestedBindings().size() >= 2) {
            return BindVerdict.reject(BindRejection.MULTI_KEY_BIND,
                    req.actionId() + " was given " + req.requestedBindings().size() + " keys ("
                            + req.requestedBindings() + "). One action, one key. PvP Land names this "
                            + "by name: \"Double Key Binds\".");
        }

        Set<Keybind> distinctExisting = distinct(req.existingBindingsForThisAction());
        if (distinctExisting.size() >= 2) {
            return BindVerdict.reject(BindRejection.MULTI_KEY_BIND,
                    req.actionId() + " already holds " + distinctExisting.size() + " distinct keys ("
                            + distinctExisting + "), which is a key sequence in the stored state. One "
                            + "action, one key.");
        }

        if (req.isCombatAction() && !bind.isSingleKey()) {
            return BindVerdict.reject(BindRejection.KEY_SEQUENCE_BIND,
                    req.actionId() + " is a combat action and cannot take a chord. "
                            + req.actionId() + " with " + bind.modifiers() + " is a macro on an "
                            + "attack, which every network studied bans as automating a gameplay "
                            + "action.");
        }

        if (req.bindingsHeldByOtherActions().contains(bind)) {
            return BindVerdict.reject(BindRejection.DOUBLE_BIND,
                    bind + " is already held by another action. The same key may not be bound twice: "
                            + "PvPHQ disallows \"abnormal keybinding or double-binding\", and PvP Land "
                            + "names \"Double Key Binds\" outright.");
        }

        if (req.isMouseOnlyAction() && !bind.isMouseButton()) {
            return BindVerdict.reject(BindRejection.COMBAT_ACTION_ON_KEYBOARD,
                    req.actionId() + " may only be bound to a physical mouse button, and "
                            + bind.key().displayName() + " is a keyboard key. PvPHQ disallows "
                            + "abnormal keybinding; the game's own exception is \"standard "
                            + "mouse-required bindings\".");
        }

        if (req.isCombatAction()) {
            Keybind vanillaDefault = BindActions.vanillaDefaultBind(actionId);
            if (vanillaDefault != null && !vanillaDefault.equals(bind)) {
                return BindVerdict.reject(BindRejection.COMBAT_ACTION_REBOUND,
                        req.actionId() + " may not be moved off its vanilla key ("
                                + vanillaDefault + "). It is bound to " + bind
                                + ", which is a rebind of a combat action.");
            }
        }

        return BindVerdict.allow();
    }

    private static Set<Keybind> distinct(java.util.List<Keybind> binds) {
        Set<Keybind> out = new LinkedHashSet<Keybind>();
        for (Keybind bind : binds) {
            if (bind != null && bind.isBound()) {
                out.add(bind);
            }
        }
        return out;
    }
}
