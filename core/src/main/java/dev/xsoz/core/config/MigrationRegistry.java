package dev.xsoz.core.config;

import dev.xsoz.core.XsozContractException;
import dev.xsoz.core.setting.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The config migration chain and the schema version it upgrades to (contracts.md C4.5).
 *
 * <p><strong>{@link #CURRENT_VERSION} is 1.</strong> C4.5 freezes it: bumping it is a
 * change request and must ship a {@link Migration} from the previous version in the same
 * commit. The v0 to v1 step is therefore a real, registered, tested migration rather
 * than a hypothetical.</p>
 *
 * <p><strong>No downgrade path.</strong> {@link #migrate(JsonObject, int)} refuses a
 * {@code fromVersion} above {@link #currentVersion()} rather than guessing: a newer file
 * read by an older client is how a profile silently loses settings.</p>
 *
 * <p>Deeply immutable once built; registration happens at construction.</p>
 */
public final class MigrationRegistry {

    /** The schema version this build reads and writes. Frozen by contracts.md C4.5. */
    public static final int CURRENT_VERSION = 1;

    /** The JSON key carrying the document's schema version. */
    public static final String SCHEMA_KEY = "schema";

    private final List<Migration> migrations;
    private final int currentVersion;

    private MigrationRegistry(List<Migration> migrations, int currentVersion) {
        this.migrations = Collections.unmodifiableList(new ArrayList<Migration>(migrations));
        this.currentVersion = currentVersion;
    }

    /**
     * The registry this build ships: the v0 to v1 step and nothing else.
     *
     * @return the bundled registry, at {@link #CURRENT_VERSION}
     */
    public static MigrationRegistry bundled() {
        List<Migration> chain = new ArrayList<Migration>();
        chain.add(new V0ToV1Migration());
        return new MigrationRegistry(chain, CURRENT_VERSION);
    }

    /**
     * @param newMigrations the steps, in any order; they are sorted by {@code fromVersion}
     * @param newCurrentVersion the version the chain reaches; must be positive
     * @return a registry over that chain
     * @throws XsozContractException if a version is non-positive or two steps share a
     *                                {@code fromVersion}
     */
    public static MigrationRegistry of(List<Migration> newMigrations, int newCurrentVersion) {
        if (newMigrations == null || newMigrations.isEmpty()) {
            throw new XsozContractException(
                    "A migration registry needs at least one step; a chain of nothing is not a "
                            + "chain.");
        }
        if (newCurrentVersion <= 0) {
            throw new XsozContractException(
                    "A schema version must be positive; was " + newCurrentVersion + ".");
        }
        List<Migration> sorted = new ArrayList<Migration>(newMigrations);
        Collections.sort(sorted, new java.util.Comparator<Migration>() {
            @Override
            public int compare(Migration left, Migration right) {
                return Integer.compare(left.fromVersion(), right.fromVersion());
            }
        });
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).fromVersion() == sorted.get(i - 1).fromVersion()) {
                throw new XsozContractException(
                        "Two migrations claim fromVersion " + sorted.get(i).fromVersion()
                                + ". A chain with two steps out of the same version has no "
                                + "defined order.");
            }
        }
        return new MigrationRegistry(sorted, newCurrentVersion);
    }

    /**
     * @param migration the step to add
     * @return this registry
     * @throws XsozContractException if a step for the same {@code fromVersion} exists
     */
    public MigrationRegistry register(Migration migration) {
        if (migration == null) {
            throw new XsozContractException("Cannot register a null migration.");
        }
        List<Migration> next = new ArrayList<Migration>(migrations);
        for (Migration existing : next) {
            if (existing.fromVersion() == migration.fromVersion()) {
                throw new XsozContractException(
                        "A migration from version " + migration.fromVersion() + " is already "
                                + "registered: \"" + existing.description() + "\".");
            }
        }
        next.add(migration);
        return of(next, currentVersion);
    }

    /** @return the version this build reads and writes */
    public int currentVersion() {
        return currentVersion;
    }

    /** @return the registered steps, in ascending {@code fromVersion} */
    public List<Migration> migrations() {
        return migrations;
    }

    /**
     * Runs the chain from a document's declared version to {@link #currentVersion()}.
     *
     * @param input       the document as read
     * @param fromVersion the version it declares
     * @return the migrated document and what was applied, or a failed result. Never
     *         throws: untrusted input produces a value, and the caller routes a failure to
     *         the recovery path of C5.4.
     */
    public MigrationResult migrate(JsonObject input, int fromVersion) {
        if (fromVersion > currentVersion) {
            return MigrationResult.failure(input, fromVersion, currentVersion,
                    "the document declares schema " + fromVersion + ", which is newer than the "
                            + currentVersion + " this build understands. Refusing rather than "
                            + "guessing: a newer file read by an older client is how a profile "
                            + "silently loses settings.");
        }
        if (fromVersion < 0) {
            return MigrationResult.failure(input, fromVersion, currentVersion,
                    "a schema version cannot be negative; the document declared " + fromVersion
                            + ".");
        }
        if (input == null) {
            return MigrationResult.failure(null, fromVersion, currentVersion,
                    "there is no document to migrate.");
        }
        if (fromVersion == currentVersion) {
            return MigrationResult.success(input, Collections.<String>emptyList());
        }

        List<String> applied = new ArrayList<String>();
        JsonObject current = input;
        int version = fromVersion;
        while (version < currentVersion) {
            Migration step = stepFor(version);
            if (step == null) {
                return MigrationResult.failure(current, fromVersion, currentVersion,
                        "no migration is registered from version " + version + ", so the chain "
                                + "cannot reach " + currentVersion + ".");
            }
            try {
                JsonObject next = step.migrate(current);
                if (next == null) {
                    return MigrationResult.failure(current, fromVersion, currentVersion,
                            "the step from version " + version + " returned no document.");
                }
                current = next;
                applied.add(step.description());
                version++;
            } catch (RuntimeException e) {
                return MigrationResult.failure(current, fromVersion, currentVersion,
                        "the step from version " + version + " threw " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
            }
        }
        return MigrationResult.success(current, applied);
    }

    private Migration stepFor(int fromVersion) {
        for (Migration candidate : migrations) {
            if (candidate.fromVersion() == fromVersion) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The v0 to v1 step.
     *
     * <p><strong>What changed at v1, and why each of these is worth a version bump:</strong></p>
     * <ul>
     *   <li>{@code modules} entries were a bare boolean; they became
     *       {@code {"enabled": <bool>, "settings": {}}}. A bare boolean cannot hold
     *       settings, which is the thing a profile exists for.</li>
     *   <li>{@code sensitivity} spelled its FOV {@code fov} and its mode {@code mode},
     *       and had no {@code rampStageIndex}. The names were ambiguous and there was no
     *       place to record the adaptation ramp's position.</li>
     *   <li>{@code hud} and {@code coaching} did not exist. They get their contract
     *       defaults rather than being left absent, so a v1 file always has the full
     *       shape.</li>
     *   <li>{@code provenance} did not exist. A v0 file therefore cannot claim a
     *       published source, and is migrated to {@link ProvenanceKind#USER_DEFINED} with
     *       a note saying exactly that. <strong>A migration must not invent a
     *       citation.</strong></li>
     * </ul>
     *
     * <p>Pure: no clock, no filesystem, no randomness.</p>
     */
    static final class V0ToV1Migration implements Migration {

        @Override
        public int fromVersion() {
            return 0;
        }

        @Override
        public String description() {
            return "v0 -> v1: module entries gain settings, sensitivity gains rampStageIndex, "
                    + "hud and coaching are created, provenance is recorded as user-defined "
                    + "because a v0 file carried no source";
        }

        @Override
        public JsonObject migrate(JsonObject input) {
            JsonObject out = input.with(SCHEMA_KEY, 1L);

            if (input.has("modules")) {
                JsonObject modules = input.getObject("modules");
                JsonObject.Builder upgraded = JsonObject.builder();
                for (String moduleId : modules.keys()) {
                    Object raw = modules.raw(moduleId);
                    if (raw instanceof Boolean) {
                        upgraded.put(moduleId, JsonObject.builder()
                                .put("enabled", ((Boolean) raw).booleanValue())
                                .put("settings", JsonObject.empty())
                                .build());
                    } else if (raw instanceof JsonObject) {
                        JsonObject entry = (JsonObject) raw;
                        JsonObject.Builder builder = JsonObject.builder()
                                .put("enabled", entry.getBoolean("enabled", false));
                        builder.put("settings", entry.optObject("settings")
                                .orElse(JsonObject.empty()));
                        upgraded.put(moduleId, builder.build());
                    } else {
                        // Preserve an unrecognised shape verbatim rather than dropping it.
                        upgraded.put(moduleId, raw);
                    }
                }
                out = out.with("modules", upgraded.build());
            }

            if (input.has("sensitivity")) {
                JsonObject sensitivity = input.getObject("sensitivity");
                JsonObject.Builder builder = JsonObject.builder();
                builder.put("cmPer360", sensitivity.getDouble("cmPer360", 0.744140625d));
                builder.put("mouseDpi", sensitivity.getInt("mouseDpi", 2000));
                builder.put("fovVertical", sensitivity.getInt(
                        sensitivity.has("fovVertical") ? "fovVertical" : "fov",
                        sensitivity.getInt("fov", 90)));
                builder.put("fovRelativeMode", sensitivity.getString(
                        sensitivity.has("fovRelativeMode") ? "fovRelativeMode" : "mode", "PHYSICAL"));
                builder.put("rampStageIndex", sensitivity.getInt("rampStageIndex", 0));
                // C6.1: a raw slider percentage is a schema error. v0 is exactly where
                // one could hide, so it is dropped here and named in the log line.
                if (sensitivity.has("sensitivityRaw")) {
                    throw new XsozContractException(
                            "A v0 sensitivity block carried a \"sensitivityRaw\" key. C6.1: the "
                                    + "canonical stored quantity is cmPer360 and a raw percentage "
                                    + "is never persisted. Migrating it would be guessing which of "
                                    + "the two the author meant.");
                }
                out = out.with("sensitivity", builder.build());
            }

            if (!input.has("hud")) {
                out = out.with("hud", JsonObject.builder()
                        .put("scale", HudLayout.DEFAULT_SCALE)
                        .putArray("elements", Collections.emptyList())
                        .build());
            }
            if (!input.has("coaching")) {
                out = out.with("coaching", JsonObject.builder()
                        .putArray("armedAddresses", Collections.emptyList())
                        .put("autoPrune", Boolean.TRUE)
                        .put("chatCapture", Boolean.FALSE)
                        .put("mouseDebounceMillis", null)
                        .build());
            }
            if (!input.has("provenance")) {
                out = out.with("provenance", JsonObject.builder()
                        .put("kind", ProvenanceKind.USER_DEFINED.name())
                        .put("note", "Migrated from a schema 0 file, which recorded no source. "
                                + "These numbers are the player's own until they say otherwise.")
                        .build());
            }
            out = out.with("keybinds", migrateKeybinds(input.optObject("keybinds")
                    .orElse(JsonObject.empty())));
            return out;
        }

        /**
         * v0 stored only the key name. The kind and code did not exist, so they are
         * written as absent and the decoder treats a nameless bind as unbound rather than
         * inventing a GLFW code. A migration that guesses a key code is a migration that
         * can put a combat action on the wrong key.
         */
        private JsonObject migrateKeybinds(JsonObject keybinds) {
            JsonObject.Builder out = JsonObject.builder();
            for (String actionId : keybinds.keys()) {
                Object raw = keybinds.raw(actionId);
                JsonObject entry = JsonObject.builder()
                        .put("key", "UNKNOWN")
                        .putArray("modifiers", Collections.emptyList())
                        .build();
                if (raw instanceof JsonObject) {
                    JsonObject source = (JsonObject) raw;
                    JsonObject.Builder builder = JsonObject.builder()
                            .put("key", source.getString("key", "UNKNOWN"))
                            .putArray("modifiers", source.getStringArray("modifiers"));
                    // Absent kind and code stay ABSENT, not null. A member present with a null
                    // value says "this key exists and is nothing", which is a different claim
                    // from "this key did not exist in v0".
                    if (source.has("kind") && source.raw("kind") != null) {
                        builder.put("kind", source.getString("kind"));
                        builder.put("code", source.getInt("code", 0));
                    }
                    entry = builder.build();
                } else if (raw instanceof String) {
                    entry = JsonObject.builder().put("key", (String) raw).build();
                }
                out.put(actionId, entry);
            }
            return out.build();
        }
    }

    /**
     * @param document the document as read
     * @return the schema version it declares
     * @throws XsozContractException if it declares none
     */
    public static int declaredVersionOf(JsonObject document) {
        if (document == null || !document.has(SCHEMA_KEY)) {
            throw new XsozContractException(
                    "A config document must declare \"" + SCHEMA_KEY + "\". C5.2: it must equal "
                            + "MigrationRegistry.currentVersion() or be lower with a migration "
                            + "path. A document with no version has no defined meaning.");
        }
        return document.getInt(SCHEMA_KEY);
    }

    /**
     * The date a bundled source was read, rendered the way the profile codec writes it.
     *
     * @param date the date
     * @return the ISO-8601 text
     */
    static String renderDate(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
