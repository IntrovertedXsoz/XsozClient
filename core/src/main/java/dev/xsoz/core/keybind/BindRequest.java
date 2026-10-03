package dev.xsoz.core.keybind;

import dev.xsoz.core.XsozContractException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the keybind editor is asking for (contracts.md C7.12).
 *
 * <p>Derived flags ({@link #isCombatAction()}, {@link #isMouseOnlyAction()}) are
 * <strong>computed from the closed sets in {@link BindActions}, never supplied</strong>. A
 * caller that could pass {@code isCombatAction = false} for {@code "attack"} would be
 * holding the switch the validator exists to hold.</p>
 *
 * <p>Deeply immutable; every collection is copied on the way in and wrapped on the way
 * out.</p>
 */
public final class BindRequest {

    private final String actionId;
    private final boolean vanillaBinding;
    private final boolean minecraftHotbarSlotAction;
    private final List<Keybind> requestedBindings;
    private final List<Keybind> existingBindingsForThisAction;
    private final Set<Keybind> bindingsHeldByOtherActions;

    private BindRequest(Builder builder) {
        if (builder.actionId == null || builder.actionId.trim().isEmpty()) {
            throw new XsozContractException(
                    "A BindRequest needs an action id. The registry decides whether it is known, "
                            + "so an absent id is not a request.");
        }
        this.actionId = builder.actionId.trim();
        this.vanillaBinding = builder.vanillaBinding;
        this.minecraftHotbarSlotAction = builder.minecraftHotbarSlotAction;
        this.requestedBindings = Collections.unmodifiableList(
                new ArrayList<Keybind>(builder.requestedBindings));
        this.existingBindingsForThisAction = Collections.unmodifiableList(
                new ArrayList<Keybind>(builder.existingBindingsForThisAction));
        this.bindingsHeldByOtherActions = Collections.unmodifiableSet(
                new LinkedHashSet<Keybind>(builder.bindingsHeldByOtherActions));
    }

    /** @return a builder for a first-party or vanilla action the caller already knows */
    public static Builder forAction(String actionId) {
        return new Builder(actionId);
    }

    /**
     * A request for an ordinary non-combat action, with no prior state. The common case in
     * the keybind editor.
     *
     * @param actionId the action
     * @return a builder
     */
    public static Builder forVanillaAction(String actionId) {
        return new Builder(actionId).vanillaBinding(true);
    }

    /**
     * A request for one of our own module toggles, e.g. {@code "hud.totem-counter"}.
     *
     * @param actionId a first-party module id
     * @return a builder
     */
    public static Builder forModuleToggle(String actionId) {
        return new Builder(actionId);
    }

    /** @return a builder; never {@code null} */
    public Builder toBuilder() {
        return new Builder(actionId)
                .vanillaBinding(vanillaBinding)
                .minecraftHotbarSlotAction(minecraftHotbarSlotAction)
                .requestedBindings(requestedBindings)
                .existingBindingsForThisAction(existingBindingsForThisAction)
                .bindingsHeldByOtherActions(bindingsHeldByOtherActions);
    }

    /** @return the action id, verbatim as typed by the caller */
    public String actionId() {
        return actionId;
    }

    /** @return the canonical action id, with any alias resolved */
    public String canonicalActionId() {
        return BindActions.canonical(actionId);
    }

    /** @return whether the action is in {@link BindActions#NO_REMAP_ACTIONS} */
    public boolean isCombatAction() {
        return BindActions.isNoRemapAction(actionId);
    }

    /** @return whether the action is in {@link BindActions#MOUSE_ONLY_ACTIONS} */
    public boolean isMouseOnlyAction() {
        return BindActions.isMouseOnlyAction(actionId);
    }

    /** @return whether the action is in the closed action registry at all */
    public boolean isKnownAction() {
        return BindActions.isKnown(actionId);
    }

    /** @return whether vanilla also binds this action */
    public boolean isVanillaBinding() {
        return vanillaBinding;
    }

    /**
     * @return the binds the editor is trying to install for this action. A list of length
     *         two or more is a multi-key bind, which {@code Keybind} has no way to represent
     *         and the policy refuses.
     */
    public List<Keybind> requestedBindings() {
        return requestedBindings;
    }

    /** @return the binds this action already holds, from the stored config */
    public List<Keybind> existingBindingsForThisAction() {
        return existingBindingsForThisAction;
    }

    /** @return every key currently held by a different action */
    public Set<Keybind> bindingsHeldByOtherActions() {
        return bindingsHeldByOtherActions;
    }

    /**
     * Whether this is one of vanilla's {@code hotbar.1..9} actions. The closed
     * {@link BindRejection} set has no constant for a hotbar-slot violation, so the policy
     * does not refuse on this flag; it is carried because the contract's request shape names
     * it and the editor needs it to render the right row.
     *
     * @return whether the action is a vanilla hotbar slot
     */
    public boolean isMinecraftHotbarSlotAction() {
        return minecraftHotbarSlotAction;
    }

    @Override
    public String toString() {
        return "BindRequest[" + actionId
                + " requested=" + requestedBindings
                + " existing=" + existingBindingsForThisAction
                + " heldElsewhere=" + bindingsHeldByOtherActions + "]";
    }

    /** Assembles a {@link BindRequest}. */
    public static final class Builder {

        private final String actionId;
        private boolean vanillaBinding;
        private boolean minecraftHotbarSlotAction;
        private final List<Keybind> requestedBindings = new ArrayList<Keybind>();
        private final List<Keybind> existingBindingsForThisAction = new ArrayList<Keybind>();
        private final Set<Keybind> bindingsHeldByOtherActions = new LinkedHashSet<Keybind>();

        private Builder(String actionId) {
            this.actionId = actionId;
            this.minecraftHotbarSlotAction = isHotbarSlot(actionId);
        }

        /**
         * @param requested the binds the editor wants to install; length 2 or more is the
         *                  multi-key case
         * @return this builder
         */
        public Builder requestedBindings(Collection<Keybind> requested) {
            requestedBindings.clear();
            if (requested != null) {
                for (Keybind bind : requested) {
                    if (bind != null) {
                        requestedBindings.add(bind);
                    }
                }
            }
            return this;
        }

        /**
         * @param bind the single bind being requested
         * @return this builder
         */
        public Builder requested(Keybind bind) {
            return requestedBindings(Collections.singletonList(bind));
        }

        /**
         * @param existing the binds this action already holds
         * @return this builder
         */
        public Builder existingBindingsForThisAction(Collection<Keybind> existing) {
            existingBindingsForThisAction.clear();
            if (existing != null) {
                for (Keybind bind : existing) {
                    if (bind != null) {
                        existingBindingsForThisAction.add(bind);
                    }
                }
            }
            return this;
        }

        /**
         * @param held every key currently held by a different action
         * @return this builder
         */
        public Builder bindingsHeldByOtherActions(Collection<Keybind> held) {
            bindingsHeldByOtherActions.clear();
            if (held != null) {
                for (Keybind bind : held) {
                    if (bind != null) {
                        bindingsHeldByOtherActions.add(bind);
                    }
                }
            }
            return this;
        }

        /**
         * @param vanillaBinding whether vanilla also binds this action
         * @return this builder
         */
        public Builder vanillaBinding(boolean vanillaBinding) {
            this.vanillaBinding = vanillaBinding;
            return this;
        }

        /**
         * @param hotbarSlot whether this is a vanilla hotbar slot action
         * @return this builder
         */
        public Builder minecraftHotbarSlotAction(boolean hotbarSlot) {
            this.minecraftHotbarSlotAction = hotbarSlot;
            return this;
        }

        /** @return the assembled, immutable request */
        public BindRequest build() {
            return new BindRequest(this);
        }

        private static boolean isHotbarSlot(String actionId) {
            return actionId != null && actionId.startsWith("hotbar.");
        }
    }
}
