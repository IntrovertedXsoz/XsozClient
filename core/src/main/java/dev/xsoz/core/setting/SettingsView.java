package dev.xsoz.core.setting;

import java.util.List;
import java.util.Map;

/**
 * A read-only, validated view of one module's stored settings (contracts.md C4.3).
 *
 * <p>Obtained only from {@link SettingsSchema#view(Map)} or
 * {@link SettingsSchema#defaultsView()}. <strong>A view is always complete</strong>: every
 * getter answers for every declared key, from the stored value where it validated and
 * from the declared default where it did not. A module that reads a view can therefore
 * never see a missing key, which is the only reason a settings screen can be written
 * without a null check on every row.</p>
 *
 * <p>What happened to the values that did not survive is in {@link #lastReport()}, and it
 * is the module's job to surface that. A silently replaced value is a value the player
 * cannot account for.</p>
 *
 * <p>Every getter is typed to the setting's {@link SettingKind}; asking a {@code BOOLEAN}
 * setting for a {@code double} is a wrong-type call, not a coercion.</p>
 */
public interface SettingsView {

    /**
     * @param key the setting key
     * @return whether the schema declares this key. Always true for a declared key.
     */
    boolean has(String key);

    /**
     * @param key the setting key
     * @return the resolved value, boxed to the setting's kind
     * @throws SettingValidationException if the schema does not declare {@code key}
     */
    Object raw(String key);

    /**
     * @param key a {@link SettingKind#BOOLEAN} setting key
     * @return the resolved flag
     */
    boolean getBoolean(String key);

    /**
     * @param key an {@link SettingKind#INT} setting key
     * @return the resolved whole number
     */
    int getInt(String key);

    /**
     * @param key a {@link SettingKind#DOUBLE} setting key
     * @return the resolved real number
     */
    double getDouble(String key);

    /**
     * @param key an {@link SettingKind#ENUM} setting key
     * @param <E> the enum type
     * @return the resolved constant
     */
    <E extends Enum<E>> E getEnum(String key);

    /**
     * @param key a {@link SettingKind#STRING} setting key
     * @return the resolved text
     */
    String getString(String key);

    /**
     * @param key a {@link SettingKind#COLOR} setting key
     * @return the resolved packed {@code 0xAARRGGBB} colour
     */
    int getArgb(String key);

    /**
     * @param key a {@link SettingKind#KEYBIND} setting key
     * @return the resolved bind, possibly {@link dev.xsoz.core.keybind.Keybind#unbound()}
     */
    dev.xsoz.core.keybind.Keybind getKeybind(String key);

    /**
     * @param key a {@link SettingKind#LIST} setting key
     * @param <E> the element type
     * @return the resolved list, unmodifiable and never longer than the setting's
     *         {@code maxSize}
     */
    <E> List<E> getList(String key);

    /** @return what the loader had to do to produce this view */
    SettingsMutationReport lastReport();
}
