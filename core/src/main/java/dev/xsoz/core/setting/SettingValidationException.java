package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;

/**
 * Thrown when a value does not satisfy its {@link Setting} (contracts.md C4.1).
 *
 * <p><strong>Validation throws; it does not silently coerce.</strong> Coercion and clamping
 * happen in the loader (C4.4), which <em>reports</em> what it did. A setting that quietly
 * turns {@code -3} into {@code 0} is a setting whose value the player cannot account for.</p>
 *
 * <p>Named in the contract, so this is not an invented subclass (contracts.md 0.4).</p>
 */
public class SettingValidationException extends XsozContractException {

    private static final long serialVersionUID = 1L;

    private final String key;

    /**
     * @param key         the setting key that refused the value
     * @param explanation what was expected, what arrived, and what the caller must do
     */
    public SettingValidationException(String key, String explanation) {
        super("Setting \"" + key + "\" refused a value: " + explanation);
        this.key = key;
    }

    /** @return the setting key that refused the value */
    public String key() {
        return key;
    }
}
