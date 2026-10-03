package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One module's declared settings, in declaration order (contracts.md C4.3).
 *
 * <p><strong>Declaration order is UI order is JSON key order</strong>, and that is a
 * contract, not a convenience: adding a setting appends, reordering is a change request,
 * and a diff of the key order is how a reviewer sees that a key moved.</p>
 *
 * <p><strong>{@link #view(Map)} never throws.</strong> It collects every problem into a
 * {@link SettingsMutationReport} and returns a fully-valid view built from declared
 * defaults where the stored value did not survive. A module that reads a view always
 * gets a usable object, which is the only reason a settings screen can be written without
 * a null check on every row.</p>
 *
 * <p><strong>{@link #validateAll(Map)} is the opposite and is the strict pass.</strong> It
 * throws on the first problem, including an undeclared key. It exists for the paths that
 * must be exactly right before anything is applied: an import (C5.5 step 3) and each
 * element of a {@link ListSetting}.</p>
 *
 * <p>A key must begin with its module id and a dot. The contract writes the key grammar
 * as {@code "<moduleId>.<settingName>"} (C4.1), and enforcing the prefix here is what
 * keeps two modules from both declaring {@code "show-carry-count"} and one of them
 * silently winning at load.</p>
 *
 * <p>Deeply immutable.</p>
 */
public final class SettingsSchema {

    private final String moduleId;
    private final List<Setting> settings;
    private final Map<String, Setting> byKey;
    private final SettingsView defaultsView;

    private SettingsSchema(String moduleId, List<Setting> settings) {
        this.moduleId = moduleId;
        this.settings = Collections.unmodifiableList(new ArrayList<Setting>(settings));
        Map<String, Setting> index = new LinkedHashMap<String, Setting>();
        Map<String, Object> declaredDefaults = new LinkedHashMap<String, Object>();
        for (Setting setting : this.settings) {
            index.put(setting.key(), setting);
            declaredDefaults.put(setting.key(), setting.defaultValue());
        }
        this.byKey = Collections.unmodifiableMap(index);
        this.defaultsView =
                new ResolvedSettingsView(this, declaredDefaults, SettingsMutationReport.clean());
    }

    /**
     * @param moduleId the owning module id; must be a legal, lower-case, dot-separated id
     * @return a builder for that module's schema
     * @throws XsozContractException if the module id is blank or malformed
     */
    public static Builder builder(String moduleId) {
        return new Builder(moduleId);
    }

    /** @return the owning module id */
    public String moduleId() {
        return moduleId;
    }

    /** @return the declared settings, in declaration order, unmodifiable */
    public List<Setting> settings() {
        return settings;
    }

    /** @return the declared setting keys, in declaration order */
    public List<String> keys() {
        return Collections.unmodifiableList(new ArrayList<String>(byKey.keySet()));
    }

    /**
     * @param key the setting key
     * @return the declared setting, or empty when this schema does not declare it
     */
    public Optional<Setting> byKey(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /**
     * A view of the declared defaults, with a clean report.
     *
     * @return a complete view holding every declared default
     */
    public SettingsView defaultsView() {
        return defaultsView;
    }

    /**
     * Builds a view from stored values, coercing and reporting rather than throwing.
     *
     * @param storedValues the raw stored values, keyed by setting key; may be {@code null}
     *                     or empty
     * @return a complete, fully-valid view; the report says what was dropped, coerced,
     *         reset or truncated
     */
    public SettingsView view(Map<String, Object> storedValues) {
        Map<String, Object> stored = storedValues == null
                ? Collections.<String, Object>emptyMap() : storedValues;
        List<String> dropped = new ArrayList<String>();
        Map<String, String> coerced = new LinkedHashMap<String, String>();
        List<String> reset = new ArrayList<String>();
        List<String> truncated = new ArrayList<String>();

        for (String key : stored.keySet()) {
            if (!byKey.containsKey(key)) {
                dropped.add(key + ": not declared by module " + moduleId
                        + ", so the entry was dropped");
            }
        }

        Map<String, Object> resolved = new LinkedHashMap<String, Object>();
        for (Setting setting : settings) {
            String key = setting.key();
            if (!stored.containsKey(key)) {
                reset.add(key + ": absent from the stored values, using the declared default "
                        + SettingReportText.of(setting, setting.defaultValue()));
                resolved.put(key, setting.defaultValue());
                continue;
            }
            Outcome outcome = load(setting, stored.get(key), coerced, truncated);
            if (outcome.usable) {
                resolved.put(key, outcome.value);
            } else {
                reset.add(key + ": " + outcome.reason + ", using the declared default "
                        + SettingReportText.of(setting, setting.defaultValue()));
                resolved.put(key, setting.defaultValue());
            }
        }

        return new ResolvedSettingsView(this, resolved, new SettingsMutationReport(
                dropped, coerced, reset, truncated));
    }

    /**
     * The strict pass: every declared key valid, no undeclared key.
     *
     * @param raw the raw stored values
     * @throws SettingValidationException on the first problem, naming the key and what
     *                                   was expected
     */
    public void validateAll(Map<String, Object> raw) {
        Map<String, Object> stored = raw == null ? Collections.<String, Object>emptyMap() : raw;
        for (String key : stored.keySet()) {
            if (!byKey.containsKey(key)) {
                throw new SettingValidationException(key,
                        "not declared by module " + moduleId + ". Declared keys: " + keys()
                                + ". A strict pass refuses an undeclared key rather than "
                                + "dropping it, because the caller asked for exactness.");
            }
        }
        for (Setting setting : settings) {
            String key = setting.key();
            if (!stored.containsKey(key)) {
                throw new SettingValidationException(key,
                        "absent. This is a strict pass: every declared key must be present.");
            }
            setting.validate(stored.get(key));
        }
    }

    // ---- loading -------------------------------------------------------------------------------

    /** The outcome of loading one setting: either a usable value or a reason it was not. */
    private static final class Outcome {
        private final boolean usable;
        private final Object value;
        private final String reason;

        private Outcome(boolean usable, Object value, String reason) {
            this.usable = usable;
            this.value = value;
            this.reason = reason;
        }

        static Outcome of(Object value) {
            return new Outcome(true, value, null);
        }

        static Outcome rejected(String reason) {
            return new Outcome(false, null, reason);
        }
    }

    private static Outcome load(Setting setting, Object rawValue,
                                Map<String, String> coerced, List<String> truncated) {
        String key = setting.key();
        switch (setting.kind()) {
            case BOOLEAN:
                return loadBoolean((BooleanSetting) setting, rawValue, coerced);
            case INT:
                return loadInt((IntSetting) setting, rawValue, coerced);
            case DOUBLE:
                return loadDouble((DoubleSetting) setting, rawValue, coerced);
            case ENUM:
                return loadEnum((EnumSetting<?>) setting, rawValue, coerced);
            case STRING:
                return loadString((StringSetting) setting, rawValue, coerced, truncated);
            case COLOR:
                return loadColor((ColorSetting) setting, rawValue, coerced);
            case KEYBIND:
                return loadKeybind((KeybindSetting) setting, rawValue);
            case LIST:
                return loadList((ListSetting<?>) setting, rawValue, coerced, truncated);
            default:
                return Outcome.rejected("no loader for kind " + setting.kind());
        }
    }

    private static Outcome loadBoolean(BooleanSetting setting, Object rawValue,
                                       Map<String, String> coerced) {
        if (rawValue instanceof Boolean) {
            return Outcome.of(rawValue);
        }
        if (rawValue instanceof Number) {
            double asDouble = ((Number) rawValue).doubleValue();
            if (asDouble == 0.0d) {
                coerced.put(setting.key(), "stored the number 0 for a boolean, read it as false");
                return Outcome.of(Boolean.FALSE);
            }
            if (asDouble == 1.0d) {
                coerced.put(setting.key(), "stored the number 1 for a boolean, read it as true");
                return Outcome.of(Boolean.TRUE);
            }
        }
        return Outcome.rejected("expected a boolean, found " + describe(rawValue));
    }

    private static Outcome loadInt(IntSetting setting, Object rawValue,
                                   Map<String, String> coerced) {
        if (!(rawValue instanceof Number)) {
            return Outcome.rejected("expected a whole number, found " + describe(rawValue));
        }
        double asDouble = ((Number) rawValue).doubleValue();
        if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)) {
            return Outcome.rejected("expected a finite number, found " + asDouble);
        }
        if (asDouble != Math.rint(asDouble)
                || asDouble < Integer.MIN_VALUE || asDouble > Integer.MAX_VALUE) {
            return Outcome.rejected("expected a whole number that fits in 32 bits, found "
                    + rawValue + ". A setting declared INT is not rounded on load");
        }
        int value = (int) asDouble;
        if (value < setting.minInclusive() || value > setting.maxInclusive()) {
            int clamped = setting.coerce(value);
            coerced.put(setting.key(), "outside [" + setting.minInclusive() + ", "
                    + setting.maxInclusive() + "] " + setting.unit() + "; clamped and snapped to "
                    + clamped);
            return Outcome.of(Integer.valueOf(clamped));
        }
        int snapped = setting.coerce(value);
        if (snapped != value) {
            coerced.put(setting.key(), "not on the declared step of " + setting.step() + " "
                    + setting.unit() + "; snapped to " + snapped);
            return Outcome.of(Integer.valueOf(snapped));
        }
        return Outcome.of(Integer.valueOf(value));
    }

    private static Outcome loadDouble(DoubleSetting setting, Object rawValue,
                                      Map<String, String> coerced) {
        if (!(rawValue instanceof Number)) {
            return Outcome.rejected("expected a number, found " + describe(rawValue));
        }
        double value = ((Number) rawValue).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Outcome.rejected("expected a finite number, found " + value);
        }
        if (value < setting.minInclusive() || value > setting.maxInclusive()) {
            double clamped = setting.coerce(value);
            coerced.put(setting.key(), "outside [" + setting.minInclusive() + ", "
                    + setting.maxInclusive() + "] " + setting.unit() + "; clamped and snapped to "
                    + clamped);
            return Outcome.of(Double.valueOf(clamped));
        }
        double snapped = setting.coerce(value);
        if (snapped != value) {
            coerced.put(setting.key(), "not on the declared step of " + setting.step() + " "
                    + setting.unit() + "; snapped to " + snapped);
            return Outcome.of(Double.valueOf(snapped));
        }
        return Outcome.of(Double.valueOf(value));
    }

    private static Outcome loadEnum(EnumSetting<?> setting, Object rawValue,
                                    Map<String, String> coerced) {
        if (setting.type().isInstance(rawValue)) {
            return Outcome.of(rawValue);
        }
        if (rawValue instanceof String) {
            String name = (String) rawValue;
            Optional<?> resolved = setting.byName(name);
            if (resolved.isPresent()) {
                coerced.put(setting.key(), "stored the name \"" + name
                        + "\", resolved it to the constant of the same name");
                return Outcome.of(resolved.get());
            }
            return Outcome.rejected("\"" + name + "\" is not one of " + setting.permittedValues());
        }
        return Outcome.rejected("expected a constant of " + setting.type().getSimpleName()
                + ", found " + describe(rawValue));
    }

    private static Outcome loadString(StringSetting setting, Object rawValue,
                                      Map<String, String> coerced, List<String> truncated) {
        if (!(rawValue instanceof String)) {
            return Outcome.rejected("expected text, found " + describe(rawValue));
        }
        String text = (String) rawValue;
        String shortened = setting.truncateToMaxLength(text);
        if (!shortened.equals(text)) {
            truncated.add(setting.key() + ": " + text.codePointCount(0, text.length())
                    + " code points exceeds maxLength " + setting.maxLength()
                    + "; shortened to the first " + setting.maxLength());
        }
        try {
            setting.validate(shortened);
        } catch (SettingValidationException e) {
            return Outcome.rejected(e.getMessage());
        }
        if (shortened.equals(text)) {
            return Outcome.of(text);
        }
        return Outcome.of(shortened);
    }

    private static Outcome loadColor(ColorSetting setting, Object rawValue,
                                     Map<String, String> coerced) {
        if (!(rawValue instanceof Number)) {
            return Outcome.rejected("expected a packed 0xAARRGGBB colour, found "
                    + describe(rawValue));
        }
        int argb;
        try {
            argb = ColorSetting.unsignedArgbOf(setting.key(), rawValue);
        } catch (SettingValidationException e) {
            return Outcome.rejected(e.getMessage());
        }
        try {
            setting.validate(Integer.valueOf(argb));
        } catch (SettingValidationException e) {
            return Outcome.rejected(e.getMessage());
        }
        int forced = setting.forceAlpha(argb);
        if (forced != argb) {
            coerced.put(setting.key(), "alpha was " + ColorSetting.alphaOf(argb)
                    + " and this setting forbids alpha; forced to 0xFF");
            return Outcome.of(Integer.valueOf(forced));
        }
        return Outcome.of(Integer.valueOf(argb));
    }

    private static Outcome loadKeybind(KeybindSetting setting, Object rawValue) {
        try {
            setting.validate(rawValue);
        } catch (SettingValidationException e) {
            return Outcome.rejected(e.getMessage());
        }
        return Outcome.of(rawValue);
    }

    private static Outcome loadList(ListSetting<?> setting, Object rawValue,
                                    Map<String, String> coerced, List<String> truncated) {
        if (!(rawValue instanceof List)) {
            return Outcome.rejected("expected a list, found " + describe(rawValue));
        }
        List<?> rawList = (List<?>) rawValue;
        int dropped = setting.droppedCount(rawList);
        List<Object> list = setting.truncateToMaxSize(rawList);
        if (dropped > 0) {
            truncated.add(setting.key() + ": " + rawList.size() + " entries exceeds maxSize "
                    + setting.maxSize() + "; kept the first " + setting.maxSize() + " and dropped "
                    + dropped + " from the end");
        }
        try {
            setting.validate(list);
        } catch (SettingValidationException e) {
            return Outcome.rejected(e.getMessage());
        }
        if (dropped > 0) {
            coerced.put(setting.key(), "truncated from the end to the declared maxSize of "
                    + setting.maxSize());
        }
        return Outcome.of(Collections.unmodifiableList(new ArrayList<Object>(list)));
    }

    private static String describe(Object rawValue) {
        if (rawValue == null) {
            return "nothing";
        }
        return rawValue.getClass().getSimpleName() + " (" + rawValue + ")";
    }

    // ---- building ------------------------------------------------------------------------------

    /** Assembles one module's {@link SettingsSchema}, appending in declaration order. */
    public static final class Builder {

        private final String moduleId;
        private final List<Setting> declared = new ArrayList<Setting>();

        private Builder(String moduleId) {
            if (moduleId == null || moduleId.trim().isEmpty()) {
                throw new XsozContractException(
                        "A settings schema needs its module id. contracts.md C4.1: a setting key "
                                + "is \"<moduleId>.<settingName>\", so the schema knows the half it "
                                + "must prefix.");
            }
            String trimmed = moduleId.trim();
            if (!trimmed.matches(Setting.KEY_GRAMMAR)) {
                throw new XsozContractException(
                        "\"" + trimmed + "\" is not a legal module id. Grammar: "
                                + Setting.KEY_GRAMMAR);
            }
            this.moduleId = trimmed;
        }

        /**
         * Appends one setting.
         *
         * @param setting the setting; its key must be prefixed with this schema's module id
         * @return this builder
         * @throws XsozContractException if the key does not belong to this module or is
         *                                already declared
         */
        public Builder add(Setting setting) {
            if (setting == null) {
                throw new XsozContractException("A schema cannot declare a null setting.");
            }
            String prefix = moduleId + ".";
            if (!setting.key().startsWith(prefix)) {
                throw new XsozContractException(
                        "Setting \"" + setting.key() + "\" does not belong to module \"" + moduleId
                                + "\". A setting key is \"<moduleId>.<settingName>\" (C4.1), and "
                                + "enforcing the prefix is what stops two modules both declaring \""
                                + setting.key() + "\" and one of them silently winning at load.");
            }
            for (Setting existing : declared) {
                if (existing.key().equals(setting.key())) {
                    throw new XsozContractException(
                            "Setting \"" + setting.key() + "\" is declared twice in module \""
                                    + moduleId + "\". Keys are globally unique (C4.1).");
                }
            }
            declared.add(setting);
            return this;
        }

        /** @return the assembled, immutable schema */
        public SettingsSchema build() {
            return new SettingsSchema(moduleId, declared);
        }
    }
}
