package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * One constant of an enum, serialised by {@link Enum#name()} (contracts.md C4.2).
 *
 * <p><strong>By name, never by ordinal.</strong> Reordering an enum's constant list must
 * not change any saved config: an ordinal-based file silently re-points a saved
 * {@code COMPACT} at whatever constant moves into slot zero, and the player has no way to
 * see that happen.</p>
 *
 * <p>The {@code unit} argument is accepted but <strong>may be {@code null} or
 * empty</strong>, because C4.2's mandatory-unit rule is stated for <em>numeric</em>
 * settings and a style enum has no unit. When present it is trimmed and exposed.</p>
 *
 * <p>Deeply immutable.</p>
 *
 * @param <E> the enum this setting holds
 */
public final class EnumSetting<E extends Enum<E>> extends Setting {

    private final E defaultValue;
    private final Class<E> type;
    private final String unit;
    private final List<E> permittedValues;

    /**
     * @param key          the globally unique key
     * @param label        the human label, no trailing colon
     * @param description  one sentence, no trailing period
     * @param defaultValue the declared default, a constant of {@code type}
     * @param type         the enum class; must have constants
     * @param unit         a unit name, or {@code null} for an enum that has none
     * @param sensitive    presentational only
     * @throws XsozContractException if the type has no constants, the default is not a
     *                                constant of it, or a blank {@code unit} is supplied
     */
    public EnumSetting(String key, String label, String description,
                       E defaultValue, Class<E> type, String unit, boolean sensitive) {
        super(key, label, description, sensitive);
        if (type == null) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" needs its enum class, so the permitted values can "
                            + "be listed instead of guessed.");
        }
        E[] constants = type.getEnumConstants();
        if (constants == null || constants.length == 0) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" names the enum " + type.getName()
                            + ", which has no constants. An enum setting with nothing to select "
                            + "is a row the player cannot change.");
        }
        if (defaultValue == null) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" needs a default, which must be one of " + type.getSimpleName()
                            + "'s constants.");
        }
        if (unit != null && unit.trim().isEmpty()) {
            throw new XsozContractException(
                    "Setting \"" + key() + "\" was given a blank unit. Pass null when the enum has "
                            + "no unit; \" \" is not a way to say the same thing.");
        }
        this.defaultValue = defaultValue;
        this.type = type;
        this.unit = unit == null ? null : unit.trim();
        this.permittedValues = Collections.unmodifiableList(new ArrayList<E>(Arrays.asList(constants)));
    }

    @Override
    public SettingKind kind() {
        return SettingKind.ENUM;
    }

    /** @return the declared default */
    @Override
    public E defaultValue() {
        return defaultValue;
    }

    /** @return the enum class, so a caller can serialise by {@code name()} */
    public Class<E> type() {
        return type;
    }

    /** @return the unit name, or {@code null} when the enum has none */
    public String unit() {
        return unit;
    }

    /**
     * The permitted values, in <strong>declaration order</strong>.
     *
     * <p>Declaration order, not a sort: it is the order the UI shows, and a sorted order
     * would make a new constant appear in the middle of a player's saved list.</p>
     *
     * @return an unmodifiable list of every constant of {@link #type()}
     */
    public List<E> permittedValues() {
        return permittedValues;
    }

    /**
     * Resolves a stored name to a constant.
     *
     * <p>The loader's use of this method is what makes "not a member of the enum means
     * use the default" (C4.4) a value rather than an exception.</p>
     *
     * @param name the stored name
     * @return the constant, or empty when {@code name} names none
     */
    public Optional<E> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (E candidate : permittedValues) {
            if (candidate.name().equals(name)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * @param rawValue the value as read; a constant of {@link #type()}, never a name
     * @throws SettingValidationException if {@code rawValue} is {@code null} or is not a
     *                                   constant of {@link #type()}
     */
    @Override
    public void validate(Object rawValue) {
        if (rawValue == null) {
            throw new SettingValidationException(key(),
                    "expected one of " + names() + ", found nothing. A missing setting is a loader "
                            + "decision, not a value this setting can interpret.");
        }
        if (!type.isInstance(rawValue)) {
            throw new SettingValidationException(key(),
                    "expected one of " + names() + ", found " + rawValue.getClass().getSimpleName()
                            + " (" + rawValue + "). C4.2: an enum is validated as the constant "
                            + "itself; the loader resolves a stored name to a constant first.");
        }
    }

    private String names() {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < permittedValues.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(permittedValues.get(i).name());
        }
        return out.append(']').toString();
    }
}
