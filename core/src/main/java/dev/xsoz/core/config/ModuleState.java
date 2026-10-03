package dev.xsoz.core.config;

import dev.xsoz.core.XsozContractException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One module's entry in a profile: whether it is requested on, and its stored settings
 * (contracts.md C5.2, the {@code modules} map).
 *
 * <p><strong>{@code enabled} here is a REQUEST, not an effective state.</strong> The
 * effective state is {@code requested && authorized} (contracts.md C2.4): this is the
 * config flag the enable gate reads, nothing more. Naming it {@code requestedEnabled}
 * would be clearer and the file format is fixed, so the Javadoc carries the
 * distinction instead.</p>
 *
 * <p>Settings are stored as raw JSON-shaped values and are validated against the owning
 * module's {@link dev.xsoz.core.setting.SettingsSchema} at load. They are <em>not</em>
 * resolved to a typed view here: this type is what a file holds, and the resolution is
 * the module's business.</p>
 *
 * <p>Deeply immutable, with a defensive copy of the settings map.</p>
 */
public final class ModuleState {

    private final boolean enabled;
    private final Map<String, Object> settings;

    private ModuleState(boolean enabled, Map<String, Object> settings) {
        this.enabled = enabled;
        this.settings = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(settings));
    }

    /**
     * @param enabled  the requested-enabled flag, the config value
     * @param settings the stored setting values, keyed by full setting key; defensively
     *                 copied
     * @return the module state
     * @throws XsozContractException if {@code settings} is {@code null}
     */
    public static ModuleState of(boolean enabled, Map<String, Object> settings) {
        if (settings == null) {
            throw new XsozContractException(
                    "A module's settings map may be empty but not null. An absent map and an "
                            + "empty one are the same fact, and only one of them is writable.");
        }
        return new ModuleState(enabled, settings);
    }

    /**
     * @param enabled the requested-enabled flag
     * @return the state with no stored settings
     */
    public static ModuleState of(boolean enabled) {
        return new ModuleState(enabled, Collections.<String, Object>emptyMap());
    }

    /**
     * @return the <em>requested</em> enabled flag. The effective state also needs the
     *         compliance gate (C2.4).
     */
    public boolean requestedEnabled() {
        return enabled;
    }

    /** @return the stored setting values, unmodifiable */
    public Map<String, Object> settings() {
        return settings;
    }

    /**
     * @param newEnabled the new requested-enabled flag
     * @return a copy with the flag changed; settings untouched
     */
    public ModuleState withRequestedEnabled(boolean newEnabled) {
        return new ModuleState(newEnabled, settings);
    }

    /**
     * @param newSettings the new stored values; defensively copied
     * @return a copy with the settings replaced
     * @throws XsozContractException if {@code newSettings} is {@code null}
     */
    public ModuleState withSettings(Map<String, Object> newSettings) {
        return new ModuleState(enabled, newSettings);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ModuleState)) {
            return false;
        }
        ModuleState that = (ModuleState) other;
        return enabled == that.enabled && settings.equals(that.settings);
    }

    @Override
    public int hashCode() {
        return 31 * Boolean.valueOf(enabled).hashCode() + settings.hashCode();
    }

    @Override
    public String toString() {
        return "ModuleState[requestedEnabled=" + enabled + ", " + settings.size() + " settings]";
    }
}
