package dev.xsoz.core.config;

import dev.xsoz.core.XsozContractException;
import dev.xsoz.core.keybind.Keybind;
import dev.xsoz.core.keybind.ModifierKey;
import dev.xsoz.core.setting.JsonObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The profile file codec (contracts.md C5.2).
 *
 * <p><strong>Key order in the emitted document is the C5.2 order</strong> - schema, id,
 * name, createdUtc, modifiedUtc, isDefault, provenance, modules, keybinds, hud,
 * sensitivity, complianceProfileId, coaching - so two equal profiles produce identical
 * bytes and a diff of two saved profiles shows a real change.</p>
 *
 * <p><strong>Fields are appended, never removed or reused</strong> (C7.2, the
 * forward-compatibility rule from the {@code xsoz:*} spec applied to the schema). A newer
 * document carries unknown fields this reader ignores; an older reader reading a newer
 * document still finds every field it knows.</p>
 *
 * <p><strong>One schema error is refused outright rather than coerced:</strong> a
 * {@code sensitivityRaw} key anywhere (C6.1 - the canonical stored quantity is
 * {@code cmPer360} and a raw slider percentage is never persisted). Everything else is a
 * load-time coercion with a report.</p>
 *
 * <p><strong>This codec is a pure function of the document.</strong> It knows nothing about
 * which modules exist; {@link ProfileLoader} holds the {@link ModuleCatalog} and is the only
 * component that can say "this key names a module I do not have".</p>
 *
 * <p>Zero dependencies: the JSON model is {@link JsonObject}, hand-written for the same
 * reason {@code :core} has no Gson.</p>
 */
public final class ProfileJson {

    /** The value of the {@code key} field that means "not bound to anything". */
    public static final String UNBOUND_KEY_NAME = "UNKNOWN";

    private ProfileJson() {
        throw new AssertionError("ProfileJson is a codec and is not instantiable.");
    }

    // ================================================================================================
    // Encoding
    // ================================================================================================

    /**
     * @param profile the profile to serialise
     * @return the document, in C5.2 key order
     * @throws XsozContractException if the profile is {@code null}
     */
    public static JsonObject encode(Profile profile) {
        if (profile == null) {
            throw new XsozContractException("ProfileJson.encode requires a profile.");
        }
        return JsonObject.builder()
                .put(MigrationRegistry.SCHEMA_KEY, MigrationRegistry.CURRENT_VERSION)
                .put("id", profile.id())
                .put("name", profile.name())
                .put("createdUtc", profile.createdUtc().toString())
                .put("modifiedUtc", profile.modifiedUtc().toString())
                .put("isDefault", profile.isDefault())
                .put("provenance", encodeProvenance(profile.provenance()))
                .put("modules", encodeModules(profile.modules()))
                .put("keybinds", encodeKeybinds(profile.keybinds()))
                .put("hud", encodeHud(profile.hud()))
                .put("sensitivity", encodeSensitivity(profile.sensitivity()))
                .put("complianceProfileId", profile.complianceProfileId())
                .put("coaching", encodeCoaching(profile.coaching()))
                .build();
    }

    private static JsonObject encodeProvenance(Provenance provenance) {
        JsonObject.Builder builder = JsonObject.builder().put("kind", provenance.kind().name());
        provenance.optSourceLabel().ifPresent(label -> builder.put("sourceLabel", label));
        provenance.optSourceUrl().ifPresent(url -> builder.put("sourceUrl", url));
        provenance.optRetrievedOn()
                .ifPresent(date -> builder.put("retrievedUtc", MigrationRegistry.renderDate(date)));
        return builder.put("note", provenance.note()).build();
    }

    private static JsonObject encodeModules(Map<String, ModuleState> modules) {
        JsonObject.Builder builder = JsonObject.builder();
        for (Map.Entry<String, ModuleState> entry : modules.entrySet()) {
            ModuleState state = entry.getValue();
            JsonObject.Builder settingsBuilder = JsonObject.builder();
            for (Map.Entry<String, Object> setting : state.settings().entrySet()) {
                settingsBuilder.put(setting.getKey(), toJsonValue(setting.getValue()));
            }
            builder.put(entry.getKey(), JsonObject.builder()
                    .put("enabled", state.requestedEnabled())
                    .put("settings", settingsBuilder.build())
                    .build());
        }
        return builder.build();
    }

    private static JsonObject encodeKeybinds(Map<String, Keybind> keybinds) {
        JsonObject.Builder builder = JsonObject.builder();
        for (Map.Entry<String, Keybind> entry : keybinds.entrySet()) {
            builder.put(entry.getKey(), jsonValueOf(Profile.encodeKeybind(entry.getValue())));
        }
        return builder.build();
    }

    private static JsonObject encodeHud(HudLayout hud) {
        JsonObject.Builder builder = JsonObject.builder().put("scale", hud.scale());
        List<Object> elements = new ArrayList<Object>();
        for (HudElement element : hud.elements()) {
            elements.add(JsonObject.builder()
                    .put("id", element.id())
                    .put("anchor", element.anchor().name())
                    .put("offsetXGu", element.offsetXGu())
                    .put("offsetYGu", element.offsetYGu())
                    .put("z", element.z())
                    .put("order", element.order())
                    .put("visible", element.visible())
                    .build());
        }
        return builder.putArray("elements", elements).build();
    }

    private static JsonObject encodeSensitivity(ProfileSensitivity sensitivity) {
        return JsonObject.builder()
                .put("cmPer360", sensitivity.cmPer360())
                .put("mouseDpi", sensitivity.mouseDpi())
                .put("fovVertical", sensitivity.fovVerticalDeg())
                .put("fovRelativeMode", sensitivity.fovRelativeMode().name())
                .put("rampStageIndex", sensitivity.rampStageIndex())
                .build();
    }

    private static JsonObject encodeCoaching(CoachingState coaching) {
        JsonObject.Builder builder = JsonObject.builder();
        builder.putArray("armedAddresses", CoachingState.toList(coaching.armedAddresses()));
        builder.put("autoPrune", coaching.autoPrune());
        builder.put("chatCapture", coaching.chatCapture());
        if (coaching.mouseDebounceMillis().isPresent()) {
            builder.put("mouseDebounceMillis", coaching.mouseDebounceMillis().getAsLong());
        } else {
            builder.put("mouseDebounceMillis", null);
        }
        return builder.build();
    }

    // ================================================================================================
    // Decoding
    // ================================================================================================

    /**
     * Decodes a profile, applying only the checks the codec itself owns.
     *
     * @param document the profile document
     * @return the decoded profile
     * @throws XsozContractException if a field is absent, malformed, or names a value
     *                               outside its declared domain
     */
    public static Profile decode(JsonObject document) {
        if (document == null) {
            throw new XsozContractException("ProfileJson.decode requires a document.");
        }
        if (document.has("sensitivityRaw")) {
            throw new XsozContractException(
                    "This profile carries a top-level \"sensitivityRaw\" key. C6.1: the canonical "
                            + "stored quantity is cmPer360 and a raw slider percentage is never "
                            + "persisted. Two numbers describing one physical fact is how this "
                            + "project produced a 5.8x error and then a 6.667x one.");
        }
        JsonObject sensitivity = document.optObject("sensitivity").orElse(null);
        if (sensitivity != null && sensitivity.has("sensitivityRaw")) {
            throw new XsozContractException(
                    "This profile's sensitivity block carries a \"sensitivityRaw\" key. C6.1: the "
                            + "canonical stored quantity is cmPer360; a raw percentage is a schema "
                            + "error, reported and dropped.");
        }
        return Profile.builder(document.getString("id"))
                .name(document.getString("name"))
                .createdUtc(parseInstant(document, "createdUtc"))
                .modifiedUtc(parseInstant(document, "modifiedUtc"))
                .isDefault(document.getBoolean("isDefault", false))
                .provenance(decodeProvenance(document.getObject("provenance")))
                .modules(decodeModules(document.getObject("modules")))
                .keybinds(decodeKeybinds(document.getObject("keybinds")))
                .hud(decodeHud(document.getObject("hud")))
                .sensitivity(decodeSensitivity(document.getObject("sensitivity")))
                .complianceProfileId(document.getString("complianceProfileId",
                        Profile.DEFAULT_DENY_COMPLIANCE_PROFILE_ID))
                .coaching(decodeCoaching(document.getObject("coaching")))
                .build();
    }

    private static Provenance decodeProvenance(JsonObject object) {
        ProvenanceKind kind;
        try {
            kind = ProvenanceKind.valueOf(object.getString("kind"));
        } catch (IllegalArgumentException e) {
            throw new XsozContractException(
                    "Unknown provenance kind \"" + object.raw("kind") + "\". Permitted: "
                            + java.util.Arrays.toString(ProvenanceKind.values()) + ".");
        }
        if (kind == ProvenanceKind.USER_DEFINED) {
            return Provenance.userDefined(object.getString("note"));
        }
        LocalDate retrievedOn;
        try {
            retrievedOn = LocalDate.parse(object.getString("retrievedUtc"));
        } catch (DateTimeParseException e) {
            throw new XsozContractException(
                    "provenance.retrievedUtc is \"" + object.raw("retrievedUtc")
                            + "\", which is not an ISO-8601 date. C0.5: a citation without its "
                            + "retrieval date is a lie about how fresh it is.");
        }
        return Provenance.sourced(kind, object.getString("sourceLabel"),
                object.getString("sourceUrl"), retrievedOn, object.getString("note"));
    }

    private static Map<String, ModuleState> decodeModules(JsonObject object) {
        Map<String, ModuleState> modules = new LinkedHashMap<String, ModuleState>();
        for (String moduleId : object.keys()) {
            JsonObject entry = object.getObject(moduleId);
            Map<String, Object> settings = new LinkedHashMap<String, Object>();
            JsonObject storedSettings = entry.optObject("settings").orElse(JsonObject.empty());
            for (String key : storedSettings.keys()) {
                settings.put(key, narrowIntegral(storedSettings.raw(key)));
            }
            modules.put(moduleId, ModuleState.of(entry.getBoolean("enabled", false), settings));
        }
        return modules;
    }

    /**
     * Narrows a decoded integral number to the smallest lossless box.
     *
     * <p><strong>JSON has ONE number type, so every integral value arrives as a
     * {@link Long}.</strong> A value written by Java as an {@code Integer} would therefore
     * read back as a {@code Long}, and the two would compare unequal while being the same
     * number - which makes a round-trip assertion fail for no reason a player could see, and
     * teaches a reader to distrust equality.</p>
     *
     * <p>The narrowing is <em>lossless</em>: a larger integral value keeps its
     * {@code Long} and a fractional one keeps its {@code Double}, because narrowing those
     * would change the number.</p>
     *
     * <p>This is an explicit serialisation mapping, which is what C3.2 requires. The
     * Java type is a function of the value alone, so the mapping is total and
     * reversible.</p>
     *
     * @param value a decoded JSON scalar
     * @return the narrowest box that holds the same value
     */
    static Object narrowIntegral(Object value) {
        if (!(value instanceof Long)) {
            return value;
        }
        long asLong = (Long) value;
        if (asLong >= Integer.MIN_VALUE && asLong <= Integer.MAX_VALUE) {
            return Integer.valueOf((int) asLong);
        }
        return value;
    }

    private static Map<String, Keybind> decodeKeybinds(JsonObject object) {
        Map<String, Keybind> keybinds = new LinkedHashMap<String, Keybind>();
        for (String actionId : object.keys()) {
            keybinds.put(actionId, decodeKeybindEntry(object.getObject(actionId)));
        }
        return keybinds;
    }

    /**
     * Decodes one {@code keybinds} entry.
     *
     * @param entry the entry object
     * @return the decoded bind; {@link Keybind#unbound()} for the {@code "UNKNOWN"} form, which
     *         is a stored state rather than a malformed field
     * @throws XsozContractException if the kind or a modifier name is not one this build knows
     */
    static Keybind decodeKeybindEntry(JsonObject entry) {
        Set<ModifierKey> modifiers = EnumSet.noneOf(ModifierKey.class);
        for (String name : entry.getStringArray("modifiers")) {
            try {
                modifiers.add(ModifierKey.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new XsozContractException(
                        "A stored keybind names the unknown modifier \"" + name + "\". Permitted: "
                                + java.util.Arrays.toString(ModifierKey.values()) + ".");
            }
        }
        return Profile.decodeKeybind(
                entry.getString("key", UNBOUND_KEY_NAME),
                entry.has("kind") && entry.raw("kind") != null ? entry.getString("kind") : null,
                entry.getInt("code", 0),
                modifiers);
    }

    private static HudLayout decodeHud(JsonObject object) {
        List<HudElement> elements = new ArrayList<HudElement>();
        for (Object raw : object.getArray("elements")) {
            if (!(raw instanceof JsonObject)) {
                throw new XsozContractException(
                        "hud.elements must hold objects, found "
                                + (raw == null ? "null" : raw.getClass().getSimpleName()) + ".");
            }
            JsonObject element = (JsonObject) raw;
            HudAnchor anchor;
            try {
                anchor = HudAnchor.valueOf(element.getString("anchor"));
            } catch (IllegalArgumentException e) {
                throw new XsozContractException(
                        "HUD element \"" + element.getString("id") + "\" names the unknown anchor \""
                                + element.raw("anchor") + "\". Permitted: "
                                + java.util.Arrays.toString(HudAnchor.values()) + ".");
            }
            try {
                elements.add(HudElement.of(
                        element.getString("id"),
                        anchor,
                        element.getDouble("offsetXGu", 0.0d),
                        element.getDouble("offsetYGu", 0.0d),
                        element.getInt("z", HudElement.MIN_Z),
                        element.getInt("order", HudElement.MIN_ORDER),
                        element.getBoolean("visible", true)));
            } catch (XsozContractException e) {
                throw new XsozContractException(
                        "HUD element is outside its declared range (C5.2: z 0..1000, order 0..9999): "
                                + e.getMessage());
            }
        }
        return HudLayout.of(object.getDouble("scale", HudLayout.DEFAULT_SCALE), elements);
    }

    private static ProfileSensitivity decodeSensitivity(JsonObject object) {
        double cmPer360 = object.getDouble("cmPer360", Double.NaN);
        int mouseDpi = object.getInt("mouseDpi", 0);
        int fovVertical = object.getInt("fovVertical", 0);
        dev.xsoz.core.sensitivity.FovRelativeMode mode;
        try {
            mode = dev.xsoz.core.sensitivity.FovRelativeMode
                    .valueOf(object.getString("fovRelativeMode", "PHYSICAL"));
        } catch (IllegalArgumentException e) {
            throw new XsozContractException(
                    "sensitivity.fovRelativeMode is \"" + object.raw("fovRelativeMode")
                            + "\". Permitted: " + java.util.Arrays.toString(
                            dev.xsoz.core.sensitivity.FovRelativeMode.values()) + ".");
        }
        int rampStageIndex = object.getInt("rampStageIndex",
                dev.xsoz.core.sensitivity.SensitivityRamp.RAMP_INACTIVE_STAGE_INDEX);
        try {
            if (rampStageIndex < 0) {
                return ProfileSensitivity.ofCmPer360(cmPer360, mouseDpi, fovVertical, mode);
            }
            return ProfileSensitivity.withRampStage(cmPer360, mouseDpi, fovVertical, mode,
                    rampStageIndex);
        } catch (XsozContractException e) {
            throw new XsozContractException("sensitivity is out of range: " + e.getMessage());
        }
    }

    private static CoachingState decodeCoaching(JsonObject object) {
        Set<String> addresses = new LinkedHashSet<String>(object.getStringArray("armedAddresses"));
        boolean chatCapture = object.getBoolean("chatCapture", false);
        // A file CAN switch chat capture on, because the player may have switched it on in
        // their own profile. What is forbidden is a profile that switches it on by itself:
        // the shipped default carries false, no migration sets it, and the import path forces
        // it off. This decoder only refuses the impossible.
        OptionalLong debounce = object.has("mouseDebounceMillis")
                && object.raw("mouseDebounceMillis") != null
                ? OptionalLong.of(object.getInt("mouseDebounceMillis", 0))
                : OptionalLong.empty();
        try {
            return CoachingState.of(addresses, object.getBoolean("autoPrune", true), chatCapture,
                    debounce);
        } catch (XsozContractException e) {
            throw new XsozContractException("coaching is out of range: " + e.getMessage());
        }
    }

    // ================================================================================================
    // Value marshalling
    // ================================================================================================

    /**
     * Maps a stored setting value to its JSON form.
     *
     * <p>An explicit mapping, never reflection (C3.2). A value type with no JSON form is a
     * programming error and says so rather than serialising something the reader cannot
     * reconstruct.</p>
     *
     * @param value the stored value
     * @return the JSON form, or the value itself when it already is one
     * @throws XsozContractException if the value has no JSON form
     */
    static Object toJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Number || value instanceof JsonObject) {
            return value;
        }
        if (value instanceof Map) {
            JsonObject.Builder builder = JsonObject.builder();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                builder.put(String.valueOf(entry.getKey()), toJsonValue(entry.getValue()));
            }
            return builder.build();
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object element : (List<?>) value) {
                out.add(toJsonValue(element));
            }
            return out;
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        throw new XsozContractException(
                "A stored setting value of type " + value.getClass().getName()
                        + " has no JSON form. Serialisation of a setting value is an explicit "
                        + "mapping, never reflection (C3.2).");
    }

    private static JsonObject jsonValueOf(Map<String, Object> map) {
        JsonObject.Builder builder = JsonObject.builder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            builder.put(entry.getKey(), toJsonValue(entry.getValue()));
        }
        return builder.build();
    }

    private static Instant parseInstant(JsonObject document, String key) {
        String raw = document.getString(key);
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new XsozContractException(
                    "\"" + key + "\" is \"" + raw + "\", which is not an ISO-8601 instant. C5.2 "
                            + "spells it Instant, e.g. 2026-09-29T00:00:00Z.");
        }
    }

    /**
     * @param settings a decoded settings map
     * @return the map with integral values narrowed, for a caller holding raw JSON values
     */
    static Map<String, Object> narrowSettings(Map<String, Object> settings) {
        if (settings == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> narrowed = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            narrowed.put(entry.getKey(), narrowIntegral(entry.getValue()));
        }
        return narrowed;
    }
}
