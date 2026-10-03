package dev.xsoz.core.setting;

import java.util.List;
import java.util.Map;

/**
 * The immutable {@link SettingsView} implementation behind
 * {@link SettingsSchema#view(Map)} and {@link SettingsSchema#defaultsView()}.
 *
 * <p>Package-private: a view is only ever obtainable from its schema, which is what
 * guarantees that every key in it was validated.</p>
 *
 * <p>The typed getters narrow to the declared kind and throw a
 * {@link SettingValidationException} naming the key when a caller asks the wrong type of
 * question. A settings screen that asks a {@code BOOLEAN} row for a {@code double} has a
 * bug, and a silent {@code null} would hide it until a frame rendered empty.</p>
 */
final class ResolvedSettingsView implements SettingsView {

    private final SettingsSchema schema;
    private final Map<String, Object> values;
    private final SettingsMutationReport report;

    ResolvedSettingsView(SettingsSchema schema, Map<String, Object> values,
                         SettingsMutationReport report) {
        this.schema = schema;
        this.values = values;
        this.report = report;
    }

    @Override
    public boolean has(String key) {
        return values.containsKey(key);
    }

    @Override
    public Object raw(String key) {
        if (!values.containsKey(key)) {
            throw new SettingValidationException(key,
                    "not declared by module " + schema.moduleId() + ". Declared keys: "
                            + schema.keys() + ".");
        }
        return values.get(key);
    }

    @Override
    public boolean getBoolean(String key) {
        requireKind(key, SettingKind.BOOLEAN);
        return (Boolean) raw(key);
    }

    @Override
    public int getInt(String key) {
        requireKind(key, SettingKind.INT);
        return ((Number) raw(key)).intValue();
    }

    @Override
    public double getDouble(String key) {
        requireKind(key, SettingKind.DOUBLE);
        return ((Number) raw(key)).doubleValue();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <E extends Enum<E>> E getEnum(String key) {
        requireKind(key, SettingKind.ENUM);
        return (E) raw(key);
    }

    @Override
    public String getString(String key) {
        requireKind(key, SettingKind.STRING);
        return (String) raw(key);
    }

    @Override
    public int getArgb(String key) {
        requireKind(key, SettingKind.COLOR);
        return ((Number) raw(key)).intValue();
    }

    @Override
    public dev.xsoz.core.keybind.Keybind getKeybind(String key) {
        requireKind(key, SettingKind.KEYBIND);
        return (dev.xsoz.core.keybind.Keybind) raw(key);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <E> List<E> getList(String key) {
        requireKind(key, SettingKind.LIST);
        return (List<E>) raw(key);
    }

    @Override
    public SettingsMutationReport lastReport() {
        return report;
    }

    /**
     * Refuses a question the schema cannot answer, naming the DECLARED kind.
     *
     * <p>The kind comes from the declaration rather than from the runtime type of the
     * value. A packed {@code 0xFF......} colour is a negative {@code Integer}, and a
     * COLOUR row asked for as an INT is exactly the bug this catches.</p>
     */
    private void requireKind(String key, SettingKind expected) {
        Setting setting = schema.byKey(key).orElseThrow(() -> new SettingValidationException(key,
                "not declared by module " + schema.moduleId() + ". Declared keys: "
                        + schema.keys() + "."));
        if (setting.kind() != expected) {
            throw new SettingValidationException(key,
                    "asked for " + expected + " but the schema declares " + setting.kind()
                            + ". A typed getter does not coerce: a settings row rendered from the "
                            + "wrong getter is a bug, not a formatting difference.");
        }
    }
}
