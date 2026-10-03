package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;
import dev.xsoz.core.keybind.BindPolicy;
import dev.xsoz.core.keybind.BindRequest;
import dev.xsoz.core.keybind.BindVerdict;
import dev.xsoz.core.keybind.Keybind;

import java.util.Collections;
import java.util.Set;

/**
 * A keybind setting (contracts.md C4.2, C7.12).
 *
 * <p>The default may be {@link Keybind#unbound()}, which is how an action ships
 * unassigned rather than being omitted from the schema.</p>
 *
 * <p><strong>The policy is optional, and when it is present it is the same policy every
 * load runs through.</strong> C4.4 lists "Keybind policy" among the things whose failure
 * resets a value to its default, so a schema that hands this setting a
 * {@link dev.xsoz.core.keybind.BindPolicy} and an action id gets policy enforcement as a
 * property of the declaration rather than as a rule a call site might forget.</p>
 *
 * <p>With no policy, any {@link Keybind} is valid, including {@link Keybind#unbound()}.
 * That is the honest default for a non-combat action and the wrong one for a combat
 * action, which is why the policy-carrying constructor exists.</p>
 */
public final class KeybindSetting extends Setting {

    private final Keybind defaultKeybind;
    private final BindPolicy policy;
    private final String policyActionId;

    /**
     * @param key           the globally unique key
     * @param label         the human label, no trailing colon
     * @param description   one sentence, no trailing period
     * @param defaultKeybind the declared default; may be {@link Keybind#unbound()} but not
     *                       {@code null}
     * @param sensitive     presentational only
     * @throws XsozContractException if the default is {@code null}
     */
    public KeybindSetting(String key, String label, String description,
                          Keybind defaultKeybind, boolean sensitive) {
        this(key, label, description, defaultKeybind, null, null, sensitive);
    }

    /**
     * @param key            the globally unique key
     * @param label          the human label, no trailing colon
     * @param description    one sentence, no trailing period
     * @param defaultKeybind the declared default; may be {@link Keybind#unbound()} but not
     *                        {@code null}
     * @param policyActionId the action id handed to the policy; required with a policy
     * @param policy         the validator every write and every load runs through, or
     *                       {@code null} to accept any bind
     * @param sensitive      presentational only
     * @throws XsozContractException if the default is {@code null}, or a policy is supplied
     *                                without an action id
     */
    public KeybindSetting(String key, String label, String description,
                          Keybind defaultKeybind, String policyActionId,
                          BindPolicy policy, boolean sensitive) {
        super(key, label, description, sensitive);
        if (defaultKeybind == null) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" needs a default keybind. Use Keybind.unbound() for "
                            + "an action that ships unassigned; null is not a bind.");
        }
        if (policy != null && (policyActionId == null || policyActionId.trim().isEmpty())) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" supplies a BindPolicy with no action id, so the "
                            + "policy has no rules to apply. A policy with no action is a policy "
                            + "that allows everything while looking strict.");
        }
        this.defaultKeybind = defaultKeybind;
        this.policy = policy;
        this.policyActionId = policyActionId == null ? null : policyActionId.trim();
    }

    @Override
    public SettingKind kind() {
        return SettingKind.KEYBIND;
    }

    /** @return the declared default bind */
    @Override
    public Keybind defaultValue() {
        return defaultKeybind;
    }

    /** @return the declared default bind, unboxed; identical to {@link #defaultValue()} */
    public Keybind defaultKeybind() {
        return defaultKeybind;
    }

    /** @return whether this setting enforces a {@link BindPolicy} */
    public boolean hasPolicy() {
        return policy != null;
    }

    /** @return the action id handed to the policy, or {@code null} when there is no policy */
    public String policyActionId() {
        return policyActionId;
    }

    /**
     * @param rawValue the value as read
     * @throws SettingValidationException if {@code rawValue} is {@code null}, is not a
     *                                   {@link Keybind}, or is refused by the policy
     */
    @Override
    public void validate(Object rawValue) {
        if (rawValue == null) {
            throw new SettingValidationException(key(),
                    "expected a Keybind, found nothing. A missing keybind is a loader decision, "
                            + "not a value this setting can interpret.");
        }
        if (!(rawValue instanceof Keybind)) {
            throw new SettingValidationException(key(),
                    "expected a Keybind, found " + rawValue.getClass().getSimpleName() + " ("
                            + rawValue + "). A key name in a string is a row the editor has to "
                            + "resolve, not a bind this setting can accept.");
        }
        if (policy == null) {
            return;
        }
        Keybind candidate = (Keybind) rawValue;
        BindRequest request = BindRequest.forAction(policyActionId)
                .requested(candidate)
                .existingBindingsForThisAction(Collections.<Keybind>emptySet())
                .build();
        BindVerdict verdict = policy.validate(candidate, request);
        if (!verdict.allowed()) {
            throw new SettingValidationException(key(),
                    "BindPolicy refused \"" + policyActionId + "\": " + verdict.reason() + " - "
                            + verdict.message());
        }
    }

    /**
     * The loaded modifiers of a stored bind, for the settings screen.
     *
     * @param bind a bind, possibly {@link Keybind#unbound()}
     * @return the unmodifiable modifier set, possibly empty
     */
    public static Set<dev.xsoz.core.keybind.ModifierKey> modifiersOf(Keybind bind) {
        return bind == null ? Collections.<dev.xsoz.core.keybind.ModifierKey>emptySet()
                : bind.modifiers();
    }
}
