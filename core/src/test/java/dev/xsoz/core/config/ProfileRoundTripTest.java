package dev.xsoz.core.config;

import dev.xsoz.core.compliance.ComplianceTier;
import dev.xsoz.core.keybind.InputKey;
import dev.xsoz.core.keybind.Keybind;
import dev.xsoz.core.keybind.ModifierKey;
import dev.xsoz.core.setting.JsonObject;
import dev.xsoz.core.sensitivity.FovRelativeMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract C5.5 - export, import, and the round-trip property.
 *
 * <p><strong>The round-trip test is the one that earns its keep.</strong> Export then
 * import must produce an equal profile; anything a codec forgets to write comes back as a
 * default, and a default that looks like a value is the hardest class of config bug to
 * find by hand.</p>
 */
class ProfileRoundTripTest {

    @TempDir
    Path configDir;

    private FileProfileStore store;

    @BeforeEach
    void openStore() {
        store = ConfigTestSupport.store(configDir);
    }

    @Nested
    @DisplayName("the round-trip property on a richly populated profile")
    class RoundTrip {

        @Test
        @DisplayName("export then import produces an EQUAL profile, differing only in the id")
        void exportThenImportIsEqual() throws Exception {
            store = install(richProfile());
            Profile loaded = stored(store, richProfile());
            assertEquals(richProfile(), loaded,
                    "the loader resolved a well-formed profile to itself; if this fails, the "
                            + "loader is changing something the document said explicitly");
            Path export = configDir.resolve("rich.xsozprofile.json");
            store.exportTo(loaded.id(), export);
            Profile imported = store.importProfileFrom(export);

            assertEquals(loaded.name(), imported.name());
            assertEquals(loaded.createdUtc(), imported.createdUtc());
            assertEquals(loaded.modifiedUtc(), imported.modifiedUtc());
            assertEquals(loaded.isDefault(), imported.isDefault());
            assertEquals(loaded.provenance(), imported.provenance());
            assertEquals(loaded.modules(), imported.modules());
            assertEquals(loaded.keybinds(), imported.keybinds());
            assertEquals(loaded.hud(), imported.hud());
            assertEquals(loaded.sensitivity(), imported.sensitivity());
            assertEquals(loaded.coaching(), imported.coaching());
            assertEquals(loaded.complianceProfileId(), imported.complianceProfileId());
            assertEquals(stripVolatileFields(loaded), stripVolatileFields(imported),
                    "every persisted field of a richly populated profile survives export and "
                            + "import. A field the codec forgot to write comes back as a default, "
                            + "and a default that looks like a value is the hardest config bug to "
                            + "find by hand.");
        }

        @Test
        @DisplayName("the id is fresh and import NEVER overwrites (C5.5 steps 6 and 7)")
        void importNeverOverwrites() throws Exception {
            store = install(richProfile());
            Profile loaded = stored(store, richProfile());
            int before = store.all().size();
            Path export = configDir.resolve("rich.xsozprofile.json");
            store.exportTo(loaded.id(), export);
            Profile imported = store.importProfileFrom(export);
            assertNotEquals(loaded.id(), imported.id());
            assertTrue(imported.id().startsWith(loaded.id()), imported.id());
            assertTrue(imported.id().contains("-import-"), imported.id());
            assertTrue(ProfileNames.isValidId(imported.id()), imported.id());
            assertEquals(before + 1, store.all().size());
            assertEquals(loaded, stored(store, loaded),
                    "the original profile is untouched");
        }

        @Test
        @DisplayName("import never changes which profile is active or which is the default")
        void importDoesNotSwitch() throws Exception {
            Profile second = store.create("Second");
            store.switchTo(second.id());
            store = install(richProfile());
            store.switchTo(second.id());
            Path export = configDir.resolve("rich.xsozprofile.json");
            store.exportTo("rich-profile", export);
            store.importProfileFrom(export);
            assertEquals(second.id(), store.activeProfile().id());
            assertEquals("chosen-one", store.defaultProfile().id());
        }

        @Test
        @DisplayName("import forces isDefault = false and default-deny (C5.5 steps 5 and 6)")
        void importForcesTheSafeState() throws Exception {
            Profile permissive = richProfile().withDefault(true)
                    .withComplianceProfileId("pvp-land");
            store = install(permissive);
            Path export = configDir.resolve("permissive.xsozprofile.json");
            store.exportTo(permissive.id(), export);
            Profile imported = store.importProfileFrom(export);
            assertFalse(imported.isDefault());
            assertEquals("default-deny", imported.complianceProfileId(),
                    "C5.5 step 5: a hand-edited or downloaded profile must not be able to import a "
                            + "permissive compliance profile");
            assertEquals("chosen-one", store.defaultProfile().id());
        }

        @Test
        @DisplayName("import forces chat capture OFF: a file must not switch a recorder on by itself")
        void importDisarmsChatCapture() throws Exception {
            Profile base = richProfile();
            Profile recording = base.withCoaching(base.coaching().withChatCapture(true));
            store = install(recording);
            Path export = configDir.resolve("recording.xsozprofile.json");
            store.exportTo(recording.id(), export);
            Profile imported = store.importProfileFrom(export);
            assertFalse(imported.coaching().chatCapture());
            assertEquals("chosen-one", store.defaultProfile().id());
        }

        @Test
        @DisplayName("a second export of the same profile is byte-identical to the first")
        void exportIsDeterministic() throws Exception {
            store = install(richProfile());
            Path first = configDir.resolve("a.xsozprofile.json");
            Path second = configDir.resolve("b.xsozprofile.json");
            store.exportTo("rich-profile", first);
            store.exportTo("rich-profile", second);
            assertEquals(new String(Files.readAllBytes(first), StandardCharsets.UTF_8),
                    new String(Files.readAllBytes(second), StandardCharsets.UTF_8),
                    "the same profile twice must produce the same bytes, so a diff of two exports "
                            + "shows a real change and not a key reshuffle");
        }

        @Test
        @DisplayName("the export wrapper carries the format discriminator and version (C5.5)")
        void exportWrapperShape() throws Exception {
            Path export = configDir.resolve("wrapped.xsozprofile.json");
            store.exportTo("chosen-one", export);
            JsonObject document = JsonObject.parse(
                    new String(Files.readAllBytes(export), StandardCharsets.UTF_8));
            assertEquals("xsoz-profile", document.getString("format"));
            assertEquals(1, document.getInt("formatVersion"));
            assertEquals(ConfigTestSupport.NOW.toString(), document.getString("exportedUtc"));
            assertTrue(document.has("profile"));
        }

        @Test
        @DisplayName("the default export filename is the one C5.5 names")
        void defaultExportFileName() {
            assertEquals("xsoz-profile-chosen-one-20260929-120000.xsozprofile.json",
                    store.defaultExportFileName("chosen-one", ConfigTestSupport.NOW));
        }

        @Test
        @DisplayName("the shipped default profile itself round-trips through export and import")
        void shippedDefaultRoundTrips() {
            Path export = configDir.resolve("chosen.xsozprofile.json");
            store.exportTo("chosen-one", export);
            Profile imported = store.importProfileFrom(export);
            Profile shipped = ChosenOneProfile.create();
            assertEquals(shipped.name(), imported.name());
            assertEquals(shipped.createdUtc(), imported.createdUtc());
            assertEquals(shipped.modifiedUtc(), imported.modifiedUtc());
            assertEquals(shipped.provenance(), imported.provenance());
            assertEquals(shipped.modules(), imported.modules());
            assertEquals(shipped.keybinds(), imported.keybinds());
            assertEquals(shipped.hud(), imported.hud());
            assertEquals(shipped.sensitivity(), imported.sensitivity());
            assertEquals(shipped.coaching(), imported.coaching());
            assertEquals(0.744140625d, imported.sensitivity().cmPer360(), 0.0d,
                    "the sourced value survives the round trip bit for bit");
            assertEquals(0, imported.sensitivity().rampStageIndex(),
                    "and so does the ramp's position, which is the whole reason it is stored as an "
                            + "index and not as a stage's cm/360");
            assertFalse(imported.isDefault());
            assertEquals("default-deny", imported.complianceProfileId());
        }
    }

    @Nested
    @DisplayName("the seven ordered import checks")
    class ImportChecks {

        @Test
        @DisplayName("step 1: a document that does not parse is refused and changes nothing")
        void garbageIsRefused() throws Exception {
            Path source = configDir.resolve("garbage.xsozprofile.json");
            Files.write(source, "this is not json at all {{{".getBytes(StandardCharsets.UTF_8));
            int before = store.all().size();
            ProfileImportRejectedException thrown =
                    assertThrows(ProfileImportRejectedException.class, () -> store.importFrom(source));
            assertEquals(ProfileImportRejectedException.STEP_PARSE, thrown.step());
            assertEquals(before, store.all().size());
        }

        @Test
        @DisplayName("step 1: a wrong format discriminator is refused")
        void wrongFormatIsRefused() throws Exception {
            Path source = configDir.resolve("wrong.xsozprofile.json");
            Files.write(source, JsonObject.builder()
                    .put("format", "some-other-client")
                    .put("formatVersion", 1)
                    .put("profile", ProfileJson.encode(ChosenOneProfile.create()))
                    .build().toJson().getBytes(StandardCharsets.UTF_8));
            ProfileImportRejectedException thrown =
                    assertThrows(ProfileImportRejectedException.class, () -> store.importFrom(source));
            assertEquals(ProfileImportRejectedException.STEP_FORMAT, thrown.step());
        }

        @Test
        @DisplayName("step 1: a formatVersion NEWER than this build is REFUSED, not guessed at")
        void newerFormatVersionIsRefused() throws Exception {
            Path source = configDir.resolve("future.xsozprofile.json");
            Files.write(source, JsonObject.builder()
                    .put("format", "xsoz-profile")
                    .put("formatVersion", 2)
                    .put("profile", ProfileJson.encode(ChosenOneProfile.create()))
                    .build().toJson().getBytes(StandardCharsets.UTF_8));
            ProfileImportRejectedException thrown =
                    assertThrows(ProfileImportRejectedException.class, () -> store.importFrom(source));
            assertEquals(ProfileImportRejectedException.STEP_FORMAT, thrown.step());
            assertTrue(thrown.getMessage().contains("Refusing rather than guessing"),
                    thrown.getMessage());
            assertEquals(1, store.all().size());
        }

        @Test
        @DisplayName("step 2: an embedded profile whose schema is newer than this build is refused")
        void newerProfileSchemaIsRefused() throws Exception {
            Path source = configDir.resolve("future-profile.xsozprofile.json");
            Files.write(source, JsonObject.builder()
                    .put("format", "xsoz-profile")
                    .put("formatVersion", 1)
                    .put("profile", ProfileJson.encode(ChosenOneProfile.create())
                            .with("schema", 99L))
                    .build().toJson().getBytes(StandardCharsets.UTF_8));
            ProfileImportRejectedException thrown =
                    assertThrows(ProfileImportRejectedException.class, () -> store.importFrom(source));
            assertEquals(ProfileImportRejectedException.STEP_SCHEMA, thrown.step());
        }

        @Test
        @DisplayName("step 3: a setting the schema refuses REJECTS the import, rather than coercing it")
        void badSettingRejectsTheImport() throws Exception {
            store = install(richProfile());
            int before = store.all().size();
            Path export = configDir.resolve("rich.xsozprofile.json");
            store.exportTo("rich-profile", export);
            JsonObject document = JsonObject.parse(
                    new String(Files.readAllBytes(export), StandardCharsets.UTF_8));
            JsonObject modules = document.getObject("profile").getObject("modules");
            JsonObject tampered = document.with("profile", document.getObject("profile")
                    .with("modules", modules.with("hud.totem-counter",
                            modules.getObject("hud.totem-counter").with("settings",
                                    JsonObject.builder()
                                            .put("hud.totem-counter.show-carry-count", "yes please")
                                            .build()))));
            Path tamperedFile = configDir.resolve("tampered.xsozprofile.json");
            Files.write(tamperedFile, tampered.toJson().getBytes(StandardCharsets.UTF_8));
            ProfileImportRejectedException thrown =
                    assertThrows(ProfileImportRejectedException.class,
                            () -> store.importFrom(tamperedFile));
            assertEquals(ProfileImportRejectedException.STEP_VALIDATE, thrown.step());
            assertEquals(before, store.all().size(), "a rejected import inserts nothing");
        }

        @Test
        @DisplayName("step 3: an UNDECLARED setting key REJECTS the import")
        void unknownSettingRejectsTheImport() throws Exception {
            store = install(richProfile());
            Path export = configDir.resolve("rich.xsozprofile.json");
            store.exportTo("rich-profile", export);
            JsonObject document = JsonObject.parse(
                    new String(Files.readAllBytes(export), StandardCharsets.UTF_8));
            JsonObject modules = document.getObject("profile").getObject("modules");
            JsonObject tampered = document.with("profile", document.getObject("profile")
                    .with("modules", modules.with("hud.totem-counter",
                            modules.getObject("hud.totem-counter").with("settings",
                                    modules.getObject("hud.totem-counter").getObject("settings")
                                            .with("hud.totem-counter.invented", 1L)))));
            Path file = configDir.resolve("invented.xsozprofile.json");
            Files.write(file, tampered.toJson().getBytes(StandardCharsets.UTF_8));
            ProfileImportRejectedException thrown =
                    assertThrows(ProfileImportRejectedException.class, () -> store.importFrom(file));
            assertEquals(ProfileImportRejectedException.STEP_VALIDATE, thrown.step());
            assertTrue(thrown.getMessage().contains("invented"), thrown.getMessage());
        }

        @Test
        @DisplayName("step 4: a Tier C module key REJECTS the import (C7.1)")
        void tierCIsRejected() throws Exception {
            store = install(richProfile());
            Path export = configDir.resolve("rich.xsozprofile.json");
            store.exportTo("rich-profile", export);
            JsonObject document = JsonObject.parse(
                    new String(Files.readAllBytes(export), StandardCharsets.UTF_8));
            JsonObject withTierC = document.getObject("profile").getObject("modules")
                    .with(ConfigTestSupport.TIER_C_MODULE, JsonObject.builder()
                            .put("enabled", true)
                            .put("settings", JsonObject.empty())
                            .build());
            Path file = configDir.resolve("tier-c.xsozprofile.json");
            Files.write(file, document.with("profile",
                    document.getObject("profile").with("modules", withTierC))
                    .toJson().getBytes(StandardCharsets.UTF_8));
            ProfileImportRejectedException thrown =
                    assertThrows(ProfileImportRejectedException.class, () -> store.importFrom(file));
            assertEquals(ProfileImportRejectedException.STEP_TIER_C, thrown.step());
            assertTrue(thrown.getMessage().contains(ConfigTestSupport.TIER_C_MODULE),
                    thrown.getMessage());
        }

        @Test
        @DisplayName("a Tier C key in a PROFILE FILE is dropped and reported, not obeyed")
        void tierCInAFileIsDroppedAndReported() throws Exception {
            Profile tampered = richProfile().withModule(ConfigTestSupport.TIER_C_MODULE,
                    ModuleState.of(true));
            FileProfileStore reopened = install(tampered);
            Profile reloaded = reopened.all().stream()
                    .filter(p -> p.id().equals(tampered.id())).findFirst().get();
            assertFalse(reloaded.modules().containsKey(ConfigTestSupport.TIER_C_MODULE));
            assertTrue(reopened.recoveryLog().stream()
                    .anyMatch(n -> n.detail().contains(ConfigTestSupport.TIER_C_MODULE)
                            && n.detail().contains("Tier C")), reopened.recoveryLog().toString());
        }

        @Test
        @DisplayName("step 3: a keybind the policy forbids REJECTS the import")
        void forbiddenKeybindRejectsTheImport() throws Exception {
            store = install(richProfile());
            int before = store.all().size();
            Path export = configDir.resolve("rich.xsozprofile.json");
            store.exportTo("rich-profile", export);
            JsonObject document = JsonObject.parse(
                    new String(Files.readAllBytes(export), StandardCharsets.UTF_8));
            // "attack" is a no-remap, mouse-only combat action, so a keyboard key is refused as
            // COMBAT_ACTION_ON_KEYBOARD (the policy's check order puts MOUSE_ONLY before
            // NO_REMAP, so that is the reason that surfaces). The bind is
            // planted in the DOCUMENT rather than in a Profile, because a load would already have
            // reset it to unbound (C5.2) - which is the point: a file this client wrote is clean,
            // and the import check is the last line of defence for one it did not.
            JsonObject tampered = document.with("profile", document.getObject("profile")
                    .with("keybinds", document.getObject("profile").getObject("keybinds")
                            .with("attack", JsonObject.builder()
                                    .put("key", "R")
                                    .put("kind", "KEYBOARD")
                                    .put("code", 82L)
                                    .putArray("modifiers", Collections.emptyList())
                                    .build())));
            Path file = configDir.resolve("badbind.xsozprofile.json");
            Files.write(file, tampered.toJson().getBytes(StandardCharsets.UTF_8));
            ProfileImportRejectedException thrown =
                    assertThrows(ProfileImportRejectedException.class, () -> store.importFrom(file));
            assertEquals(ProfileImportRejectedException.STEP_VALIDATE, thrown.step());
            assertTrue(thrown.getMessage().contains("COMBAT_ACTION_ON_KEYBOARD"), thrown.getMessage());
            assertEquals(before, store.all().size());
        }

        @Test
        @DisplayName("a keybind the policy forbids in a PROFILE FILE is reset to unbound and reported")
        void forbiddenKeybindInAFileIsResetAndReported() throws Exception {
            Profile rich = richProfile().withKeybinds(Collections.singletonMap("attack",
                    Keybind.of(InputKey.keyboard(82, "R"))));
            FileProfileStore reopened = install(rich);
            Profile reloaded = reopened.all().stream()
                    .filter(p -> p.id().equals(rich.id())).findFirst().get();
            assertEquals(Keybind.unbound(), reloaded.keybinds().get("attack"),
                    "C5.2: a value that fails BindPolicy on load is reset to unbound");
            assertTrue(reopened.recoveryLog().stream()
                    .anyMatch(n -> n.detail().contains("attack")
                            && n.detail().contains("COMBAT_ACTION_ON_KEYBOARD")),
                    reopened.recoveryLog().toString());
        }

        @Test
        @DisplayName("an UNBOUND keybind is not sent to the policy: not-bound is a state, not a bind")
        void unboundKeybindIsNotAPolicyViolation() throws Exception {
            // C5.2's own example stores {"key": "UNKNOWN"} for hud.totem-counter.
            assertTrue(ChosenOneProfile.defaultKeybinds().containsKey("hud.totem-counter"));
            Path export = configDir.resolve("unbound.xsozprofile.json");
            store.exportTo("chosen-one", export);
            Profile imported = store.importProfileFrom(export);
            assertEquals(Keybind.unbound(), imported.keybinds().get("hud.totem-counter"));
        }

        @Test
        @DisplayName("a document with no profile object is refused")
        void noProfileIsRefused() throws Exception {
            Path source = configDir.resolve("bare.xsozprofile.json");
            Files.write(source, JsonObject.builder()
                    .put("format", "xsoz-profile")
                    .put("formatVersion", 1)
                    .build().toJson().getBytes(StandardCharsets.UTF_8));
            ProfileImportRejectedException thrown =
                    assertThrows(ProfileImportRejectedException.class, () -> store.importFrom(source));
            assertEquals(ProfileImportRejectedException.STEP_SHAPE, thrown.step());
        }

        @Test
        @DisplayName("a missing file is refused at the parse step, not as a NullPointerException")
        void missingFileIsRefused() {
            assertThrows(ProfileImportRejectedException.class,
                    () -> store.importFrom(configDir.resolve("nope.xsozprofile.json")));
        }
    }

    /** A profile with something in every field, so the round trip has something to lose. */
    private static Profile richProfile() {
        Map<String, Object> totemSettings = new LinkedHashMap<String, Object>();
        totemSettings.put("hud.totem-counter.show-carry-count", Boolean.TRUE);
        totemSettings.put("hud.totem-counter.style", "COMPACT");

        Map<String, Object> releaseSettings = new LinkedHashMap<String, Object>();
        releaseSettings.put("latency.crystal-release.release-timeout-millis", Integer.valueOf(2000));

        Map<String, Object> performanceSettings = new LinkedHashMap<String, Object>();
        performanceSettings.put("perf.sodium-tweaks.frame-cap-fps", Integer.valueOf(240));

        Map<String, ModuleState> modules = new LinkedHashMap<String, ModuleState>();
        modules.put("hud.totem-counter", ModuleState.of(true, totemSettings));
        modules.put("latency.crystal-release", ModuleState.of(false, releaseSettings));
        modules.put("perf.sodium-tweaks", ModuleState.of(true, performanceSettings));

        Map<String, Keybind> keybinds = new LinkedHashMap<String, Keybind>();
        keybinds.put("hud.totem-counter", Keybind.unbound());
        keybinds.put("open-settings", Keybind.of(InputKey.keyboard(344, "RSHIFT")));
        keybinds.put("forward", Keybind.of(InputKey.keyboard(87, "W")));
        keybinds.put("chord-example", Keybind.of(InputKey.keyboard(70, "F"), ModifierKey.SHIFT));

        List<HudElement> elements = new ArrayList<HudElement>(Arrays.asList(
                HudElement.of("hud.totem-counter", HudAnchor.TOP_RIGHT, -8.0d, -8.0d, 100, 0, true),
                HudElement.of("latency.crystal-release", HudAnchor.BOTTOM_LEFT, 4.5d, -12.25d, 90, 7,
                        false)));

        // chatCapture is FALSE here on purpose: an import FORCES it off (C5.5 step 5's spirit -
        // a file must not switch a recorder on), so a profile with it on cannot round-trip
        // losslessly. That difference is asserted on its own, in importDisarmsChatCapture.
        CoachingState coaching = CoachingState.of(
                new java.util.LinkedHashSet<String>(Arrays.asList("mcpvp.club", "pvp.land")),
                false, false, OptionalLong.of(15L));

        // C5.5 steps 5 and 6 force THREE things on insertion, and only three: a fresh id,
        // isDefault = false, and complianceProfileId = default-deny. The rich profile therefore
        // carries default-deny, so the round-trip assertion below can be a plain equality over
        // EVERY field. Each of the three forced differences has its own test in this class.
        return Profile.builder("rich-profile")
                .name("Rich Profile 123")
                .createdUtc(Instant.parse("2026-01-02T03:04:05Z"))
                .modifiedUtc(Instant.parse("2026-02-03T04:05:06Z"))
                .isDefault(false)
                .provenance(Provenance.sourced(ProvenanceKind.COMMUNITY_CONVENTION,
                        "server-maintained wiki", "https://simplyvanilla.miraheze.org/wiki/PvP_Guide",
                        java.time.LocalDate.of(2025, 12, 7),
                        "A community layout, explicitly NOT any individual player's setup"))
                .modules(modules)
                .keybinds(keybinds)
                .hud(HudLayout.of(1.75d, elements))
                .sensitivity(ProfileSensitivity.withRampStage(0.744140625d, 3200, 103,
                        FovRelativeMode.PHYSICAL, 4))
                .complianceProfileId("default-deny")
                .coaching(coaching)
                .build();
    }

    /**
     * Writes a profile to the config directory and reopens the store over it.
     *
     * <p>This is the honest way to get a specific profile into a store: the store's only
     * insert paths are {@code create}, {@code duplicate} and {@code import}, and all three
     * rewrite the id, the default flag and the compliance profile id. A file on disk is
     * what a hand-edited or migrated config actually looks like.</p>
     */
    private FileProfileStore install(Profile profile) throws Exception {
        Files.createDirectories(configDir.resolve("profiles"));
        Files.write(configDir.resolve("profiles").resolve(profile.id() + ".json"),
                ProfileJson.encode(profile).toJson().getBytes(StandardCharsets.UTF_8));
        return ConfigTestSupport.store(configDir);
    }

    /**
     * @param profile the profile to fetch back
     * @return the profile as the store holds it after the loader has resolved it
     */
    private static Profile stored(FileProfileStore store, Profile profile) {
        return store.all().stream().filter(p -> p.id().equals(profile.id()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "the store did not load " + profile.id() + "; it holds " + store.all()));
    }

    /**
     * The two fields an import is <em>required</em> to change: the fresh id, and nothing
     * else. C5.5 step 6 forces a new id; everything else must survive verbatim.
     */
    private static Profile stripVolatileFields(Profile profile) {
        return profile.copiedAs("volatile-stripped", profile.name(), profile.createdUtc(),
                profile.provenance());
    }
}
