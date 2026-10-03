package dev.xsoz.core.config;

import dev.xsoz.core.compliance.ComplianceTier;
import dev.xsoz.core.keybind.BindPolicy;
import dev.xsoz.core.keybind.BindRejection;
import dev.xsoz.core.keybind.BindRequest;
import dev.xsoz.core.keybind.DefaultBindPolicy;
import dev.xsoz.core.keybind.Keybind;
import dev.xsoz.core.setting.JsonObject;
import dev.xsoz.core.setting.SettingsSchema;
import dev.xsoz.core.setting.SettingsView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Decodes a profile document and applies every check the codec cannot: the migration
 * chain, the module catalog, the keybind policy, and the bundled compliance set.
 *
 * <p><strong>The split is deliberate.</strong> {@link ProfileJson} is a pure function of
 * the document. This class is the only place that knows what modules exist, and it is
 * therefore the only place that can say "this key names a module I do not have".</p>
 *
 * <p><strong>An empty {@link ModuleCatalog} disables the unknown-key checks entirely.</strong>
 * A store that dropped every module key because the catalog had not been wired yet would
 * empty every profile on first run. A catalog that knows nothing means "not installed",
 * not "nothing exists", and the two must not be confused.</p>
 *
 * <p><strong>{@code UNKNOWN_ACTION} from the bind policy is not a violation.</strong> The
 * policy's own words are "this client has no rules for it". Refusing an import because an
 * action is not in the closed registry would make this client unable to import its own
 * shipped default. An unbound bind is likewise not sent to the policy: "not bound" is a
 * stored state, not an attempt to bind the unknown key.</p>
 */
public final class ProfileLoader {

    private final MigrationRegistry migrations;
    private final ModuleCatalog catalog;
    private final Set<String> bundledComplianceProfileIds;
    private final BindPolicy bindPolicy;

    private ProfileLoader(MigrationRegistry migrations, ModuleCatalog catalog,
                          Set<String> bundledComplianceProfileIds, BindPolicy bindPolicy) {
        this.migrations = migrations;
        this.catalog = catalog == null ? ModuleCatalog.none() : catalog;
        this.bundledComplianceProfileIds = bundledComplianceProfileIds == null
                ? Collections.<String>emptySet() : bundledComplianceProfileIds;
        this.bindPolicy = bindPolicy == null ? new DefaultBindPolicy() : bindPolicy;
    }

    /**
     * @param migrations                 the migration chain
     * @param catalog                    what modules exist
     * @param bundledComplianceProfileIds the compliance profile ids this build ships
     * @param bindPolicy                 the keybind validator
     * @return a loader over that configuration
     */
    public static ProfileLoader of(MigrationRegistry migrations, ModuleCatalog catalog,
                                   Set<String> bundledComplianceProfileIds, BindPolicy bindPolicy) {
        return new ProfileLoader(migrations, catalog, bundledComplianceProfileIds, bindPolicy);
    }

    /**
     * Loads a document.
     *
     * @param document the profile document as read
     * @return the profile plus everything the loader had to do to it
     * @throws dev.xsoz.core.XsozContractException if the document cannot be loaded at all:
     *         unparseable, a schema newer than this build, or structurally invalid. The
     *         caller routes that to the recovery path of C5.4, which preserves the bytes.
     */
    public ProfileLoadResult load(JsonObject document) {
        int declared = MigrationRegistry.declaredVersionOf(document);
        if (declared > migrations.currentVersion()) {
            throw new dev.xsoz.core.XsozContractException(
                    "The document declares schema " + declared + ", which is newer than the "
                            + migrations.currentVersion() + " this build understands. C5.4: a file "
                            + "with a higher schema than the client understands is REFUSED, not "
                            + "guessed at.");
        }
        MigrationResult migration = migrations.migrate(document, declared);
        if (migration.failed()) {
            throw new dev.xsoz.core.XsozContractException(migration.failureReason());
        }

        List<String> notes = new ArrayList<String>(migration.appliedDescriptions());
        return new ProfileLoadResult(resolve(ProfileJson.decode(migration.output()), notes), notes);
    }

    /**
     * Applies the catalog-dependent resolution to an already-decoded profile: unknown module
     * keys dropped, Tier C entries dropped, every module's settings resolved through its
     * schema, every keybind through the policy, unknown HUD ids dropped, and an unbundled
     * compliance id forced to default-deny.
     *
     * <p><strong>Exposed because the import path must produce the SAME shape a load does.</strong>
     * A load resolves a stored {@code 2000} to the {@link Integer} its {@code INT} setting
     * declares; an import that skipped this step would keep the {@link Long} the JSON decoder
     * produced, and the two profiles would compare unequal while looking identical on screen.</p>
     *
     * @param profile the decoded profile
     * @param notes   the list to append what it did to
     * @return the resolved profile
     */
    public Profile resolve(Profile profile, List<String> notes) {
        Profile resolved = sanitiseModules(profile, notes);
        resolved = sanitiseKeybinds(resolved, notes);
        resolved = sanitiseHud(resolved, notes);
        return sanitiseComplianceProfileId(resolved, notes);
    }

    /**
     * Drops module entries the catalog does not know, drops Tier C entries always, and
     * resolves each module's stored settings through its schema.
     */
    private Profile sanitiseModules(Profile profile, List<String> notes) {
        Set<String> known = catalog.knownModuleIds();
        boolean catalogInstalled = !known.isEmpty();
        Map<String, ModuleState> resolved = new LinkedHashMap<String, ModuleState>();
        for (Map.Entry<String, ModuleState> entry : profile.modules().entrySet()) {
            String moduleId = entry.getKey();
            Optional<ComplianceTier> tier = catalog.tierOf(moduleId);
            if (tier.isPresent() && tier.get() == ComplianceTier.C) {
                notes.add(moduleId + ": dropped. C7.1: a Tier C feature is never compiled, so a "
                        + "profile cannot hold one. A Tier C key in a config file is a failure, "
                        + "not a value to keep.");
                continue;
            }
            if (catalogInstalled && !known.contains(moduleId)) {
                notes.add(moduleId + ": dropped. Module id not in the registered module set, so "
                        + "this client has no settings schema for it and cannot honour it.");
                continue;
            }
            Optional<SettingsSchema> schema = catalog.settingsSchemaFor(moduleId);
            if (!schema.isPresent()) {
                resolved.put(moduleId, entry.getValue());
                continue;
            }
            SettingsView view = schema.get().view(entry.getValue().settings());
            appendSchemaNotes(view, moduleId, notes);
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            for (String key : schema.get().keys()) {
                values.put(key, view.raw(key));
            }
            resolved.put(moduleId, ModuleState.of(entry.getValue().requestedEnabled(), values));
        }
        return resolved.size() == profile.modules().size() && resolved.equals(profile.modules())
                ? profile : profile.withModules(resolved);
    }

    /**
     * Appends a module's load notes.
     *
     * <p>The setting key already carries the module id (C4.1: a key is
     * {@code "<moduleId>.<settingName>"}), so it is used verbatim. Prefixing it again would
     * produce {@code hud.totem-counter.hud.totem-counter.style}, which reads like two
     * different modules.</p>
     */
    private void appendSchemaNotes(SettingsView view, String moduleId, List<String> notes) {
        dev.xsoz.core.setting.SettingsMutationReport report = view.lastReport();
        for (String dropped : report.unknownKeysDropped()) {
            notes.add(dropped);
        }
        for (Map.Entry<String, String> coerced : report.valuesCoerced().entrySet()) {
            notes.add(coerced.getKey() + ": coerced, " + coerced.getValue());
        }
        for (String reset : report.valuesResetToDefault()) {
            notes.add(reset);
        }
        for (String truncated : report.valuesTruncated()) {
            notes.add(truncated);
        }
    }

    /**
     * Resets a bind the policy refuses to {@link Keybind#unbound()} and reports it
     * (C5.2: every value passes {@code BindPolicy} on load).
     */
    private Profile sanitiseKeybinds(Profile profile, List<String> notes) {
        Map<String, Keybind> resolved = new LinkedHashMap<String, Keybind>();
        boolean changed = false;
        for (Map.Entry<String, Keybind> entry : profile.keybinds().entrySet()) {
            String actionId = entry.getKey();
            Keybind bind = entry.getValue();
            if (bind == null || !bind.isBound()) {
                resolved.put(actionId, Keybind.unbound());
                continue;
            }
            String refusal = refusalFor(actionId, bind);
            if (refusal == null) {
                resolved.put(actionId, bind);
            } else {
                resolved.put(actionId, Keybind.unbound());
                notes.add(actionId + ": reset to unbound. " + refusal);
                changed = true;
            }
        }
        return changed ? profile.withKeybinds(resolved) : profile;
    }

    /**
     * @return why the policy refuses this bind, or {@code null} when it is acceptable.
     *         {@link BindRejection#UNKNOWN_ACTION} is acceptable: the client has no rules
     *         for the action, which is not the same as the bind being forbidden.
     */
    private String refusalFor(String actionId, Keybind bind) {
        BindRequest request = BindRequest.forAction(actionId).requested(bind).build();
        dev.xsoz.core.keybind.BindVerdict verdict = bindPolicy.validate(bind, request);
        if (verdict.allowed() || verdict.reason() == BindRejection.UNKNOWN_ACTION) {
            return null;
        }
        return verdict.reason() + ": " + verdict.message();
    }

    /** Drops HUD elements whose id names no module, and reports it (C5.2). */
    private Profile sanitiseHud(Profile profile, List<String> notes) {
        Set<String> known = catalog.knownModuleIds();
        if (known.isEmpty()) {
            return profile;
        }
        List<HudElement> kept = new ArrayList<HudElement>();
        boolean dropped = false;
        for (HudElement element : profile.hud().elements()) {
            if (known.contains(element.id())) {
                kept.add(element);
            } else {
                dropped = true;
                notes.add("hud element \"" + element.id() + "\": dropped. C5.2: the id must name a "
                        + "module that provides a HUD element. An element that draws nothing is a "
                        + "permanently broken row with no way to remove it in the UI.");
            }
        }
        if (!dropped) {
            return profile;
        }
        return profile.withHud(HudLayout.of(profile.hud().scale(), kept));
    }

    /**
     * Falls back to {@code default-deny} when the stored compliance profile id is not in
     * the locally bundled set (C5.2, C5.5 step 5).
     */
    private Profile sanitiseComplianceProfileId(Profile profile, List<String> notes) {
        String id = profile.complianceProfileId();
        if (id.equals(Profile.DEFAULT_DENY_COMPLIANCE_PROFILE_ID) || bundledComplianceProfileIds.isEmpty()) {
            return profile;
        }
        if (bundledComplianceProfileIds.contains(id)) {
            return profile;
        }
        notes.add("complianceProfileId \"" + id + "\": not in the locally bundled set, so it fell "
                + "back to \"" + Profile.DEFAULT_DENY_COMPLIANCE_PROFILE_ID + "\". C5.2: the load "
                + "falls back to default-deny, which is the strictest known behaviour.");
        return profile.withComplianceProfileId(Profile.DEFAULT_DENY_COMPLIANCE_PROFILE_ID);
    }

    /**
     * Forces a fresh id, {@code isDefault = false} and a default-deny compliance profile on
     * an imported document (C5.5 steps 5 and 6).
     *
     * <p><strong>Only those three things change.</strong> The name, both timestamps, the
     * provenance and every setting are carried across untouched, because an import is a
     * <em>copy</em> of what somebody else configured and rewriting any of it would make the
     * copy a lie about what it is. C5.6's {@code create} and {@code duplicate} are the
     * operations that reset timestamps, because those are new profiles.</p>
     *
     * @param profile the decoded profile
     * @param newId   the fresh id, {@code <oldId>-import-<4 hex>}
     * @return the profile as it will be inserted
     */
    public Profile prepareForInsertion(Profile profile, String newId) {
        Profile prepared = profile.toBuilder()
                .id(newId)
                .isDefault(false)
                .complianceProfileId(Profile.DEFAULT_DENY_COMPLIANCE_PROFILE_ID)
                .build();
        CoachingState coaching = prepared.coaching();
        if (coaching.chatCapture()) {
            prepared = prepared.withCoaching(coaching.withChatCapture(false));
        }
        return prepared;
    }

    /**
     * @return the debounce the profile carries, for the C9 coaching side
     */
    public static OptionalLong debounceOf(Profile profile) {
        return profile.coaching().mouseDebounceMillis();
    }
}
