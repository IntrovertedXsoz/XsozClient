package dev.xsoz.core.config;

import dev.xsoz.core.XsozContractException;
import dev.xsoz.core.keybind.InputKey;
import dev.xsoz.core.keybind.InputKeyKind;
import dev.xsoz.core.keybind.Keybind;
import dev.xsoz.core.keybind.ModifierKey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One settings profile (contracts.md C5.2).
 *
 * <p>A profile is the whole of what a player configures: which modules are requested on,
 * their settings, the keybinds, the HUD layout, the sensitivity, the compliance profile
 * id and the coaching arming. Switching profiles is therefore a full re-resolve, not a
 * partial reload (C2.6).</p>
 *
 * ============================ TWO INVARIANTS, BOTH ASSERTED ============================
 *
 * <p><strong>1. The id is immutable for the life of the profile.</strong> Renaming
 * changes {@code name} and nothing else. The id is what the file, the bindings and the
 * {@code lastProfileId} pointer are keyed by, and a rename that moved it would orphan
 * every reference to it.</p>
 *
 * <p><strong>2. Every key in {@code modules} and {@code keybinds} is a declared id.</strong>
 * The constructor rejects a map with a null value, and {@link Builder} is the only way in,
 * so a {@code Profile} in hand always has a value for every key it names.</p>
 *
 * <p><strong>{@code isDefault} is a store-level post-condition, not a constructor
 * invariant.</strong> Exactly one profile carries it at a time, which is a fact about the
 * <em>set</em> and not about any one profile; the store asserts it after every mutation
 * (C5.2).</p>
 *
 * <p>Deeply immutable: final fields, defensive copies in, unmodifiable collections out.
 * Every {@code with} method returns a copy.</p>
 */
public final class Profile {

    /** The compliance profile every store falls back to (C5.2, C5.5 step 5). */
    public static final String DEFAULT_DENY_COMPLIANCE_PROFILE_ID = "default-deny";

    private final String id;
    private final String name;
    private final Instant createdUtc;
    private final Instant modifiedUtc;
    private final boolean isDefault;
    private final Provenance provenance;
    private final Map<String, ModuleState> modules;
    private final Map<String, Keybind> keybinds;
    private final HudLayout hud;
    private final ProfileSensitivity sensitivity;
    private final String complianceProfileId;
    private final CoachingState coaching;

    private Profile(Builder builder) {
        this.id = ProfileNames.requireValidId(builder.id);
        this.name = ProfileNames.requireValidName(builder.name);
        this.createdUtc = Objects.requireNonNull(builder.createdUtc, "createdUtc must not be null");
        this.modifiedUtc = Objects.requireNonNull(builder.modifiedUtc, "modifiedUtc must not be null");
        this.isDefault = builder.isDefault;
        this.provenance = Objects.requireNonNull(builder.provenance, "provenance must not be null");
        this.modules = Collections.unmodifiableMap(
                new LinkedHashMap<String, ModuleState>(builder.modules));
        this.keybinds = Collections.unmodifiableMap(
                new LinkedHashMap<String, Keybind>(builder.keybinds));
        this.hud = Objects.requireNonNull(builder.hud, "hud must not be null");
        this.sensitivity = Objects.requireNonNull(builder.sensitivity, "sensitivity must not be null");
        this.complianceProfileId = requireComplianceProfileId(builder.complianceProfileId);
        this.coaching = Objects.requireNonNull(builder.coaching, "coaching must not be null");
    }

    private static String requireComplianceProfileId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new XsozContractException(
                    "A profile needs a complianceProfileId. C5.2: it must name a profile in the "
                            + "locally bundled set, and the fallback \"" + DEFAULT_DENY_COMPLIANCE_PROFILE_ID
                            + "\" is the strictest known behaviour.");
        }
        return raw.trim();
    }

    /**
     * @param id the profile id; frozen for the life of the profile
     * @return a builder for a profile with that id
     * @throws XsozContractException if the id is malformed
     */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** @return the immutable profile id */
    public String id() {
        return id;
    }

    /** @return the human name */
    public String name() {
        return name;
    }

    /** @return when the profile was created. Unit: ISO-8601 instant, UTC. */
    public Instant createdUtc() {
        return createdUtc;
    }

    /**
     * @return when the profile was last written. Unit: ISO-8601 instant, UTC. C5.2: this
     *         is written on every save.
     */
    public Instant modifiedUtc() {
        return modifiedUtc;
    }

    /**
     * @return whether this is the profile with {@code isDefault == true}. C5.2: exactly
     *         one profile carries this at all times; the store asserts it after every
     *         mutation.
     */
    public boolean isDefault() {
        return isDefault;
    }

    /** @return where the profile's numbers came from; never null */
    public Provenance provenance() {
        return provenance;
    }

    /** @return the per-module requested-enabled flags and stored settings, unmodifiable */
    public Map<String, ModuleState> modules() {
        return modules;
    }

    /** @return the keybinds, keyed by action id, unmodifiable */
    public Map<String, Keybind> keybinds() {
        return keybinds;
    }

    /** @return the HUD layout */
    public HudLayout hud() {
        return hud;
    }

    /**
     * @return the stored sensitivity block, carrying the canonical {@code cmPer360} and
     *         the adaptation ramp's position
     */
    public ProfileSensitivity sensitivity() {
        return sensitivity;
    }

    /**
     * @return the compliance profile id, or {@link #DEFAULT_DENY_COMPLIANCE_PROFILE_ID}
     *         when the store forced the fallback
     */
    public String complianceProfileId() {
        return complianceProfileId;
    }

    /** @return the coaching arming state */
    public CoachingState coaching() {
        return coaching;
    }

    // ---- withers ------------------------------------------------------------------------------

    /**
     * @param newName the new name; trimmed and validated, uniqueness is the store's job
     * @return a copy with the name changed. <strong>The id is unchanged</strong> (C5.2).
     */
    public Profile withName(String newName) {
        return toBuilder().name(newName).build();
    }

    /**
     * @param newModified the new modification instant
     * @return a copy with {@code modifiedUtc} changed
     */
    public Profile withModifiedUtc(Instant newModified) {
        return toBuilder().modifiedUtc(newModified).build();
    }

    /**
     * @param newDefault the new default flag
     * @return a copy with the flag changed
     */
    public Profile withDefault(boolean newDefault) {
        return toBuilder().isDefault(newDefault).build();
    }

    /**
     * @param newProvenance the new provenance
     * @return a copy with the provenance changed
     */
    public Profile withProvenance(Provenance newProvenance) {
        return toBuilder().provenance(newProvenance).build();
    }

    /**
     * @param newModules the per-module states; defensively copied
     * @return a copy with the module map replaced
     */
    public Profile withModules(Map<String, ModuleState> newModules) {
        return toBuilder().modules(newModules).build();
    }

    /**
     * @param moduleId the module id
     * @param state    the new state
     * @return a copy with that one module's state replaced
     */
    public Profile withModule(String moduleId, ModuleState state) {
        Map<String, ModuleState> next = new LinkedHashMap<String, ModuleState>(modules);
        next.put(moduleId, state);
        return withModules(next);
    }

    /**
     * @param newKeybinds the keybinds; defensively copied
     * @return a copy with the keybind map replaced
     */
    public Profile withKeybinds(Map<String, Keybind> newKeybinds) {
        return toBuilder().keybinds(newKeybinds).build();
    }

    /**
     * @param newHud the new layout
     * @return a copy with the layout replaced
     */
    public Profile withHud(HudLayout newHud) {
        return toBuilder().hud(newHud).build();
    }

    /**
     * @param newSensitivity the new sensitivity block
     * @return a copy with the sensitivity replaced
     */
    public Profile withSensitivity(ProfileSensitivity newSensitivity) {
        return toBuilder().sensitivity(newSensitivity).build();
    }

    /**
     * @param newComplianceProfileId the new compliance profile id
     * @return a copy with the compliance profile replaced
     */
    public Profile withComplianceProfileId(String newComplianceProfileId) {
        return toBuilder().complianceProfileId(newComplianceProfileId).build();
    }

    /**
     * @param newCoaching the new coaching arming state
     * @return a copy with the coaching state replaced
     */
    public Profile withCoaching(CoachingState newCoaching) {
        return toBuilder().coaching(newCoaching).build();
    }

    /**
     * A deep copy with a <strong>different id</strong>, a new name, a new creation instant
     * and {@code isDefault = false}, as {@code create} and {@code duplicate} both require
     * (C5.6).
     *
     * @param newId        the new id; must be valid and unused
     * @param newName      the new name
     * @param newCreatedUtc the new creation instant
     * @param newProvenance the provenance the copy carries
     * @return the copy
     */
    public Profile copiedAs(String newId, String newName, Instant newCreatedUtc,
                            Provenance newProvenance) {
        return toBuilder()
                .id(newId)
                .name(newName)
                .createdUtc(newCreatedUtc)
                .modifiedUtc(newCreatedUtc)
                .isDefault(false)
                .provenance(newProvenance)
                .build();
    }

    /**
     * @return a builder pre-loaded with this profile's fields, id included
     */
    public Builder toBuilder() {
        return new Builder(id)
                .name(name)
                .createdUtc(createdUtc)
                .modifiedUtc(modifiedUtc)
                .isDefault(isDefault)
                .provenance(provenance)
                .modules(modules)
                .keybinds(keybinds)
                .hud(hud)
                .sensitivity(sensitivity)
                .complianceProfileId(complianceProfileId)
                .coaching(coaching);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Profile)) {
            return false;
        }
        Profile that = (Profile) other;
        return isDefault == that.isDefault
                && id.equals(that.id)
                && name.equals(that.name)
                && createdUtc.equals(that.createdUtc)
                && modifiedUtc.equals(that.modifiedUtc)
                && provenance.equals(that.provenance)
                && modules.equals(that.modules)
                && keybinds.equals(that.keybinds)
                && hud.equals(that.hud)
                && sensitivity.equals(that.sensitivity)
                && complianceProfileId.equals(that.complianceProfileId)
                && coaching.equals(that.coaching);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, createdUtc, modifiedUtc, Boolean.valueOf(isDefault),
                provenance, modules, keybinds, hud, sensitivity, complianceProfileId, coaching);
    }

    @Override
    public String toString() {
        return "Profile[id=" + id + ", name=\"" + name + "\", isDefault=" + isDefault
                + ", modules=" + modules.size() + ", keybinds=" + keybinds.size()
                + ", " + sensitivity + "]";
    }

    /**
     * Decodes the {@code {"key": "...", "kind": "...", "code": N, "modifiers": [...]}}
     * form used by the profile's {@code keybinds} map (C5.2).
     *
     * <p><strong>Why {@code kind} and {@code code} are stored, not just a name.</strong>
     * C5.2's example shows {@code {"key": "RSHIFT", "modifiers": []}}. A name alone cannot
     * be decoded back into a bind without a GLFW-name table, and a table is a second
     * source of truth for what a key is. {@code kind} plus {@code code} round-trips
     * exactly, and the name is carried alongside for the UI.</p>
     *
     * @param keyName  the stored key name, or {@code "UNKNOWN"} for an unbound action
     * @param kind     the key kind name, or {@code null} when unbound
     * @param code     the key code, or 0 when unbound
     * @param modifiers the held modifiers
     * @return the decoded bind
     * @throws XsozContractException if the kind is unknown or the code is out of range
     */
    public static Keybind decodeKeybind(String keyName, String kind, int code,
                                        Collection<ModifierKey> modifiers) {
        if (keyName == null || "UNKNOWN".equals(keyName) || (kind == null && code == 0)) {
            return Keybind.unbound();
        }
        InputKeyKind inputKind;
        try {
            inputKind = InputKeyKind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            throw new XsozContractException(
                    "Stored keybind \"" + keyName + "\" has unknown kind \"" + kind + "\". "
                            + "Permitted: KEYBOARD, MOUSE.");
        } catch (NullPointerException e) {
            throw new XsozContractException(
                    "Stored keybind \"" + keyName + "\" has no kind. An unbound bind is spelled "
                            + "{\"key\": \"UNKNOWN\"}, not a bind with a missing field.");
        }
        String displayName = keyName.trim().isEmpty() ? String.valueOf(code) : keyName.trim();
        InputKey key = inputKind == InputKeyKind.MOUSE
                ? InputKey.mouse(code, displayName)
                : InputKey.keyboard(code, displayName);
        if (modifiers == null || modifiers.isEmpty()) {
            return Keybind.of(key);
        }
        return Keybind.of(key, new ArrayList<ModifierKey>(modifiers));
    }

    /**
     * Renders a bind for the file: the display name, the kind and the code, or
     * {@code "UNKNOWN"} with nulls for an unbound action.
     *
     * @param bind the bind, possibly {@link Keybind#unbound()}
     * @return {@code {keyName, kind, code, modifiers}}, in that insertion order
     */
    public static Map<String, Object> encodeKeybind(Keybind bind) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (bind == null || !bind.isBound()) {
            out.put("key", "UNKNOWN");
            return out;
        }
        out.put("key", bind.key().displayName());
        out.put("kind", bind.key().kind().name());
        out.put("code", Long.valueOf(bind.key().code()));
        Set<ModifierKey> ordered = EnumSet.noneOf(ModifierKey.class);
        ordered.addAll(bind.modifiers());
        List<String> names = new ArrayList<String>();
        for (ModifierKey modifier : ordered) {
            names.add(modifier.name());
        }
        out.put("modifiers", names);
        return out;
    }

    /** Assembles a {@link Profile}. Every field is required except the collections. */
    public static final class Builder {

        private String id;
        private String name;
        private Instant createdUtc;
        private Instant modifiedUtc;
        private boolean isDefault;
        private Provenance provenance;
        private Map<String, ModuleState> modules = Collections.emptyMap();
        private Map<String, Keybind> keybinds = Collections.emptyMap();
        private HudLayout hud = HudLayout.empty();
        private ProfileSensitivity sensitivity;
        private String complianceProfileId = DEFAULT_DENY_COMPLIANCE_PROFILE_ID;
        private CoachingState coaching = CoachingState.disarmed();

        private Builder(String id) {
            this.id = ProfileNames.requireValidId(id);
        }

        /**
         * @param newId a new id, for a copy
         * @return this builder
         */
        public Builder id(String newId) {
            this.id = ProfileNames.requireValidId(newId);
            return this;
        }

        /**
         * @param newName the human name
         * @return this builder
         */
        public Builder name(String newName) {
            this.name = newName;
            return this;
        }

        /**
         * @param newCreatedUtc the creation instant
         * @return this builder
         */
        public Builder createdUtc(Instant newCreatedUtc) {
            this.createdUtc = newCreatedUtc;
            return this;
        }

        /**
         * @param newModifiedUtc the modification instant
         * @return this builder
         */
        public Builder modifiedUtc(Instant newModifiedUtc) {
            this.modifiedUtc = newModifiedUtc;
            return this;
        }

        /**
         * @param newDefault whether this profile is the default one
         * @return this builder
         */
        public Builder isDefault(boolean newDefault) {
            this.isDefault = newDefault;
            return this;
        }

        /**
         * @param newProvenance where the numbers came from
         * @return this builder
         */
        public Builder provenance(Provenance newProvenance) {
            this.provenance = newProvenance;
            return this;
        }

        /**
         * @param newModules the per-module states; defensively copied on build
         * @return this builder
         */
        public Builder modules(Map<String, ModuleState> newModules) {
            this.modules = newModules == null ? Collections.<String, ModuleState>emptyMap()
                    : new LinkedHashMap<String, ModuleState>(newModules);
            return this;
        }

        /**
         * @param newKeybinds the keybinds; defensively copied on build
         * @return this builder
         */
        public Builder keybinds(Map<String, Keybind> newKeybinds) {
            this.keybinds = newKeybinds == null ? Collections.<String, Keybind>emptyMap()
                    : new LinkedHashMap<String, Keybind>(newKeybinds);
            return this;
        }

        /**
         * @param newHud the HUD layout
         * @return this builder
         */
        public Builder hud(HudLayout newHud) {
            this.hud = newHud;
            return this;
        }

        /**
         * @param newSensitivity the sensitivity block
         * @return this builder
         */
        public Builder sensitivity(ProfileSensitivity newSensitivity) {
            this.sensitivity = newSensitivity;
            return this;
        }

        /**
         * @param newComplianceProfileId the compliance profile id
         * @return this builder
         */
        public Builder complianceProfileId(String newComplianceProfileId) {
            this.complianceProfileId = newComplianceProfileId;
            return this;
        }

        /**
         * @param newCoaching the coaching arming state
         * @return this builder
         */
        public Builder coaching(CoachingState newCoaching) {
            this.coaching = newCoaching;
            return this;
        }

        /**
         * @return the assembled, immutable profile
         * @throws XsozContractException if any required field is missing
         */
        public Profile build() {
            if (name == null) {
                throw new XsozContractException(
                        "Profile \"" + id + "\" has no name. A profile with no name is a row in "
                                + "the picker nobody can point at.");
            }
            if (createdUtc == null) {
                throw new XsozContractException("Profile \"" + id + "\" has no createdUtc.");
            }
            if (modifiedUtc == null) {
                throw new XsozContractException("Profile \"" + id + "\" has no modifiedUtc.");
            }
            if (provenance == null) {
                throw new XsozContractException(
                        "Profile \"" + id + "\" has no provenance. A value with no provenance is "
                                + "a rumour (C5.2).");
            }
            if (sensitivity == null) {
                throw new XsozContractException(
                        "Profile \"" + id + "\" has no sensitivity block. There is no default: "
                                + "sensitivity is a physical fact about a physical mouse.");
            }
            return new Profile(this);
        }
    }
}
