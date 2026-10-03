package dev.xsoz.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract C5.6 - the full profile lifecycle and all four delete guards, plus the name
 * rules.
 */
class ProfileLifecycleTest {

    @TempDir
    Path configDir;

    private FileProfileStore store;

    @BeforeEach
    void openStore() {
        store = ConfigTestSupport.store(configDir);
    }

    @Nested
    @DisplayName("first run")
    class FirstRun {

        @Test
        @DisplayName("the default profile is created, written, and is the default and the active one")
        void defaultIsInstalled() {
            assertEquals(1, store.all().size());
            Profile profile = store.defaultProfile();
            assertEquals("chosen-one", profile.id());
            assertEquals("Chosen One's Profile", profile.name());
            assertTrue(profile.isDefault());
            assertEquals("chosen-one", store.activeProfile().id());
            assertTrue(Files.isRegularFile(configDir.resolve("profiles").resolve("chosen-one.json")),
                    "C5.7: the shipped profile is copied to config/profiles/chosen-one.json");
        }

        @Test
        @DisplayName("a fresh open has no recovery notice: nothing was wrong")
        void noRecoveryNotice() {
            assertTrue(store.recoveryLog().isEmpty());
            assertFalse(store.lastRecovery().isPresent());
        }

        @Test
        @DisplayName("reopening finds the same profile and writes no second one")
        void reopenIsStable() throws Exception {
            store.create("Second");
            FileProfileStore reopened = ConfigTestSupport.store(configDir);
            assertEquals(2, reopened.all().size());
            assertEquals("chosen-one", reopened.defaultProfile().id());
            try (java.util.stream.Stream<Path> files = Files.list(configDir.resolve("profiles"))) {
                assertEquals(2, files.filter(p -> p.toString().endsWith(".json")).count(),
                        "one file per profile, and no temp file left behind");
            }
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("create clones the ACTIVE profile under a fresh id and is not the default")
        void createClonesActive() {
            store.switchTo("chosen-one");
            Profile created = store.create("My Ramp");
            assertNotEquals("chosen-one", created.id());
            assertEquals("My Ramp", created.name());
            assertFalse(created.isDefault(), "C5.6: isDefault is forced false on a new profile");
            assertEquals(store.defaultProfile().id(), "chosen-one");
            assertEquals(ProvenanceKind.USER_DEFINED, created.provenance().kind(),
                    "C5.6: a clone must stop claiming the original's numbers came from someone "
                            + "else's published settings");
            assertEquals(ChosenOneProfile.CM_PER_360, created.sensitivity().cmPer360(), 0.0d);
            assertEquals(store.activeProfile().sensitivity().cmPer360(),
                    created.sensitivity().cmPer360(), 0.0d);
            assertTrue(created.name().length() > 0);
        }

        @Test
        @DisplayName("the fresh id is a valid, unused, filename-safe stem")
        void freshIdIsUsable() {
            Profile created = store.create("A Very Odd Name! 123");
            assertTrue(ProfileNames.isValidId(created.id()), created.id());
            assertFalse(created.id().contains(" "));
            assertFalse(created.id().contains("!"));
            assertTrue(Files.isRegularFile(
                    configDir.resolve("profiles").resolve(created.id() + ".json")));
        }

        @Test
        @DisplayName("two names that slugify identically still get different ids")
        void idsDoNotCollide() {
            List<String> ids = new ArrayList<String>();
            for (String name : new String[] {"Alpha One", "Alpha-One", "Alpha One!", "alpha  one"}) {
                ids.add(store.create(name).id());
            }
            assertEquals(4, new java.util.HashSet<String>(ids).size(),
                    "every id is unique, because a filename stem is not a name");
            assertEquals(5, store.all().size());
        }
    }

    @Nested
    @DisplayName("name rules")
    class Names {

        @ParameterizedTest(name = "refuses the blank name \"{0}\"")
        @ValueSource(strings = {"", "   ", "\t", "\n"})
        void blankIsRefused(String name) {
            ProfileNameInvalidException thrown = assertThrows(ProfileNameInvalidException.class,
                    () -> store.create(name));
            assertNotNull(thrown.rejectedName());
        }

        @Test
        @DisplayName("the name is trimmed before it is stored")
        void nameIsTrimmed() {
            assertEquals("Trimmed", store.create("   Trimmed   ").name());
        }

        @Test
        @DisplayName("a duplicate is refused CASE-INSENSITIVELY and on the TRIMMED name")
        void duplicatesAreCaseInsensitive() {
            store.create("Crystal PvP");
            for (String attempt : new String[] {"Crystal PvP", "crystal pvp", "CRYSTAL PVP",
                    "  Crystal PvP  "}) {
                assertThrows(ProfileNameInvalidException.class, () -> store.create(attempt),
                        "\"" + attempt + "\" must collide with \"Crystal PvP\"");
            }
            assertEquals(2, store.all().size());
        }

        @Test
        @DisplayName("a name over 48 CODE POINTS is refused, and emoji count once")
        void lengthIsBoundedInCodePoints() {
            assertEquals(48, ProfileNames.MAX_NAME_CODE_POINTS);
            assertNotNull(store.create(nameOfCodePoints(48)));
            assertThrows(ProfileNameInvalidException.class, () -> store.create(nameOfCodePoints(49)));
            // 24 emoji are 48 UTF-16 units but 24 code points: half the allowance.
            String emoji = repeat("\uD83D\uDE00", 24);
            assertEquals(48, emoji.length());
            assertEquals(24, ProfileNames.codePointCount(emoji));
            assertNotNull(store.create(emoji));
            // 48 emoji are 96 UTF-16 units but exactly 48 code points: still inside the cap,
            // which a char-counting cap would have refused.
            String fortyEight = repeat("\uD83D\uDE00", 48);
            assertEquals(96, fortyEight.length());
            assertEquals(48, ProfileNames.codePointCount(fortyEight));
            assertNotNull(store.create(fortyEight));
            // 49 code points is one too many, whatever it looks like in chars.
            assertThrows(ProfileNameInvalidException.class,
                    () -> store.create(fortyEight + "\uD83D\uDE00"));
        }

        @ParameterizedTest(name = "refuses a name carrying \"{0}\"")
        @ValueSource(strings = {"/", "\\", ":", "*", "?", "\"", "<", ">", "|"})
        void forbiddenCharactersAreRefused(String forbidden) {
            assertThrows(ProfileNameInvalidException.class,
                    () -> store.create("bad" + forbidden + "name"));
        }

        @Test
        @DisplayName("a control character in a name is refused")
        void controlCharactersAreRefused() {
            assertThrows(ProfileNameInvalidException.class,
                    () -> store.create("bad name"));
        }

        private String nameOfCodePoints(int count) {
            StringBuilder text = new StringBuilder();
            while (ProfileNames.codePointCount(text.toString()) < count) {
                text.append('x');
            }
            return text.toString();
        }

        private String repeat(String unit, int times) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < times; i++) {
                text.append(unit);
            }
            return text.toString();
        }
    }

    @Nested
    @DisplayName("rename")
    class Rename {

        @Test
        @DisplayName("a rename changes the name and NOT the id, and not the default flag")
        void renameKeepsTheId() {
            Profile before = store.defaultProfile();
            Profile renamed = store.rename(before.id(), "  Renamed Default  ");
            assertEquals(before.id(), renamed.id(), "C5.2: the id is immutable for the life of "
                    + "the profile");
            assertEquals("Renamed Default", renamed.name());
            assertTrue(renamed.isDefault(), "a rename does not touch isDefault");
            assertEquals(renamed, store.defaultProfile());
        }

        @Test
        @DisplayName("a rename onto another profile's name is refused, on the trimmed folded name")
        void renameOntoAnExistingNameIsRefused() {
            Profile other = store.create("Second");
            assertThrows(ProfileNameInvalidException.class,
                    () -> store.rename("chosen-one", "  second "));
            assertThrows(ProfileNameInvalidException.class,
                    () -> store.rename(other.id(), "CHOSEN ONE'S PROFILE"));
            assertEquals("Second", store.all().stream()
                    .filter(p -> p.id().equals(other.id())).findFirst().get().name());
        }

        @Test
        @DisplayName("renaming a profile to its own current name is a no-op, not a collision")
        void renameToOwnNameIsANoOp() {
            Profile before = store.defaultProfile();
            assertEquals(before, store.rename(before.id(), "Chosen One's Profile"));
        }

        @Test
        @DisplayName("renaming something that does not exist is a named failure")
        void renameMissingIsNamed() {
            ProfileNotFoundException thrown = assertThrows(ProfileNotFoundException.class,
                    () -> store.rename("nope", "Whatever"));
            assertEquals("nope", thrown.profileId());
        }
    }

    @Nested
    @DisplayName("duplicate")
    class Duplicate {

        @Test
        @DisplayName("a duplicate gets a NEW id, copies every setting, and is not the default")
        void duplicateIsADeepCopy() {
            Profile original = store.defaultProfile();
            Profile copy = store.duplicate(original.id(), "Copy Of Chosen One");
            assertNotEquals(original.id(), copy.id());
            assertEquals("Copy Of Chosen One", copy.name());
            assertFalse(copy.isDefault());
            assertEquals(original.sensitivity(), copy.sensitivity());
            assertEquals(original.modules(), copy.modules());
            assertEquals(original.keybinds(), copy.keybinds());
            assertEquals(original.hud(), copy.hud());
            assertEquals(original.coaching(), copy.coaching());
            assertEquals(original.complianceProfileId(), copy.complianceProfileId());
        }

        @Test
        @DisplayName("provenance is copied VERBATIM, including the source URL (C5.6)")
        void provenanceIsCopiedVerbatim() {
            Profile copy = store.duplicate("chosen-one", "Copy Of Chosen One");
            assertEquals(ProvenanceKind.PUBLISHED_SETTINGS, copy.provenance().kind());
            assertEquals("https://www.youtube.com/@Marlowww/videos", copy.provenance().sourceUrl());
            assertEquals(store.defaultProfile().provenance(), copy.provenance());
        }

        @Test
        @DisplayName("the copy's createdUtc is now, and the original's is untouched")
        void createdUtcIsNow() {
            Profile original = store.defaultProfile();
            Profile copy = store.duplicate(original.id(), "Copy Of Chosen One");
            assertEquals(ConfigTestSupport.NOW, copy.createdUtc());
            assertEquals(ChosenOneProfile.CREATED_UTC, original.createdUtc());
            assertEquals(ConfigTestSupport.NOW, copy.modifiedUtc());
        }

        @Test
        @DisplayName("duplicating does not change which profile is active or which is the default")
        void duplicateDoesNotSwitch() {
            Profile activeBefore = store.activeProfile();
            Profile defaultBefore = store.defaultProfile();
            store.duplicate("chosen-one", "Copy Of Chosen One");
            assertEquals(activeBefore.id(), store.activeProfile().id());
            assertEquals(defaultBefore.id(), store.defaultProfile().id());
        }
    }

    @Nested
    @DisplayName("the four delete guards")
    class Delete {

        @Test
        @DisplayName("guard 1: the LAST profile cannot be deleted, whatever the arguments say")
        void cannotDeleteTheLastProfile() {
            assertEquals(1, store.all().size());
            for (String[] args : new String[][] {{null, null},
                    {"chosen-one", null}, {null, "chosen-one"}, {"chosen-one", "chosen-one"}}) {
                ProfileDeleteRefusedException thrown =
                        assertThrows(ProfileDeleteRefusedException.class,
                                () -> store.delete("chosen-one", args[0], args[1]));
                assertEquals(ProfileDeleteRefusedException.LAST_PROFILE, thrown.refusalReason());
                assertTrue(thrown.getMessage().contains("cannot delete the last profile"),
                        thrown.getMessage());
            }
            assertEquals(1, store.all().size());
        }

        @Test
        @DisplayName("guard 2: the default cannot be deleted without a reassignment")
        void defaultNeedsReassignment() {
            store.create("Second");
            ProfileDeleteRefusedException thrown = assertThrows(ProfileDeleteRefusedException.class,
                    () -> store.delete("chosen-one", null, null));
            assertEquals(ProfileDeleteRefusedException.DEFAULT_NEEDS_REASSIGNMENT,
                    thrown.refusalReason());
            assertTrue(thrown.getMessage().contains("default profile requires reassignment"),
                    thrown.getMessage());
            assertEquals(2, store.all().size(), "a refused delete changes nothing");
            assertEquals("chosen-one", store.defaultProfile().id());
        }

        @Test
        @DisplayName("guard 2: the reassignment must name an existing OTHER profile, and it is applied")
        void defaultReassignmentIsApplied() {
            Profile second = store.create("Second");
            // chosen-one is both the default AND the active profile on a fresh store, so both
            // guards apply and both successors have to be named.
            store.delete("chosen-one", second.id(), second.id());
            assertEquals(1, store.all().size());
            assertEquals(second.id(), store.defaultProfile().id());
            assertTrue(store.defaultProfile().isDefault());
            assertEquals(second.id(), store.activeProfile().id());
        }

        @Test
        @DisplayName("guard 2: a reassignment to the profile being deleted, or to nothing, is refused")
        void defaultReassignmentIsValidated() {
            store.create("Second");
            assertThrows(ProfileDeleteRefusedException.class,
                    () -> store.delete("chosen-one", "chosen-one", null));
            assertThrows(ProfileNotFoundException.class,
                    () -> store.delete("chosen-one", "no-such-profile", null));
            assertEquals(2, store.all().size());
        }

        @Test
        @DisplayName("guard 3: the ACTIVE profile cannot be deleted without a successor")
        void activeNeedsSuccessor() {
            Profile second = store.create("Second");
            store.switchTo(second.id());
            assertEquals(second.id(), store.activeProfile().id());
            ProfileDeleteRefusedException thrown = assertThrows(ProfileDeleteRefusedException.class,
                    () -> store.delete(second.id(), null, null));
            assertEquals(ProfileDeleteRefusedException.ACTIVE_NEEDS_SUCCESSOR, thrown.refusalReason());
            assertTrue(thrown.getMessage().contains("active profile requires a successor"),
                    thrown.getMessage());
            assertEquals(second.id(), store.activeProfile().id(), "the switch did not happen");
        }

        @Test
        @DisplayName("guard 3: with a successor, the switch happens first and atomically")
        void activeSuccessorIsApplied() {
            Profile second = store.create("Second");
            store.switchTo(second.id());
            Profile active = store.delete(second.id(), null, "chosen-one");
            assertEquals("chosen-one", active.id());
            assertEquals("chosen-one", store.activeProfile().id());
            assertEquals(1, store.all().size());
            assertTrue(active.isDefault());
        }

        @Test
        @DisplayName("a non-default, non-active profile deletes with no arguments at all")
        void plainDeleteNeedsNoArguments() {
            Profile second = store.create("Second");
            Profile third = store.create("Third");
            store.delete(third.id(), null, null);
            assertEquals(2, store.all().size());
            assertFalse(Files.exists(configDir.resolve("profiles").resolve(third.id() + ".json")));
            assertTrue(Files.exists(configDir.resolve("profiles").resolve(second.id() + ".json")));
        }

        @Test
        @DisplayName("guard 4: a file that cannot be deleted leaves a ZOMBIE id that cannot be reused")
        void undeletableFileBecomesAZombie() throws Exception {
            Profile second = store.create("Second");
            Path file = configDir.resolve("profiles").resolve(second.id() + ".json");
            Files.delete(file);
            // Replace the file with a NON-EMPTY directory of the same name, so the delete fails
            // on every platform including Windows, where a read-only file is still deletable.
            Files.createDirectories(file.resolve("occupied"));
            store.delete(second.id(), null, null);
            assertEquals(1, store.all().size());
            assertTrue(store.isZombie(second.id()),
                    "the id is held so a later create or import cannot resurrect it");
            assertEquals(ConfigRecoveryOutcome.PROFILE_FILE_DELETE_FAILED,
                    store.lastRecovery().get().outcome());
            assertTrue(store.lastRecovery().get().detail().contains("in-memory state is correct"),
                    store.lastRecovery().get().detail());
            // A create that would otherwise slugify to the same stem must not reuse the id.
            Profile other = store.create("Second");
            assertNotEquals(second.id(), other.id());
        }

        @Test
        @DisplayName("deleting something that does not exist is a named failure")
        void deleteMissingIsNamed() {
            assertThrows(ProfileNotFoundException.class, () -> store.delete("nope", null, null));
        }
    }

    @Nested
    @DisplayName("setDefault and switchTo")
    class DefaultAndSwitch {

        @Test
        @DisplayName("setDefault moves the flag and leaves exactly one holder, every time")
        void setDefaultIsExclusive() {
            Profile second = store.create("Second");
            store.setDefault(second.id());
            assertEquals(second.id(), store.defaultProfile().id());
            assertEquals(1, countDefaults(store));
            Profile third = store.create("Third");
            store.setDefault(third.id());
            assertEquals(third.id(), store.defaultProfile().id());
            assertEquals(1, countDefaults(store));
        }

        @Test
        @DisplayName("switchTo never requires a restart and notifies the listener")
        void switchNotifies() {
            ProfileSwitchListener.RecordingProfileSwitchListener listener =
                    ProfileSwitchListener.recording();
            store.addSwitchListener(listener);
            Profile second = store.create("Second");
            store.switchTo(second.id());
            assertEquals(second.id(), store.activeProfile().id());
            assertEquals(1, listener.count());
            assertEquals("chosen-one", listener.switches().get(0).previous().id());
            assertEquals(second.id(), listener.switches().get(0).current().id());
            assertEquals("user", listener.switches().get(0).reason());
        }

        @Test
        @DisplayName("switching to the already-active profile is a no-op and fires nothing")
        void switchToSelfIsANoOp() {
            ProfileSwitchListener.RecordingProfileSwitchListener listener =
                    ProfileSwitchListener.recording();
            store.addSwitchListener(listener);
            store.switchTo("chosen-one");
            assertEquals(0, listener.count());
        }

        @Test
        @DisplayName("the active pointer is persisted in xsozclient.json and survives a reopen")
        void activePointerIsPersisted() {
            Profile second = store.create("Second");
            store.switchTo(second.id());
            assertTrue(Files.isRegularFile(configDir.resolve("xsozclient.json")));
            FileProfileStore reopened = ConfigTestSupport.store(configDir);
            assertEquals(second.id(), reopened.activeProfile().id(),
                    "C5.1: lastProfileId is the store-level pointer that must survive a restart");
            assertEquals(second.id(), reopened.globalConfig().lastProfileId());
        }

        @Test
        @DisplayName("an active pointer naming a profile that no longer exists falls back to the default")
        void danglingActivePointerFallsBack() {
            Profile second = store.create("Second");
            store.switchTo(second.id());
            store.delete(second.id(), null, "chosen-one");
            assertEquals("chosen-one", store.activeProfile().id());
            assertEquals("chosen-one", store.globalConfig().lastProfileId(),
                    "the pointer was moved as part of the switch, before the delete committed");
            assertTrue(store.recoveryLog().isEmpty(),
                    "a clean delete recovers nothing, so there is no notice to show");
        }

        @Test
        @DisplayName("switching to a profile that does not exist is a named failure")
        void switchMissingIsNamed() {
            assertThrows(ProfileNotFoundException.class, () -> store.switchTo("nope"));
        }
    }

    @Nested
    @DisplayName("post-conditions")
    class PostConditions {

        @Test
        @DisplayName("exactly one profile is the default after every kind of mutation")
        void exactlyOneDefaultAlways() {
            assertEquals(1, countDefaults(store));
            Profile second = store.create("Second");
            assertEquals(1, countDefaults(store));
            store.rename(second.id(), "Renamed");
            assertEquals(1, countDefaults(store));
            store.duplicate(second.id(), "Copy");
            assertEquals(1, countDefaults(store));
            store.setDefault(second.id());
            assertEquals(1, countDefaults(store));
            store.switchTo(second.id());
            assertEquals(1, countDefaults(store));
            store.save(store.activeProfile().withName("Saved"));
            assertEquals(1, countDefaults(store));
            store.delete(second.id(), "chosen-one", "chosen-one");
            assertEquals(1, countDefaults(store));
        }

        @Test
        @DisplayName("all() is sorted by id ascending, byte order")
        void allIsSorted() {
            store.create("Zebra");
            store.create("Apple");
            store.create("Mango");
            List<String> ids = new ArrayList<String>();
            for (Profile profile : store.all()) {
                ids.add(profile.id());
            }
            List<String> sorted = new ArrayList<String>(ids);
            java.util.Collections.sort(sorted);
            assertEquals(sorted, ids);
        }

        @Test
        @DisplayName("save advances modifiedUtc and leaves createdUtc alone")
        void saveAdvancesModifiedUtc() {
            Profile before = store.defaultProfile();
            Profile saved = store.save(before.withName("Saved Name"));
            assertEquals(ConfigTestSupport.NOW, saved.modifiedUtc());
            assertEquals(ChosenOneProfile.CREATED_UTC, saved.createdUtc());
            assertEquals("Saved Name", saved.name());
            assertEquals("Saved Name", ConfigTestSupport.store(configDir).defaultProfile().name());
        }

        @Test
        @DisplayName("save refuses a profile whose id is not in the store")
        void saveMissingIsNamed() {
            assertThrows(ProfileNotFoundException.class,
                    () -> store.save(store.defaultProfile().copiedAs("ghost", "Ghost",
                            ConfigTestSupport.NOW, Provenance.userDefined("x"))));
        }
    }

    private static int countDefaults(FileProfileStore store) {
        int count = 0;
        for (Profile profile : store.all()) {
            if (profile.isDefault()) {
                count++;
            }
        }
        return count;
    }
}
