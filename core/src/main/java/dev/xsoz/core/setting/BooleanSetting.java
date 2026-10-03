package dev.xsoz.core.setting;

/**
 * A true/false setting (contracts.md C4.2).
 *
 * <p>The simplest kind, and the one with no range to get wrong. {@link #validate} refuses
 * anything that is not a {@link Boolean}, including {@code 0}, {@code 1} and {@code "true"}:
 * coercion belongs to the loader, which reports what it did.</p>
 */
public final class BooleanSetting extends Setting {

    private final boolean defaultValue;

    /**
     * @param key          the globally unique key
     * @param label        the human label, no trailing colon
     * @param description  one sentence, no trailing period
     * @param defaultValue the declared default
     * @param sensitive    presentational only; a boolean carries no secret, so it is normally
     *                     {@code false}
     */
    public BooleanSetting(String key, String label, String description,
                          boolean defaultValue, boolean sensitive) {
        super(key, label, description, sensitive);
        this.defaultValue = defaultValue;
    }

    @Override
    public SettingKind kind() {
        return SettingKind.BOOLEAN;
    }

    /** @return the declared default, boxed */
    @Override
    public Boolean defaultValue() {
        return Boolean.valueOf(defaultValue);
    }

    /** @return the declared default, unboxed */
    public boolean defaultBoolean() {
        return defaultValue;
    }

    /**
     * @param rawValue the value as read
     * @throws SettingValidationException if {@code rawValue} is {@code null} or not a
     *                                   {@link Boolean}
     */
    @Override
    public void validate(Object rawValue) {
        if (rawValue == null) {
            throw new SettingValidationException(key(),
                    "expected a boolean, found nothing. A missing setting is a loader decision, "
                            + "not a value this setting can interpret.");
        }
        if (!(rawValue instanceof Boolean)) {
            throw new SettingValidationException(key(),
                    "expected a boolean, found " + rawValue.getClass().getSimpleName() + " ("
                            + rawValue + "). C4.1: validate throws, it does not coerce.");
        }
    }
}
