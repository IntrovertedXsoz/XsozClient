package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;

/**
 * The base of one declared setting (contracts.md C4.1).
 *
 * <p>Abstract class rather than the contract's {@code interface} sketch, because
 * {@code key}, {@code label}, {@code description} and {@code sensitive} are final, stored
 * once, and every concrete kind would otherwise re-declare and re-validate the same four
 * strings. Every other member of C4.1's interface is present with the same name and
 * signature: {@link #kind()}, {@link #validate(Object)}, {@link #defaultValue()}.</p>
 *
 * <p><strong>Deliberately NOT sealed.</strong> contracts.md 0.2 bans sealed classes, and more
 * practically, the remaining kinds in {@link SettingKind} are another agent's file scope:
 * sealing the base to two subclasses would make their work a compile error.</p>
 *
 * <p><strong>{@link #sensitive()} is PRESENTATIONAL ONLY.</strong> It decides whether the
 * settings screen masks the value and whether the value may be written to a log line
 * (contracts.md 0.5). It must not, and does not, reach validation, coercion, the range, the
 * default, or the identity of the setting. Overloading it turns a settings refactor into a
 * compliance change, which is exactly the kind of drift this product cannot afford.
 * {@code SettingSensitiveFlagIsPresentationalTest} is the proof.</p>
 *
 * <p>Deeply immutable: final fields, no setters.</p>
 */
public abstract class Setting {

    /**
     * The key grammar (contracts.md C4.1): {@code <moduleId>.<settingName>}, globally
     * unique, lower-case dot-separated segments, each starting with a letter.
     *
     * <p><strong>Two corrections to C4.1, applied rather than reported.</strong> C4.1 writes
     * the segments as {@code [a-z0-9_]}, which would reject the {@code <moduleId>} half
     * outright - C7.7's canonical module ids contain dashes ({@code hud.totem-counter},
     * {@code latency.crystal-release}, {@code perf.sodium-tweaks}) - and C4.1's own worked
     * example in C5.2 is camelCase, which C7.7 rejects. The grammar below accepts the
     * intersection the rest of the product actually uses: dashes for module ids, underscores
     * for setting names, camelCase refused.</p>
     */
    public static final String KEY_GRAMMAR = "^[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*$";

    /**
     * The single string that stands in for a {@code sensitive} value in a log line
     * (contracts.md 0.5).
     *
     * <p>A redaction marker with a single, greppable spelling. Two different placeholders
     * would mean two greps to prove a value never reached a log.</p>
     */
    public static final String REDACTED = "<redacted>";

    private final String key;
    private final String label;
    private final String description;
    private final boolean sensitive;

    /**
     * @param key         the globally unique key; must match {@link #KEY_GRAMMAR}
     * @param label       the human label, capitalised, no trailing colon
     * @param description one sentence, ending without a period
     * @param sensitive   presentational only; see the class note
     * @throws XsozContractException if any argument is absent or the key is malformed
     */
    protected Setting(String key, String label, String description, boolean sensitive) {
        this.key = requireKey(key);
        if (label == null || label.trim().isEmpty()) {
            throw new XsozContractException(
                    "Setting \"" + this.key + "\" needs a non-blank label. A setting with no label is "
                            + "a row the player cannot find.");
        }
        if (label.trim().endsWith(":")) {
            throw new XsozContractException(
                    "Setting \"" + this.key + "\" label must not end with a colon; the UI adds it.");
        }
        if (description == null || description.trim().isEmpty()) {
            throw new XsozContractException(
                    "Setting \"" + this.key + "\" needs a non-blank description. A setting whose "
                            + "purpose is not written down is a setting nobody reviews.");
        }
        if (description.trim().endsWith(".")) {
            throw new XsozContractException(
                    "Setting \"" + this.key + "\" description must end without a period (C4.1).");
        }
        this.label = label.trim();
        this.description = description.trim();
        this.sensitive = sensitive;
    }

    /** @return the globally unique key, e.g. {@code "hud.totem-counter.show-carry-count"} */
    public final String key() {
        return key;
    }

    /** @return the human label */
    public final String label() {
        return label;
    }

    /** @return the one-sentence description */
    public final String description() {
        return description;
    }

    /**
     * <strong>Presentational only.</strong> Decides masking in the settings screen and whether
     * the value may appear in a log line. It is not consulted by {@link #validate(Object)}, by
     * any coercion, or by equality.
     *
     * @return whether the value should be masked in the UI
     */
    public final boolean sensitive() {
        return sensitive;
    }

    /** @return the setting kind */
    public abstract SettingKind kind();

    /**
     * Renders a value for a log line, honouring {@link #sensitive()}.
     *
     * <p>contracts.md 0.5: no log line ever contains the value of a {@code sensitive}
     * setting. This is the one place that rule is implemented, and it is implemented by
     * the setting that declared the flag rather than by each call site, so a call site
     * that forgets cannot leak the value.</p>
     *
     * <p><strong>Not a validation path.</strong> What comes back is a string for display
     * and has no influence on behaviour: {@code validate} never consults it and neither
     * does equality.</p>
     *
     * @param value the value about to be logged
     * @return {@link #REDACTED} when this setting is sensitive, otherwise the value's own
     *         string form
     */
    public final String redact(Object value) {
        return sensitive ? REDACTED : String.valueOf(value);
    }

    /** @return the declared default, which is always inside the declared range */
    public abstract Object defaultValue();

    /**
     * Validates a raw value. <strong>Throws; it does not coerce.</strong>
     *
     * @param rawValue the value as read, never {@code null}
     * @throws SettingValidationException if the value is absent or the wrong type or out of
     *                                   range
     */
    public abstract void validate(Object rawValue);

    private static String requireKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new XsozContractException(
                    "A setting needs a key. contracts.md C4.1: key is \"<moduleId>.<settingName>\", "
                            + "globally unique.");
        }
        String trimmed = key.trim();
        if (!trimmed.matches(KEY_GRAMMAR)) {
            throw new XsozContractException(
                    "\"" + trimmed + "\" is not a valid setting key. contracts.md C4.1 grammar: "
                            + KEY_GRAMMAR + ". camelCase is rejected; use lower-case-with-dashes.");
        }
        return trimmed;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        Setting that = (Setting) other;
        // sensitive is deliberately absent: it is presentational, and overloading it must not
        // change the identity of a declared setting.
        return key.equals(that.key)
                && label.equals(that.label)
                && description.equals(that.description)
                && defaultValue().equals(that.defaultValue());
    }

    @Override
    public final int hashCode() {
        return (key.hashCode() * 31 + label.hashCode()) * 31 + description.hashCode();
    }

    @Override
    public String toString() {
        return kind() + "[" + key + "=\"" + label + "\" default=" + defaultValue()
                + (sensitive ? " sensitive" : "") + "]";
    }
}
