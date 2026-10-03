package dev.xsoz.core.config;

import dev.xsoz.core.XsozContractException;
import dev.xsoz.core.setting.JsonObject;
import dev.xsoz.core.sensitivity.FovRelativeMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract C4.5 and C5.4 - the versioned schema and its migration chain.
 *
 * <p>There is no downgrade path. A file newer than this build is REFUSED, because a newer
 * file read by an older client is how a profile silently loses settings.</p>
 */
class ConfigMigrationTest {

    @TempDir
    Path configDir;

    private Path profilesDir;

    private MigrationRegistry registry;

    @BeforeEach
    void bundledChain() throws Exception {
        registry = MigrationRegistry.bundled();
        profilesDir = Files.createDirectories(configDir.resolve("profiles"));
    }

    @Nested
    @DisplayName("the version")
    class Version {

        @Test
        @DisplayName("currentVersion is 1, frozen by C4.5")
        void currentVersionIsOne() {
            assertEquals(1, MigrationRegistry.CURRENT_VERSION);
            assertEquals(1, registry.currentVersion());
        }

        @Test
        @DisplayName("the bundled chain registers exactly the v0 -> v1 step")
        void chainContents() {
            assertEquals(1, registry.migrations().size());
            assertEquals(0, registry.migrations().get(0).fromVersion());
            assertTrue(registry.migrations().get(0).description().contains("v0 -> v1"),
                    registry.migrations().get(0).description());
        }

        @Test
        @DisplayName("a document that declares no version is refused: it has no defined meaning")
        void missingVersionIsRefused() {
            assertThrows(XsozContractException.class,
                    () -> MigrationRegistry.declaredVersionOf(JsonObject.parse("{\"id\": \"x\"}")));
        }

        @Test
        @DisplayName("a newer schema is REFUSED, and the failure is a value, not an exception")
        void newerSchemaIsRefused() {
            JsonObject document = JsonObject.parse("{\"schema\": 2, \"id\": \"x\"}");
            MigrationResult result = registry.migrate(document, 2);
            assertTrue(result.failed());
            assertTrue(result.failureReason().contains("newer than"), result.failureReason());
            assertTrue(result.failureReason().contains("Refusing rather than guessing"),
                    result.failureReason());
        }

        @Test
        @DisplayName("a migration that throws is a FAILED result, not a propagating exception")
        void aThrowingMigrationIsAValue() {
            MigrationRegistry throwing = MigrationRegistry.of(
                    Arrays.asList(new Migration() {
                        @Override
                        public int fromVersion() {
                            return 0;
                        }

                        @Override
                        public String description() {
                            return "always throws";
                        }

                        @Override
                        public JsonObject migrate(JsonObject input) {
                            throw new IllegalStateException("boom");
                        }
                    }), 1);
            MigrationResult result = throwing.migrate(JsonObject.parse("{\"schema\": 0}"), 0);
            assertTrue(result.failed());
            assertTrue(result.failureReason().contains("boom"), result.failureReason());
        }

        @Test
        @DisplayName("a chain with two steps out of the same version has no defined order and is refused")
        void ambiguousChainIsRefused() {
            Migration first = new NoOpMigration(0, "first");
            Migration second = new NoOpMigration(0, "second");
            assertThrows(XsozContractException.class,
                    () -> MigrationRegistry.of(Arrays.asList(first, second), 1));
        }
    }

    @Nested
    @DisplayName("the v0 -> v1 step")
    class V0ToV1 {

        private JsonObject v0() {
            return JsonObject.builder()
                    .put("schema", 0L)
                    .put("id", "legacy")
                    .put("name", "Legacy Profile")
                    .put("createdUtc", "2024-01-01T00:00:00Z")
                    .put("modifiedUtc", "2024-01-01T00:00:00Z")
                    .put("isDefault", true)
                    .put("modules", JsonObject.builder()
                            .put("hud.totem-counter", Boolean.TRUE)
                            .put("latency.crystal-release", Boolean.FALSE)
                            .put("perf.sodium-tweaks", JsonObject.builder()
                                    .put("enabled", true)
                                    .build())
                            .build())
                    .put("keybinds", JsonObject.builder()
                            .put("hud.totem-counter", JsonObject.builder()
                                    .put("key", "UNKNOWN")
                                    .build())
                            .build())
                    .put("sensitivity", JsonObject.builder()
                            .put("cmPer360", 6.096d)
                            .put("mouseDpi", 1000L)
                            .put("fov", 70L)
                            .put("mode", "PHYSICAL")
                            .build())
                    .build();
        }

        @Test
        @DisplayName("a bare boolean module entry becomes {enabled, settings}")
        void moduleEntriesGainSettings() {
            JsonObject upgraded = migrate(v0());
            assertEquals(1L, upgraded.getInt("schema"));
            JsonObject modules = upgraded.getObject("modules");
            assertTrue(modules.getObject("hud.totem-counter").getBoolean("enabled"));
            assertEquals(0, modules.getObject("hud.totem-counter")
                    .getObject("settings").size());
            assertFalse(modules.getObject("latency.crystal-release").getBoolean("enabled"));
            assertTrue(modules.getObject("perf.sodium-tweaks").getBoolean("enabled"));
        }

        @Test
        @DisplayName("the old FOV and mode spellings become the C6.1 spellings, and rampStageIndex appears")
        void sensitivityFieldNames() {
            JsonObject upgraded = migrate(v0());
            JsonObject sensitivity = upgraded.getObject("sensitivity");
            assertEquals(70, sensitivity.getInt("fovVertical"));
            assertEquals("PHYSICAL", sensitivity.getString("fovRelativeMode"));
            assertEquals(0, sensitivity.getInt("rampStageIndex"));
            assertFalse(sensitivity.has("fov"), "the old spelling is replaced, not kept alongside");
            assertFalse(sensitivity.has("mode"));
        }

        @Test
        @DisplayName("hud and coaching, which did not exist in v0, are created at their defaults")
        void missingBlocksAreCreated() {
            JsonObject upgraded = migrate(v0());
            assertEquals(1.0d, upgraded.getObject("hud").getDouble("scale"), 0.0d);
            assertEquals(0, upgraded.getObject("hud").getArray("elements").size());
            assertTrue(upgraded.getObject("coaching").getBoolean("autoPrune"));
            assertFalse(upgraded.getObject("coaching").getBoolean("chatCapture"),
                    "a migration must not switch a recorder on");
            assertEquals(0, upgraded.getObject("coaching").getStringArray("armedAddresses").size());
            assertEquals(null, upgraded.getObject("coaching").raw("mouseDebounceMillis"));
        }

        @Test
        @DisplayName("a v0 file had no source, so provenance is USER_DEFINED and says so")
        void provenanceIsNotInvented() {
            JsonObject upgraded = migrate(v0());
            JsonObject provenance = upgraded.getObject("provenance");
            assertEquals("USER_DEFINED", provenance.getString("kind"));
            assertTrue(provenance.getString("note").contains("no source"), provenance.getString("note"));
            assertFalse(provenance.has("sourceUrl"),
                    "a migration must never invent a citation it was not given");
            assertFalse(provenance.has("retrievedUtc"));
        }

        @Test
        @DisplayName("a v0 keybind that named no kind stays UNBOUND rather than guessing a key code")
        void keybindsAreNotInvented() {
            JsonObject upgraded = migrate(v0());
            JsonObject bind = upgraded.getObject("keybinds").getObject("hud.totem-counter");
            assertEquals("UNKNOWN", bind.getString("key"));
            assertFalse(bind.has("kind"));
        }

        @Test
        @DisplayName("a v0 sensitivity block carrying sensitivityRaw FAILS the migration, loudly")
        void sensitivityRawIsFatal() {
            JsonObject poisoned = v0().with("sensitivity", v0().getObject("sensitivity")
                    .with("sensitivityRaw", 100.0d));
            MigrationResult result = registry.migrate(poisoned, 0);
            assertTrue(result.failed());
            assertTrue(result.failureReason().contains("sensitivityRaw"), result.failureReason());
            assertTrue(result.failureReason().contains("C6.1"), result.failureReason());
        }

        @Test
        @DisplayName("the migrated document decodes to a real Profile and loads through the store")
        void migratedDocumentLoads() throws Exception {
            // The RAW v0 document goes on disk, so the STORE is what migrates it. Pre-migrating
            // in the test would prove only that the test's own call worked.
            Files.write(profilesDir.resolve("legacy.json"),
                    v0().toJson().getBytes(StandardCharsets.UTF_8));
            FileProfileStore reopened = ConfigTestSupport.store(configDir);
            // The v0 file was the default when it was written, and a store that already has a
            // default does NOT invent a second one. C5.2 requires exactly one at all times.
            assertEquals(1, reopened.all().size());
            Profile legacy = reopened.all().stream()
                    .filter(p -> p.id().equals("legacy")).findFirst().get();
            assertEquals("Legacy Profile", legacy.name());
            assertEquals(6.096d, legacy.sensitivity().cmPer360(), 0.0d);
            assertEquals(1000, legacy.sensitivity().mouseDpi());
            assertEquals(70, legacy.sensitivity().fovVerticalDeg());
            assertEquals(FovRelativeMode.PHYSICAL, legacy.sensitivity().fovRelativeMode());
            assertEquals(0, legacy.sensitivity().rampStageIndex());
            assertTrue(legacy.isDefault());
            assertTrue(reopened.recoveryLog().stream()
                    .anyMatch(n -> n.detail().contains("v0 -> v1")),
                    "the migration is named in the load notes, so the player can see it happened; "
                            + "log was " + reopened.recoveryLog());
            
        }

        private JsonObject migrate(JsonObject document) {
            MigrationResult result = registry.migrate(document, 0);
            assertFalse(result.failed(), result.failureReason());
            assertEquals(1, result.appliedDescriptions().size());
            assertTrue(result.appliedDescriptions().get(0).contains("v0 -> v1"));
            return result.output();
        }
    }

    @Nested
    @DisplayName("a v1 document")
    class V1 {

        @Test
        @DisplayName("migrating a document already at the current version applies nothing")
        void alreadyCurrent() {
            JsonObject document = ProfileJson.encode(ChosenOneProfile.create());
            MigrationResult result = registry.migrate(document, 1);
            assertFalse(result.failed());
            assertEquals(0, result.appliedDescriptions().size());
            assertEquals(document, result.output());
        }

        @Test
        @DisplayName("a sensitivityRaw key anywhere is a schema error, reported and dropped")
        void sensitivityRawIsASchemaError() {
            JsonObject document = ProfileJson.encode(ChosenOneProfile.create())
                    .with("sensitivityRaw", 100.0d);
            XsozContractException thrown = assertThrows(XsozContractException.class,
                    () -> ProfileJson.decode(document));
            assertTrue(thrown.getMessage().contains("C6.1"), thrown.getMessage());
        }

        @Test
        @DisplayName("a sensitivityRaw key inside the sensitivity block is refused too")
        void nestedSensitivityRawIsASchemaError() {
            JsonObject document = ProfileJson.encode(ChosenOneProfile.create())
                    .with("sensitivity", ProfileJson.encode(ChosenOneProfile.create())
                            .getObject("sensitivity").with("sensitivityRaw", 1.0d));
            assertThrows(XsozContractException.class, () -> ProfileJson.decode(document));
        }
    }

    @Nested
    @DisplayName("xsozclient.json")
    class GlobalConfigFile {

        @Test
        @DisplayName("it round-trips through its own codec")
        void roundTrip() {
            GlobalConfig original = GlobalConfig.of("0.1.0-SNAPSHOT", "chosen-one",
                    java.time.LocalDate.of(2026, 9, 29), true);
            assertEquals(original, GlobalConfig.fromJson(original.toJson()));
        }

        @Test
        @DisplayName("a first-run config with no lastProfileId round-trips through JSON null")
        void nullLastProfileIdRoundTrips() {
            GlobalConfig original = GlobalConfig.of("0.1.0-SNAPSHOT", null, null, true);
            assertEquals(null, original.toJson().raw("lastProfileId"));
            assertEquals(original, GlobalConfig.fromJson(original.toJson()));
        }

        @Test
        @DisplayName("a newer schema is refused, and an illegal lastProfileId is refused")
        void validation() {
            GlobalConfig original = GlobalConfig.of("0.1.0-SNAPSHOT", null, null, true);
            assertThrows(XsozContractException.class,
                    () -> GlobalConfig.fromJson(original.toJson().with("schema", 99L)));
            assertThrows(XsozContractException.class,
                    () -> GlobalConfig.fromJson(original.toJson().with("lastProfileId", "Not An Id")));
            assertThrows(XsozContractException.class,
                    () -> GlobalConfig.fromJson(original.toJson()
                            .with("rulesSnapshotDate", "the day before yesterday")));
        }
    }

    /** A migration that changes nothing, for the chain-shape tests. */
    private static final class NoOpMigration implements Migration {

        private final int fromVersion;
        private final String description;

        NoOpMigration(int fromVersion, String description) {
            this.fromVersion = fromVersion;
            this.description = description;
        }

        @Override
        public int fromVersion() {
            return fromVersion;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public JsonObject migrate(JsonObject input) {
            return input;
        }
    }
}
