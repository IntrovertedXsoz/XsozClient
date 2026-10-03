package dev.xsoz.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract C5.3 and C5.4 - durability.
 *
 * <p><strong>A corrupt config degrades to a working default; it never throws on
 * startup.</strong> Every test here opens a store over something broken and asserts that a
 * usable profile came back and a {@link RecoveryNotice} says what happened.</p>
 */
class ConfigDurabilityTest {

    @TempDir
    Path configDir;

    private Path profilesDir;

    @BeforeEach
    void setUp() throws Exception {
        profilesDir = Files.createDirectories(configDir.resolve("profiles"));
    }

    @Nested
    @DisplayName("C5.3 the atomic write")
    class AtomicWrite {

        @Test
        @DisplayName("a successful save leaves no .tmp behind and the file is whole")
        void successfulSaveLeavesNoTemp() throws Exception {
            FileProfileStore store = ConfigTestSupport.store(configDir);
            store.save(store.defaultProfile().withName("Saved"));
            try (Stream<Path> files = Files.list(profilesDir)) {
                List<String> names = files.map(p -> p.getFileName().toString()).sorted()
                        .collect(java.util.stream.Collectors.toList());
                assertEquals(java.util.Arrays.asList("chosen-one.json", "chosen-one.json.bak"),
                        names, "one whole file plus the single rollback level, and NO .tmp");
            }
            for (String name : names()) {
                assertFalse(name.endsWith(".tmp"),
                        "C5.3: a .tmp is never left behind on a successful save, found " + name);
            }
            assertNotEquals(0, Files.size(profilesDir.resolve("chosen-one.json")));
        }

        private List<String> names() throws Exception {
            try (Stream<Path> files = Files.list(profilesDir)) {
                return files.map(p -> p.getFileName().toString()).collect(
                        java.util.stream.Collectors.toList());
            }
        }

        @Test
        @DisplayName("a FAILED write leaves the previous file byte-for-byte intact")
        void failedWriteLeavesThePreviousFileIntact() throws Exception {
            FileProfileStore store = ConfigTestSupport.store(configDir);
            store.save(store.defaultProfile().withName("Good Name"));
            Path file = profilesDir.resolve("chosen-one.json");
            byte[] before = Files.readAllBytes(file);

            // Occupy the temp path with a NON-EMPTY DIRECTORY, so the temp write fails on
            // every platform. On Windows a read-only file is still deletable, so making the
            // target read-only would prove nothing; a directory at the temp path is a real,
            // reproducible failure at C5.3 step 3.
            Path tmp = profilesDir.resolve("chosen-one.json.tmp");
            Files.createDirectories(tmp.resolve("occupied"));
            Files.write(tmp.resolve("occupied").resolve("marker"), new byte[] {1});

            assertThrows(dev.xsoz.core.XsozContractException.class,
                    () -> store.save(store.defaultProfile().withName("Bad Name")));

            assertArrayEqualsBytes(before, Files.readAllBytes(file),
                    "C5.3: the temp write happens before the backup copy and the rename, so a "
                            + "failure at any point leaves the old COMPLETE file in place");
            assertTrue(Files.isDirectory(tmp), "the occupied temp path was not removed or altered");
            assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                            .contains("\"name\": \"Good Name\""),
                    "the file on disk is still the LAST GOOD save, not the failed one");
        }

        @Test
        @DisplayName("the backup level is taken once per session per profile, not once per save")
        void backupIsOncePerSession() throws Exception {
            FileProfileStore store = ConfigTestSupport.store(configDir);
            store.save(store.defaultProfile().withName("First Save"));
            Path backup = profilesDir.resolve("chosen-one.json.bak");
            assertTrue(Files.isRegularFile(backup), "the first save of an existing file takes a backup");
            byte[] firstBackup = Files.readAllBytes(backup);

            store.save(store.defaultProfile().withName("Second Save"));
            store.save(store.defaultProfile().withName("Third Save"));
            assertArrayEqualsBytes(firstBackup, Files.readAllBytes(backup),
                    "C5.3 step 4: exactly one rollback level per session, so a second save does not "
                            + "overwrite the only backup with something newer");
            assertTrue(new String(Files.readAllBytes(profilesDir.resolve("chosen-one.json")),
                    StandardCharsets.UTF_8).contains("Third Save"));
        }

        @Test
        @DisplayName("a reopened store sees the last save, so the write was not merely written")
        void reopenSeesTheLastSave() {
            FileProfileStore store = ConfigTestSupport.store(configDir);
            store.save(store.defaultProfile().withName("Persisted"));
            assertEquals("Persisted", ConfigTestSupport.store(configDir).defaultProfile().name());
        }
    }

    @Nested
    @DisplayName("C5.4 the four recovery steps")
    class Recovery {

        @Test
        @DisplayName("garbage JSON degrades to a working default and NEVER throws on startup")
        void garbageDegradesToTheDefault() throws Exception {
            Files.write(profilesDir.resolve("chosen-one.json"),
                    "}{ this is not json at all ][".getBytes(StandardCharsets.UTF_8));
            FileProfileStore store = ConfigTestSupport.store(configDir);

            assertEquals(1, store.all().size());
            assertEquals("Chosen One's Profile", store.defaultProfile().name());
            assertTrue(store.defaultProfile().isDefault());
            assertEquals("chosen-one", store.activeProfile().id());
            assertEquals(0.744140625d, store.defaultProfile().sensitivity().cmPer360(), 0.0d,
                    "the default that came back is the real default, not an empty shell");
            assertEquals(ConfigRecoveryOutcome.CONFIG_RESET_TO_DEFAULT,
                    store.lastRecovery().get().outcome());
            assertTrue(store.lastRecovery().get().detail().contains("Nothing was deleted")
                    || store.lastRecovery().get().detail().contains("nothing was deleted"),
                    store.lastRecovery().get().detail());
        }

        @Test
        @DisplayName("the unreadable bytes are PRESERVED before anything is written over the file")
        void unreadableBytesArePreserved() throws Exception {
            String garbage = "}{ this is not json at all ][";
            Files.write(profilesDir.resolve("chosen-one.json"), garbage.getBytes(StandardCharsets.UTF_8));
            FileProfileStore store = ConfigTestSupport.store(configDir);

            String evidence = store.lastRecovery().get().evidencePath();
            assertNotEquals(null, evidence, "C5.4 step 2: never delete or overwrite a file we could "
                    + "not read");
            Path preserved = Path.of(evidence);
            assertTrue(Files.isRegularFile(preserved));
            assertEquals(garbage, new String(Files.readAllBytes(preserved), StandardCharsets.UTF_8),
                    "the rejected copy holds the exact bytes that were on disk");
            assertTrue(preserved.getFileName().toString().contains(".rejected-"),
                    preserved.getFileName().toString());
            assertTrue(store.lastRecovery().get().logLine().contains("rejected"),
                    store.lastRecovery().get().logLine());
        }

        @Test
        @DisplayName("a good backup is used, written back, and reported as CONFIG_RECOVERED_FROM_BACKUP")
        void backupIsUsed() throws Exception {
            // Session one: a save takes the backup level, which holds the file AS IT WAS BEFORE
            // the save. Session two is a fresh store, so its first save takes a NEW backup level
            // holding session one's last file. That is what the recovery path then has to work
            // with, and it is why the backup is once per SESSION rather than once per save.
            FileProfileStore sessionOne = ConfigTestSupport.store(configDir);
            sessionOne.save(sessionOne.defaultProfile().withName("Known Good"));
            FileProfileStore sessionTwo = ConfigTestSupport.store(configDir);
            sessionTwo.save(sessionTwo.defaultProfile().withName("Newer"));
            Path backup = profilesDir.resolve("chosen-one.json.bak");
            assertTrue(new String(Files.readAllBytes(backup), StandardCharsets.UTF_8)
                    .contains("Known Good"), "the backup holds the previous session's last file");

            Files.write(profilesDir.resolve("chosen-one.json"),
                    "totally broken {{{".getBytes(StandardCharsets.UTF_8));
            FileProfileStore recovered = ConfigTestSupport.store(configDir);

            assertEquals(1, recovered.all().size());
            assertEquals("Known Good", recovered.defaultProfile().name(),
                    "C5.4 step 3: the single backup level was used");
            assertEquals(ConfigRecoveryOutcome.CONFIG_RECOVERED_FROM_BACKUP,
                    recovered.lastRecovery().get().outcome());
            assertTrue(new String(Files.readAllBytes(profilesDir.resolve("chosen-one.json")),
                            StandardCharsets.UTF_8).contains("Known Good"),
                    "C5.4 step 3: the recovered profile is written back over the broken file, so the "
                            + "next launch is clean");
        }

        @Test
        @DisplayName("when both the file and the backup are broken, the default is written instead")
        void bothBrokenFallsBackToTheDefault() throws Exception {
            FileProfileStore first = ConfigTestSupport.store(configDir);
            first.save(first.defaultProfile().withName("Known Good"));
            Files.write(profilesDir.resolve("chosen-one.json"),
                    "broken primary".getBytes(StandardCharsets.UTF_8));
            Files.write(profilesDir.resolve("chosen-one.json.bak"),
                    "broken backup".getBytes(StandardCharsets.UTF_8));

            FileProfileStore recovered = ConfigTestSupport.store(configDir);
            assertEquals("Chosen One's Profile", recovered.defaultProfile().name());
            assertEquals(ConfigRecoveryOutcome.CONFIG_RESET_TO_DEFAULT,
                    recovered.lastRecovery().get().outcome());
            assertTrue(Files.isRegularFile(profilesDir.resolve("chosen-one.json")));
            assertTrue(new String(Files.readAllBytes(profilesDir.resolve("chosen-one.json")),
                    StandardCharsets.UTF_8).contains("Chosen One's Profile"));
        }

        @Test
        @DisplayName("a profile whose file is unreadable recovers to the default UNDER ITS OWN ID")
        void recoveryKeepsTheId() throws Exception {
            Files.write(profilesDir.resolve("chosen-one.json"),
                    ProfileJson.encode(ChosenOneProfile.create())
                            .toJson().getBytes(StandardCharsets.UTF_8));
            Files.write(profilesDir.resolve("second.json"),
                    "nope".getBytes(StandardCharsets.UTF_8));
            FileProfileStore store = ConfigTestSupport.store(configDir);
            assertEquals(2, store.all().size(), "both profiles are present after recovery");
            Profile second = store.all().stream()
                    .filter(p -> p.id().equals("second")).findFirst().get();
            assertEquals("Chosen One's Profile", second.name(),
                    "C5.4 step 4 builds the registered default; the id stays the filename stem so "
                            + "every reference to it keeps resolving");
            assertFalse(second.isDefault(), "a recovery must never create a SECOND default");
            assertEquals(1, store.all().stream().filter(Profile::isDefault).count());
            assertEquals("chosen-one", store.defaultProfile().id());
        }

        @Test
        @DisplayName("an unreadable xsozclient.json resets the pointer and leaves the profiles alone")
        void globalConfigRecovery() throws Exception {
            FileProfileStore first = ConfigTestSupport.store(configDir);
            Profile second = first.create("Second");
            first.switchTo(second.id());
            Files.write(configDir.resolve("xsozclient.json"),
                    "not json".getBytes(StandardCharsets.UTF_8));

            FileProfileStore reopened = ConfigTestSupport.store(configDir);
            assertEquals(2, reopened.all().size(), "the profiles themselves are untouched");
            assertEquals("chosen-one", reopened.activeProfile().id(),
                    "the pointer falls back to the default");
            assertTrue(reopened.recoveryLog().stream()
                    .anyMatch(n -> n.detail().contains("xsozclient.json")),
                    reopened.recoveryLog().toString());
        }

        @Test
        @DisplayName("a file whose id does not match its filename is recovered, not silently re-homed")
        void idMismatchIsRecovered() throws Exception {
            Files.write(profilesDir.resolve("chosen-one.json"),
                    ProfileJson.encode(ChosenOneProfile.create().copiedAs("a-different-id",
                            "Renamed By Hand", ChosenOneProfile.CREATED_UTC,
                            ChosenOneProfile.provenance()))
                            .toJson().getBytes(StandardCharsets.UTF_8));
            FileProfileStore store = ConfigTestSupport.store(configDir);
            assertEquals(1, store.all().size());
            assertEquals("chosen-one", store.all().get(0).id(),
                    "the filename is the id, and a hand-renamed file is not re-homed into an id "
                            + "that may already exist");
            assertTrue(store.lastRecovery().isPresent());
        }

        @Test
        @DisplayName("a file with no schema key at all is recovered, not guessed at")
        void noSchemaIsRecovered() throws Exception {
            Files.write(profilesDir.resolve("chosen-one.json"),
                    "{\"id\": \"chosen-one\", \"name\": \"No Version\"}"
                            .getBytes(StandardCharsets.UTF_8));
            FileProfileStore store = ConfigTestSupport.store(configDir);
            assertEquals("Chosen One's Profile", store.defaultProfile().name());
            assertEquals(ConfigRecoveryOutcome.CONFIG_RESET_TO_DEFAULT,
                    store.lastRecovery().get().outcome());
        }

        @Test
        @DisplayName("a file declaring a NEWER schema is REFUSED, not partially read")
        void newerSchemaIsRefused() throws Exception {
            Files.write(profilesDir.resolve("chosen-one.json"),
                    ProfileJson.encode(ChosenOneProfile.create())
                            .with("schema", 99L).toJson().getBytes(StandardCharsets.UTF_8));
            FileProfileStore store = ConfigTestSupport.store(configDir);
            assertEquals("Chosen One's Profile", store.defaultProfile().name());
            assertTrue(store.lastRecovery().get().detail()
                    .contains("newer than the"), store.lastRecovery().get().detail());
        }

        @Test
        @DisplayName("a store that cannot create its directory still opens, and serves the default in memory")
        void unopenableDirectoryDegradesToMemory() throws Exception {
            // A FILE where the config directory should be, so createDirectories fails for a
            // reason no filesystem will let us talk our way out of.
            Path blocked = configDir.resolve("blocked");
            Files.write(blocked, new byte[] {1});
            FileProfileStore store = FileProfileStore.open(blocked, ConfigTestSupport.clock(),
                    ConfigTestSupport.catalog(),
                    FileProfileStore.DEFAULT_BUNDLED_COMPLIANCE_IDS,
                    new dev.xsoz.core.keybind.DefaultBindPolicy());

            assertEquals(1, store.all().size());
            assertEquals("Chosen One's Profile", store.defaultProfile().name(),
                    "a config that cannot be opened at all still degrades to a WORKING default, "
                            + "because a session with no profile has nothing to degrade to");
            assertTrue(store.defaultProfile().isDefault());
            assertTrue(store.recoveryLog().stream()
                    .anyMatch(n -> n.outcome() == ConfigRecoveryOutcome.STORE_OPENED_IN_MEMORY),
                    store.recoveryLog().toString());
        }
    }

    @Nested
    @DisplayName("a store with no module catalog")
    class NoCatalog {

        @Test
        @DisplayName("a profile is NOT emptied just because the catalog has not been wired yet")
        void emptyCatalogDoesNotWipeProfiles() throws Exception {
            Files.write(profilesDir.resolve("chosen-one.json"),
                    ProfileJson.encode(ChosenOneProfile.create())
                            .toJson().getBytes(StandardCharsets.UTF_8));
            Files.write(profilesDir.resolve("legacy.json"),
                    ProfileJson.encode(ChosenOneProfile.create().copiedAs("legacy", "Legacy",
                            ChosenOneProfile.CREATED_UTC, ChosenOneProfile.provenance()))
                            .toJson().getBytes(StandardCharsets.UTF_8));
            FileProfileStore store = ConfigTestSupport.storeWithoutCatalog(configDir);
            assertEquals(2, store.all().size());
            assertEquals(2, store.all().get(0).modules().size(),
                    "\"no catalog\" means NOT INSTALLED, not \"no modules exist\": a store that "
                            + "dropped every module key here would empty every profile on first run");
            assertTrue(store.recoveryLog().isEmpty());
        }
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual, String why) {
        assertEquals(new String(expected, StandardCharsets.UTF_8),
                new String(actual, StandardCharsets.UTF_8), why);
    }
}
