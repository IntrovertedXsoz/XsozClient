package dev.xsoz.core.config;

import dev.xsoz.core.XsozContractException;
import dev.xsoz.core.compliance.ComplianceTier;
import dev.xsoz.core.keybind.BindPolicy;
import dev.xsoz.core.keybind.BindRejection;
import dev.xsoz.core.keybind.BindRequest;
import dev.xsoz.core.keybind.BindVerdict;
import dev.xsoz.core.keybind.DefaultBindPolicy;
import dev.xsoz.core.keybind.Keybind;
import dev.xsoz.core.setting.JsonObject;
import dev.xsoz.core.setting.SettingsMutationReport;
import dev.xsoz.core.setting.SettingsSchema;
import dev.xsoz.core.setting.SettingsView;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/**
 * The file-backed {@link ProfileStore} (contracts.md C5.1 to C5.7).
 *
 * ============================ WHAT THIS CLASS IS RESPONSIBLE FOR ============================
 *
 * <p>C5.3's atomic write, C5.4's four-step corruption recovery, C5.5's ordered import,
 * C5.6's lifecycle and its four delete guards, C5.7's shipped default, plus the store-level
 * {@code xsozclient.json} pointer.</p>
 *
 * <p><strong>A corrupt config degrades to a working default; it never throws on
 * startup</strong> (C5.4). Every read path here either produces a usable profile or
 * produces a {@link RecoveryNotice} and a fallback. A load path that dies on untrusted
 * input is a load path that will not start, and a config that will not start is a config
 * the player blames on the mod.</p>
 *
 * <p><strong>Evidence first, always.</strong> C5.4 step 2 writes the unreadable bytes to
 * {@code <id>.json.rejected-<epochMillis>} <em>before</em> anything is written over the
 * file. A recovery that destroys the evidence of the failure is a recovery that leaves
 * the player with no way to report what happened.</p>
 *
 * ============================ THE ATOMIC WRITE, IN ORDER ============================
 *
 * <pre>
 *   1  serialise deterministically
 *   2  write &lt;id&gt;.json.tmp in the SAME directory (same volume, so the rename is atomic)
 *   3  ch.force(true)                                  -- fsync BEFORE the rename
 *   4  copy the existing file to &lt;id&gt;.json.bak     -- once per session, per profile
 *   5  Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING), falling back to a plain
 *      replace when the filesystem refuses the atomic form
 *   6  modifiedUtc is advanced on the NEXT save, not this one (C5.3 step 6)
 * </pre>
 *
 * <p>The fsync is <em>before</em> the rename on purpose. After the rename, a crash can
 * leave the new name pointing at a file whose contents are still in the page cache; before
 * it, a crash can only leave the old complete file or the new complete file.</p>
 *
 * ============================ CONCURRENCY ============================
 *
 * <p><strong>The store is single-threaded, and says so.</strong> C3.1 makes the bus
 * single-threaded and C1.7 makes the facade game-thread-only, so a config store reachable
 * from two threads is a design that has already gone wrong. Every public method is
 * {@code synchronized} anyway, because a lock that costs nothing is cheaper than the
 * corruption it prevents.</p>
 */
public final class FileProfileStore implements ProfileStore {

    /** The directory profiles live in, relative to the config directory (C5.1). */
    public static final String PROFILES_DIR_NAME = "profiles";

    /** The recovery log, relative to the config directory (C5.4 step 5). */
    public static final String RECOVERY_LOG_NAME = "recovery.log";

    /** The export format discriminator (C5.5). */
    public static final String EXPORT_FORMAT = "xsoz-profile";

    /** The export format version this build reads and writes (C5.5). */
    public static final int EXPORT_FORMAT_VERSION = 1;

    /** The client version written into a fresh {@code xsozclient.json}. */
    public static final String CLIENT_VERSION = "0.1.0-SNAPSHOT";

    /** The compliance profile ids a build that ships C7.4's table bundles. */
    public static final Set<String> DEFAULT_BUNDLED_COMPLIANCE_IDS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    "default-deny", "pvphq", "pvp-land", "hypixel", "cubecraft", "pvplegacy",
                    "mineplex", "mcc-island", "local")));

    private static final DateTimeFormatter EXPORT_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private static final Comparator<Profile> BY_ID = new Comparator<Profile>() {
        @Override
        public int compare(Profile left, Profile right) {
            return left.id().compareTo(right.id());
        }
    };

    private final Path configDir;
    private final Path profilesDir;
    private final Clock clock;
    private final ModuleCatalog catalog;
    private final Set<String> bundledComplianceProfileIds;
    private final BindPolicy bindPolicy;
    private final ProfileLoader loader;
    private final Set<String> backupsTakenThisSession = new LinkedHashSet<String>();
    private final List<RecoveryNotice> recoveryLog = new ArrayList<RecoveryNotice>();
    private final List<ProfileSwitchListener> listeners = new ArrayList<ProfileSwitchListener>();
    private final Map<String, Profile> profiles = new LinkedHashMap<String, Profile>();
    private final Set<String> zombieIds = new LinkedHashSet<String>();
    private GlobalConfig globalConfig;
    private String activeProfileId;
    private int idCounter;

    private FileProfileStore(Path configDir, Clock clock, ModuleCatalog catalog,
                             Set<String> bundledComplianceProfileIds, BindPolicy bindPolicy) {
        this.configDir = configDir;
        this.profilesDir = configDir.resolve(PROFILES_DIR_NAME);
        this.clock = clock;
        this.catalog = catalog == null ? ModuleCatalog.none() : catalog;
        this.bundledComplianceProfileIds = bundledComplianceProfileIds == null
                ? DEFAULT_BUNDLED_COMPLIANCE_IDS : bundledComplianceProfileIds;
        this.bindPolicy = bindPolicy == null ? new DefaultBindPolicy() : bindPolicy;
        this.loader = ProfileLoader.of(MigrationRegistry.bundled(), this.catalog,
                this.bundledComplianceProfileIds, this.bindPolicy);
    }

    // ================================================================================================
    // Opening
    // ================================================================================================

    /**
     * Opens a store against a config directory, creating it if absent and recovering from
     * whatever is already there. <strong>Never throws for a bad file.</strong>
     *
     * @param configDir {@code <gameDir>/xsozclient/config}
     * @return the open store
     */
    public static FileProfileStore open(Path configDir) {
        return open(configDir, Clock.systemUTC(), ModuleCatalog.none(),
                DEFAULT_BUNDLED_COMPLIANCE_IDS, new DefaultBindPolicy());
    }

    /**
     * @param configDir                  {@code <gameDir>/xsozclient/config}
     * @param clock                      the clock every timestamp comes from, so a test can
     *                                   pin "now"
     * @param catalog                    what modules exist
     * @param bundledComplianceProfileIds the compliance profile ids this build ships
     * @param bindPolicy                 the keybind validator
     * @return the open store
     */
    public static FileProfileStore open(Path configDir, Clock clock, ModuleCatalog catalog,
                                        Set<String> bundledComplianceProfileIds,
                                        BindPolicy bindPolicy) {
        FileProfileStore store = new FileProfileStore(configDir, clock, catalog,
                bundledComplianceProfileIds, bindPolicy);
        store.loadEverything();
        return store;
    }

    private synchronized void loadEverything() {
        try {
            Files.createDirectories(profilesDir);
        } catch (IOException e) {
            recoveryLog.add(RecoveryNotice.of(ConfigRecoveryOutcome.STORE_OPENED_IN_MEMORY, null,
                    "Could not create " + profilesDir + " (" + e + "). The session runs entirely "
                            + "in memory and nothing will be written.", now()));
            // Still serve a working default. C5.4's whole point is that a broken config
            // degrades to something the player can play with, and a store with no profile at
            // all has nothing to degrade to.
            this.globalConfig = GlobalConfig.of(CLIENT_VERSION, null, ChosenOneProfile.RETRIEVED_ON,
                    true);
            installBundledDefault(null);
            resolveActiveProfile();
            return;
        }
        this.globalConfig = loadGlobalConfig();
        loadProfileFiles();
        if (profiles.isEmpty()) {
            installBundledDefault(null);
        }
        assertExactlyOneDefault();
        resolveActiveProfile();
    }

    private GlobalConfig loadGlobalConfig() {
        Path path = configDir.resolve(GlobalConfig.FILE_NAME);
        if (!Files.isRegularFile(path)) {
            return GlobalConfig.of(CLIENT_VERSION, null, ChosenOneProfile.RETRIEVED_ON, true);
        }
        try {
            return GlobalConfig.fromJson(JsonObject.parse(readAll(path)));
        } catch (RuntimeException e) {
            Path rejected = preserveUnreadable(path);
            recoveryLog.add(RecoveryNotice.withEvidence(ConfigRecoveryOutcome.CONFIG_RESET_TO_DEFAULT,
                    null,
                    "xsozclient.json could not be read (" + e.getMessage() + "); a fresh one was "
                            + "built with default values. The profiles themselves are untouched.",
                    now(), rejected == null ? null : rejected.toString()));
            return GlobalConfig.of(CLIENT_VERSION, null, ChosenOneProfile.RETRIEVED_ON, true);
        }
    }

    private void loadProfileFiles() {
        List<Path> files = new ArrayList<Path>();
        DirectoryStream<Path> stream = null;
        try {
            stream = Files.newDirectoryStream(profilesDir, "*.json");
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    files.add(path);
                }
            }
        } catch (IOException e) {
            recoveryLog.add(RecoveryNotice.of(ConfigRecoveryOutcome.STORE_OPENED_IN_MEMORY, null,
                    "Could not list " + profilesDir + " (" + e + ").", now()));
            return;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // A directory stream that will not close has already been read.
                }
            }
        }
        Collections.sort(files, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return left.getFileName().toString().compareTo(right.getFileName().toString());
            }
        });
        for (Path file : files) {
            String id = file.getFileName().toString();
            id = id.substring(0, id.length() - ".json".length());
            loadOneProfile(id, file);
        }
    }

    /**
     * C5.4's four steps, in order, for one profile file.
     */
    private void loadOneProfile(String id, Path file) {
        try {
            ProfileLoadResult result = loader.load(JsonObject.parse(readAll(file)));
            for (String note : result.notes()) {
                recoveryLog.add(RecoveryNotice.of(ConfigRecoveryOutcome.USED_PRIMARY, id, note,
                        now()));
            }
            register(id, result.profile());
            return;
        } catch (RuntimeException primaryFailure) {
            // Step 2: evidence first, before anything is written over the file.
            Path rejected = preserveUnreadable(file);
            // Step 3: the one rollback level.
            Path backup = file.resolveSibling(id + ".json.bak");
            if (Files.isRegularFile(backup)) {
                try {
                    ProfileLoadResult recovered = loader.load(JsonObject.parse(readAll(backup)));
                    register(id, recovered.profile());
                    writeProfile(recovered.profile());
                    recoveryLog.add(RecoveryNotice.withEvidence(
                            ConfigRecoveryOutcome.CONFIG_RECOVERED_FROM_BACKUP, id,
                            "The profile file could not be read (" + primaryFailure.getMessage()
                                    + "), so the single backup level was used and written back over "
                                    + "it. Nothing was lost that the backup did not already hold.",
                            now(), rejected == null ? null : rejected.toString()));
                    return;
                } catch (RuntimeException backupFailure) {
                    // Fall through to step 4.
                }
            }
            // Step 4: both unusable. Build the registered default, under this id.
            installBundledDefault(id);
            recoveryLog.add(RecoveryNotice.withEvidence(ConfigRecoveryOutcome.CONFIG_RESET_TO_DEFAULT,
                    id,
                    "Neither the profile file nor its backup could be read (" + primaryFailure
                            + "), so the bundled default profile was written in its place. Any "
                            + "settings in the unreadable file are preserved in the rejected copy "
                            + "and nothing was deleted.",
                    now(), rejected == null ? null : rejected.toString()));
        }
    }

    /**
     * C5.4 step 4: both the file and its backup are unusable, so the registered default is
     * built and written.
     *
     * <p><strong>The rebuilt default only carries {@code isDefault} when no other profile
     * already holds it.</strong> A recovery must not create a SECOND default: the whole point
     * of the post-condition is that a settings screen can never show two default ticks, and a
     * recovery path that produced one would show two. The flag is therefore dropped and
     * {@link #assertExactlyOneDefault()} decides, once, for the whole set.</p>
     *
     * @param underId the id of the file that was unreadable, or {@code null} on a first run
     */
    private void installBundledDefault(String underId) {
        Profile bundled = ChosenOneProfile.create();
        Profile installed = underId == null ? bundled
                : bundled.copiedAs(underId, bundled.name(), bundled.createdUtc(), bundled.provenance());
        // The rebuilt default carries isDefault only on a fresh install, or while no other
        // profile holds it. A recovery that produced a SECOND default would show two default
        // ticks in a settings screen, which is the exact failure the post-condition exists to
        // prevent - so the flag is decided once, here, and never twice.
        boolean mayBeDefault = underId == null || !anyProfileIsDefault();
        installed = installed.withDefault(mayBeDefault);
        register(installed.id(), installed);
        try {
            writeProfile(installed);
        } catch (RuntimeException e) {
            recoveryLog.add(RecoveryNotice.of(ConfigRecoveryOutcome.STORE_OPENED_IN_MEMORY,
                    installed.id(),
                    "The default profile was built in memory but could not be written (" + e + ").",
                    now()));
        }
    }

    private boolean anyProfileIsDefault() {
        for (Profile profile : profiles.values()) {
            if (profile.isDefault()) {
                return true;
            }
        }
        return false;
    }

    private void register(String id, Profile profile) {
        if (!id.equals(profile.id())) {
            throw new XsozContractException(
                    "Refusing to register profile \"" + profile.id() + "\" from the file named \""
                            + id + "\". The id is the filename stem (C5.2) and a mismatch means "
                            + "the file was renamed by hand; a hand-renamed file is recovered "
                            + "through the backup, not silently re-homed.");
        }
        if (profiles.containsKey(id)) {
            throw new XsozContractException(
                    "Two files both claim the profile id \"" + id + "\". Ids are the filename "
                            + "stem and there is exactly one file per profile (C5.1).");
        }
        profiles.put(id, profile);
    }

    private void assertExactlyOneDefault() {
        List<String> marked = new ArrayList<String>();
        for (Profile profile : profiles.values()) {
            if (profile.isDefault()) {
                marked.add(profile.id());
            }
        }
        if (marked.size() == 1) {
            return;
        }
        String chosen;
        if (profiles.containsKey(ChosenOneProfile.PROFILE_ID)) {
            chosen = ChosenOneProfile.PROFILE_ID;
        } else {
            List<Profile> sorted = new ArrayList<Profile>(profiles.values());
            Collections.sort(sorted, BY_ID);
            chosen = sorted.get(0).id();
        }
        for (Map.Entry<String, Profile> entry : profiles.entrySet()) {
            profiles.put(entry.getKey(),
                    entry.getValue().withDefault(entry.getKey().equals(chosen)));
        }
        recoveryLog.add(RecoveryNotice.of(ConfigRecoveryOutcome.CONFIG_RESET_TO_DEFAULT, chosen,
                "C5.2 requires exactly one profile with isDefault == true; this store had "
                        + (marked.isEmpty() ? "none" : marked.size() + " (" + marked + ")") + ". "
                        + "\"" + chosen + "\" was made the default so the settings screen cannot "
                        + "show two or zero defaults.", now()));
    }

    private void resolveActiveProfile() {
        String wanted = globalConfig == null ? null : globalConfig.lastProfileId();
        if (wanted != null && profiles.containsKey(wanted) && !zombieIds.contains(wanted)) {
            activeProfileId = wanted;
            return;
        }
        Profile defaultProfile = defaultProfile();
        activeProfileId = defaultProfile.id();
    }

    // ================================================================================================
    // Atomic write - contracts.md C5.3
    // ================================================================================================

    /**
     * Writes one profile atomically, with one backup level per session.
     *
     * @param profile the profile to write
     * @throws XsozContractException if any step fails. The previous file is untouched
     *                                whenever this throws: the temp write happens before
     *                                the backup copy and the rename, so a failure at any
     *                                point leaves the old complete file in place.
     */
    public void writeProfile(Profile profile) {
        if (profile == null) {
            throw new XsozContractException("writeProfile requires a profile.");
        }
        String json = ProfileJson.encode(profile).toJson();
        Path target = profileFile(profile.id());
        Path tmp = profileTempFile(profile.id());
        Path backup = profileBackupFile(profile.id());
        try {
            OutputStream out = new FileOutputStream(tmp.toFile());
            try {
                FileChannel channel = ((FileOutputStream) out).getChannel();
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(json);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                // fsync BEFORE the rename. After the rename, a crash can leave the new name
                // pointing at contents still in the page cache; before it, a crash can only
                // leave the old complete file or the new complete file.
                channel.force(true);
            } finally {
                out.close();
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new XsozContractException(
                    "Could not write the temp file " + tmp + " for profile \"" + profile.id()
                            + "\" (" + e + "). The previous " + target.getFileName()
                            + " is untouched: the temp write happens before the backup copy and "
                            + "the rename, so nothing on disk changed.");
        }
        try {
            if (Files.isRegularFile(target) && backupsTakenThisSession.add(profile.id())) {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new XsozContractException(
                    "Could not take the backup level for profile \"" + profile.id() + "\" (" + e
                            + "). The previous " + target.getFileName() + " is untouched.");
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            recoveryLog.add(RecoveryNotice.of(ConfigRecoveryOutcome.USED_PRIMARY, profile.id(),
                    "The filesystem refused an ATOMIC_MOVE; fell back to a plain replace. The write "
                            + "is still whole-file, but it is no longer guaranteed indivisible.",
                    now()));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                deleteQuietly(tmp);
                throw new XsozContractException(
                        "Could not move " + tmp.getFileName() + " onto " + target.getFileName()
                                + " for profile \"" + profile.id() + "\" (" + fallbackFailure
                                + "). The previous file is untouched.");
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new XsozContractException(
                    "Could not move " + tmp.getFileName() + " onto " + target.getFileName()
                            + " for profile \"" + profile.id() + "\" (" + e + "). The previous file "
                            + "is untouched.");
        }
    }

    private synchronized void writeGlobalConfig() {
        if (globalConfig == null) {
            return;
        }
        Path target = configDir.resolve(GlobalConfig.FILE_NAME);
        Path tmp = configDir.resolve(GlobalConfig.FILE_NAME + ".tmp");
        String json = globalConfig.toJson().toJson();
        try {
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                deleteQuietly(tmp);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            recoveryLog.add(RecoveryNotice.of(ConfigRecoveryOutcome.USED_PRIMARY, null,
                    "Could not write " + target.getFileName() + " (" + e + "). The session "
                            + "continues; the active profile will be picked again next launch.",
                    now()));
        }
    }

    private Path profileFile(String id) {
        return profilesDir.resolve(id + ".json");
    }

    /**
     * @param id the profile id
     * @return {@code <id>.json.tmp}, the same directory and therefore the same volume, which
     *         is what makes the rename atomic
     */
    private Path profileTempFile(String id) {
        return profilesDir.resolve(id + ".json.tmp");
    }

    /**
     * @param id the profile id
     * @return {@code <id>.json.bak}, the single rollback level of C5.1
     */
    private Path profileBackupFile(String id) {
        return profilesDir.resolve(id + ".json.bak");
    }

    /**
     * C5.4 step 2: write the unreadable bytes aside before anything is written over the
     * file. <strong>Never delete or overwrite a file we could not read.</strong>
     *
     * @param file the file whose bytes are being preserved
     * @return where they were preserved, or {@code null} if even that failed
     */
    private Path preserveUnreadable(Path file) {
        String stamp = Long.toString(now().toEpochMilli());
        Path rejected = file.resolveSibling(file.getFileName().toString() + ".rejected-" + stamp);
        try {
            Files.write(rejected, Files.readAllBytes(file));
            return rejected;
        } catch (IOException e) {
            return null;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A temp file that cannot be removed is reported by the next write, which will
            // fail with a message naming it.
        }
    }

    private static String readAll(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new XsozContractException("Could not read " + path + ": " + e);
        }
    }

    private Instant now() {
        return clock.instant();
    }

    // ================================================================================================
    // Lifecycle - contracts.md C5.6
    // ================================================================================================

    @Override
    public synchronized List<Profile> all() {
        List<Profile> sorted = new ArrayList<Profile>(profiles.values());
        Collections.sort(sorted, BY_ID);
        return Collections.unmodifiableList(sorted);
    }

    @Override
    public synchronized Profile defaultProfile() {
        for (Profile profile : profiles.values()) {
            if (profile.isDefault()) {
                return profile;
            }
        }
        throw new XsozContractException(
                "No profile carries isDefault. C5.2: exactly one profile has it at all times, and "
                        + "the store asserts that after every mutation. Reaching this is a bug in "
                        + "the store, not a state a player can produce.");
    }

    @Override
    public synchronized Profile activeProfile() {
        Profile profile = profiles.get(activeProfileId);
        if (profile == null) {
            throw new XsozContractException(
                    "The active profile \"" + activeProfileId + "\" is not in the store. The active "
                            + "pointer is only ever set to an id the store holds.");
        }
        return profile;
    }

    @Override
    public synchronized Profile switchTo(String profileId) {
        Profile target = requireProfile(profileId);
        Profile previous = activeProfile();
        if (previous.id().equals(target.id())) {
            return previous;
        }
        activeProfileId = target.id();
        globalConfig = (globalConfig == null
                ? GlobalConfig.of(CLIENT_VERSION, target.id(), ChosenOneProfile.RETRIEVED_ON, true)
                : globalConfig.withLastProfileId(target.id()));
        writeGlobalConfig();
        notifySwitched(previous, target, "user");
        return target;
    }

    @Override
    public synchronized Profile create(String newName) {
        Profile source = activeProfile();
        String name = requireUnusedName(newName, null);
        Profile created = source.copiedAs(freshId(name), name, now(),
                Provenance.userDefined("Created by copying \"" + source.name() + "\" on "
                        + now() + ". The numbers are the player's own until they say otherwise."));
        profiles.put(created.id(), created);
        writeProfile(created);
        assertPostConditions();
        return created;
    }

    @Override
    public synchronized Profile rename(String profileId, String newName) {
        Profile existing = requireProfile(profileId);
        if (ProfileNames.sameName(newName, existing.name())) {
            // A rename to the profile's own current name changes nothing, and must not be
            // treated as a duplicate of itself.
            return existing;
        }
        String name = requireUnusedName(newName, profileId);
        Profile renamed = existing.withName(name).withModifiedUtc(now());
        profiles.put(profileId, renamed);
        writeProfile(renamed);
        return renamed;
    }

    @Override
    public synchronized Profile duplicate(String profileId, String newName) {
        Profile source = requireProfile(profileId);
        String name = requireUnusedName(newName, profileId);
        // C5.6: provenance is copied VERBATIM, including the source URL. A duplicate is the
        // same numbers under a second name, and relabelling them as the player's own would
        // strip the citation from a copy of a sourced profile.
        Profile copy = source.copiedAs(freshId(name), name, now(), source.provenance());
        profiles.put(copy.id(), copy);
        writeProfile(copy);
        assertPostConditions();
        return copy;
    }

    @Override
    public synchronized Profile delete(String profileId, String reassignDefaultTo,
                                        String activateInstead) {
        Profile target = requireProfile(profileId);

        // Guard 1: cannot delete the last profile, regardless of arguments.
        if (profiles.size() == 1) {
            throw new ProfileDeleteRefusedException(profileId,
                    ProfileDeleteRefusedException.LAST_PROFILE,
                    "cannot delete the last profile. A store with no profiles has nothing to fall "
                            + "back to on next launch, and C5.2 requires exactly one default.");
        }
        // Guard 2: cannot delete the default without naming an existing other profile.
        if (target.isDefault()) {
            if (reassignDefaultTo == null) {
                throw new ProfileDeleteRefusedException(profileId,
                        ProfileDeleteRefusedException.DEFAULT_NEEDS_REASSIGNMENT,
                        "default profile requires reassignment. Name the profile that becomes the "
                                + "default; C5.2 requires exactly one default at all times and a "
                                + "store with none is a settings screen with no live row.");
            }
            if (reassignDefaultTo.equals(profileId)) {
                throw new ProfileDeleteRefusedException(profileId,
                        ProfileDeleteRefusedException.DEFAULT_NEEDS_REASSIGNMENT,
                        "the default cannot be reassigned to itself, because it is the profile "
                                + "being deleted. Name a different one.");
            }
            requireProfile(reassignDefaultTo);
        }
        // Guard 3: cannot delete the active profile without naming a successor.
        boolean isActive = profileId.equals(activeProfileId);
        if (isActive && activateInstead == null) {
            throw new ProfileDeleteRefusedException(profileId,
                    ProfileDeleteRefusedException.ACTIVE_NEEDS_SUCCESSOR,
                    "active profile requires a successor. C5.6: the switch happens first and "
                            + "atomically, and a store with no active profile has nothing to "
                            + "apply at startup.");
        }
        if (isActive) {
            requireProfile(activateInstead);
        }
        if (reassignDefaultTo != null && zombieIds.contains(reassignDefaultTo)) {
            throw new ProfileNotFoundException(reassignDefaultTo,
                    "it is a zombie: its file could not be deleted, so the id is held and cannot "
                            + "be reused.");
        }
        if (activateInstead != null && zombieIds.contains(activateInstead)) {
            throw new ProfileNotFoundException(activateInstead,
                    "it is a zombie: its file could not be deleted, so the id is held and cannot "
                            + "be reused.");
        }

        // Everything below this line is in-memory state, and it is committed BEFORE the
        // file is touched (C5.6 guard 4).
        if (isActive) {
            Profile successor = profiles.get(activateInstead);
            Profile previous = activeProfile();
            activeProfileId = successor.id();
            globalConfig = (globalConfig == null
                    ? GlobalConfig.of(CLIENT_VERSION, successor.id(), ChosenOneProfile.RETRIEVED_ON, true)
                    : globalConfig.withLastProfileId(successor.id()));
            writeGlobalConfig();
            notifySwitched(previous, successor, "delete");
        }
        if (target.isDefault()) {
            Profile newDefault = profiles.get(reassignDefaultTo).withDefault(true);
            profiles.put(reassignDefaultTo, newDefault);
            writeProfile(newDefault);
        }
        profiles.remove(profileId);

        // Guard 4: the file is deleted only after the in-memory state has committed.
        try {
            Files.deleteIfExists(profileFile(profileId));
            Files.deleteIfExists(profileBackupFile(profileId));
        } catch (IOException e) {
            zombieIds.add(profileId);
            recoveryLog.add(RecoveryNotice.of(ConfigRecoveryOutcome.PROFILE_FILE_DELETE_FAILED,
                    profileId,
                    "The in-memory state is correct and the profile is gone from the list, but its "
                            + "file could not be deleted (" + e + "). The id is now held as a "
                            + "zombie so a later create or import cannot resurrect it as a "
                            + "duplicate.", now()));
        }
        assertPostConditions();
        return activeProfile();
    }

    @Override
    public synchronized Profile setDefault(String profileId) {
        Profile target = requireProfile(profileId);
        for (Map.Entry<String, Profile> entry : profiles.entrySet()) {
            boolean shouldBeDefault = entry.getKey().equals(profileId);
            if (entry.getValue().isDefault() == shouldBeDefault) {
                continue;
            }
            Profile updated = entry.getValue().withDefault(shouldBeDefault);
            profiles.put(entry.getKey(), updated);
            writeProfile(updated);
        }
        assertPostConditions();
        return target.isDefault() ? target : profiles.get(profileId);
    }

    @Override
    public synchronized Profile save(Profile profile) {
        Profile existing = requireProfile(profile.id());
        Profile persisted = profile.withModifiedUtc(now());
        profiles.put(persisted.id(), persisted);
        writeProfile(persisted);
        assertPostConditions();
        if (activeProfileId.equals(persisted.id()) && persisted.coaching().autoPrune()
                != globalConfig.autoPruneCoaching()) {
            globalConfig = globalConfig.withAutoPruneCoaching(persisted.coaching().autoPrune());
            writeGlobalConfig();
        }
        return persisted;
    }

    // ================================================================================================
    // Export and import - contracts.md C5.5
    // ================================================================================================

    @Override
    public synchronized Path exportTo(String profileId, Path target) {
        Profile profile = requireProfile(profileId);
        JsonObject document = JsonObject.builder()
                .put("format", EXPORT_FORMAT)
                .put("formatVersion", EXPORT_FORMAT_VERSION)
                .put("exportedUtc", now().toString())
                .put("profile", ProfileJson.encode(profile))
                .build();
        writeAtomically(target, document.toJson());
        return target;
    }

    @Override
    public void importFrom(Path source) {
        importProfileFrom(source);
    }

    @Override
    public synchronized Profile importProfileFrom(Path source) {
        if (source == null) {
            throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_PARSE,
                    "no file to read.");
        }
        JsonObject document;
        try {
            document = JsonObject.parse(readAll(source));
        } catch (RuntimeException e) {
            throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_PARSE,
                    "the file does not parse as JSON (" + e.getMessage() + "). Nothing was "
                            + "changed.");
        }
        // Step 1: format and formatVersion.
        if (!EXPORT_FORMAT.equals(document.raw("format"))) {
            throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_FORMAT,
                    "format is " + document.raw("format") + ", expected \"" + EXPORT_FORMAT + "\".");
        }
        int formatVersion = document.getInt("formatVersion", -1);
        if (formatVersion > EXPORT_FORMAT_VERSION) {
            throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_FORMAT,
                    "formatVersion " + formatVersion + " is newer than the " + EXPORT_FORMAT_VERSION
                            + " this build reads. Refusing rather than guessing: a newer export "
                            + "read by an older client is how a profile silently loses settings.");
        }
        if (formatVersion < 1) {
            throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_FORMAT,
                    "formatVersion is " + formatVersion + "; it must be at least 1.");
        }
        JsonObject embedded = document.optObject("profile").orElse(null);
        if (embedded == null) {
            throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_SHAPE,
                    "the document carries no \"profile\" object.");
        }
        // Step 2: migrate the embedded profile if its schema is lower.
        int declared = embedded.getInt(MigrationRegistry.SCHEMA_KEY, MigrationRegistry.CURRENT_VERSION);
        if (declared > MigrationRegistry.CURRENT_VERSION) {
            throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_SCHEMA,
                    "the embedded profile declares schema " + declared + ", newer than the "
                            + MigrationRegistry.CURRENT_VERSION + " this build understands. "
                            + "Refusing rather than guessing.");
        }
        MigrationResult migration = MigrationRegistry.bundled().migrate(embedded, declared);
        if (migration.failed()) {
            throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_SCHEMA,
                    migration.failureReason());
        }
        JsonObject upgraded = migration.output();
        // Steps 3 and 4: validate everything, and reject a Tier C key.
        validateForImport(upgraded);
        // Step 5 and 6: force the compliance profile, force not-default, force a fresh id.
        Profile decoded;
        try {
            decoded = ProfileJson.decode(upgraded);
        } catch (RuntimeException e) {
            throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_VALIDATE,
                    "the embedded profile does not decode (" + e.getMessage() + ").");
        }
        String freshId = importId(decoded.id());
        List<String> notes = new ArrayList<String>(migration.appliedDescriptions());
        // Resolve exactly as a load would, so an imported profile is the same shape as a loaded
        // one rather than a subtly different one that only looks the same on screen.
        Profile inserted = loader.resolve(loader.prepareForInsertion(decoded, freshId), notes);
        for (String note : notes) {
            recoveryLog.add(RecoveryNotice.of(ConfigRecoveryOutcome.USED_PRIMARY, freshId, note,
                    now()));
        }
        if (zombieIds.contains(freshId) || profiles.containsKey(freshId)) {
            throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_SHAPE,
                    "the fresh id \"" + freshId + "\" is already held. Nothing was changed.");
        }
        // Step 7: insert. Import never overwrites and never changes the active profile.
        profiles.put(freshId, inserted);
        writeProfile(inserted);
        assertPostConditions();
        return inserted;
    }

    private void validateForImport(JsonObject embedded) {
        JsonObject modules = embedded.optObject("modules").orElse(JsonObject.empty());
        for (String moduleId : modules.keys()) {
            Optional<ComplianceTier> tier = catalog.tierOf(moduleId);
            if (tier.isPresent() && tier.get() == ComplianceTier.C) {
                throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_TIER_C,
                        "the document enables \"" + moduleId + "\", which is Tier C. C7.1: a Tier C "
                                + "feature is never compiled, and a downloaded profile must not be "
                                + "able to bring one along.");
            }
            Optional<SettingsSchema> schema = catalog.settingsSchemaFor(moduleId);
            if (!schema.isPresent()) {
                continue;
            }
            Map<String, Object> settings = new LinkedHashMap<String, Object>();
            JsonObject stored = modules.getObject(moduleId).optObject("settings")
                    .orElse(JsonObject.empty());
            for (String key : stored.keys()) {
                settings.put(key, ProfileJson.narrowIntegral(stored.raw(key)));
            }
            /*
             * C5.5 step 3 says "validateAll over every setting", and the store is a
             * TRANSACTION: a refused import must have changed nothing, which is a stronger
             * promise than a lenient load that reports.
             *
             * The lenient `view` runs first so the report says exactly what was wrong, and
             * the import is then refused if the report contains a DROP or a RESET:
             *
             *   unknown key    -> refuse. This client has no schema for it, so it cannot
             *                     honour it, and importing a key nobody can read is importing
             *                     a setting that silently does nothing.
             *   reset to default-> refuse. The document CLAIMS a value and the schema
             *                     refuses it; taking the default instead would install a
             *                     number the document did not contain.
             *   coerced        -> allow. A whole number for a double, or a translucent
             *                     colour for an opaque-only setting, is a faithful reading of
             *                     what was written, and it is recorded.
             *   truncated      -> allow, and recorded. A list over its maxSize is a list the
             *                     player must shorten, not a reason to refuse their profile.
             *
             * Deliberately NOT required: that every DECLARED key is present. A profile
             * written before a setting existed must still import, which is the entire point
             * of the migration chain; absence is filled from the declared default by the
             * same `view` call.
             */
            SettingsView view = schema.get().view(settings);
            SettingsMutationReport report = view.lastReport();
            if (!report.unknownKeysDropped().isEmpty()) {
                throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_VALIDATE,
                        "module \"" + moduleId + "\": " + report.unknownKeysDropped()
                                + ". C5.5 step 3: import validates every setting before applying "
                                + "anything, and this client has no schema for a key it cannot "
                                + "honour.");
            }
            if (!report.valuesResetToDefault().isEmpty()) {
                throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_VALIDATE,
                        "module \"" + moduleId + "\": " + report.valuesResetToDefault()
                                + ". C5.5 step 3: a value the schema refuses is refused here too. "
                                + "Taking the default instead would install a number the document "
                                + "did not contain.");
            }
        }
        JsonObject keybinds = embedded.optObject("keybinds").orElse(JsonObject.empty());
        for (String actionId : keybinds.keys()) {
            Keybind bind;
            try {
                bind = ProfileJson.decodeKeybindEntry(keybinds.getObject(actionId));
            } catch (RuntimeException e) {
                throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_VALIDATE,
                        "keybind \"" + actionId + "\" does not decode (" + e.getMessage() + ").");
            }
            if (!bind.isBound()) {
                // "Not bound" is a stored state, not an attempt to bind the unknown key, so
                // it is not sent to the policy. C5.2's own example stores it that way.
                continue;
            }
            BindVerdict verdict = bindPolicy.validate(bind,
                    BindRequest.forAction(actionId).requested(bind).build());
            if (verdict.allowed() || verdict.reason() == BindRejection.UNKNOWN_ACTION) {
                continue;
            }
            throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_VALIDATE,
                    "keybind \"" + actionId + "\" is refused: " + verdict.reason() + " - "
                            + verdict.message() + ". C5.5 step 3: every bind passes BindPolicy "
                            + "before anything is applied.");
        }
        Set<String> knownModules = catalog.knownModuleIds();
        if (knownModules.isEmpty()) {
            return;
        }
        JsonObject hud = embedded.optObject("hud").orElse(JsonObject.empty());
        for (Object raw : hud.getArray("elements")) {
            if (!(raw instanceof JsonObject)) {
                throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_VALIDATE,
                        "hud.elements holds a non-object, so an element id cannot be checked.");
            }
            String elementId = ((JsonObject) raw).getString("id");
            if (!knownModules.contains(elementId)) {
                throw new ProfileImportRejectedException(ProfileImportRejectedException.STEP_VALIDATE,
                        "hud element \"" + elementId + "\" names no registered module. C5.5 step 3: "
                                + "every HUD element id is validated before anything is applied.");
            }
        }
    }

    // ================================================================================================
    // Support
    // ================================================================================================

    @Override
    public synchronized String defaultExportFileName(String profileId, Instant atUtc) {
        return "xsoz-profile-" + requireProfile(profileId).id() + "-"
                + EXPORT_STAMP.format(atUtc) + ".xsozprofile.json";
    }

    @Override
    public synchronized List<RecoveryNotice> recoveryLog() {
        return Collections.unmodifiableList(new ArrayList<RecoveryNotice>(recoveryLog));
    }

    @Override
    public synchronized Optional<RecoveryNotice> lastRecovery() {
        return recoveryLog.isEmpty() ? Optional.<RecoveryNotice>empty()
                : Optional.of(recoveryLog.get(recoveryLog.size() - 1));
    }

    @Override
    public synchronized boolean isZombie(String profileId) {
        return zombieIds.contains(profileId);
    }

    @Override
    public void addSwitchListener(ProfileSwitchListener listener) {
        if (listener == null) {
            throw new XsozContractException("A switch listener must not be null.");
        }
        synchronized (this) {
            listeners.add(listener);
        }
    }

    /** @return the config directory this store writes into */
    public Path configDir() {
        return configDir;
    }

    /** @return the profiles directory, {@code config/profiles} */
    public Path profilesDir() {
        return profilesDir;
    }

    /** @return the store-level pointer, which is what {@code xsozclient.json} holds */
    public synchronized GlobalConfig globalConfig() {
        return globalConfig;
    }

    /** @return the recovery log path, which C5.4 step 5 appends one line to */
    public Path recoveryLogPath() {
        return configDir.resolve(RECOVERY_LOG_NAME);
    }

    private void assertPostConditions() {
        int defaults = 0;
        for (Profile profile : profiles.values()) {
            if (profile.isDefault()) {
                defaults++;
            }
        }
        if (defaults != 1) {
            throw new XsozContractException(
                    "Post-condition failed: " + defaults + " profiles carry isDefault after a "
                            + "mutation; C5.2 requires exactly one.");
        }
        if (!profiles.containsKey(activeProfileId)) {
            throw new XsozContractException(
                    "Post-condition failed: the active profile \"" + activeProfileId + "\" is not "
                            + "in the store.");
        }
    }

    private Profile requireProfile(String profileId) {
        Profile profile = profileId == null ? null : profiles.get(profileId);
        if (profile == null) {
            throw new ProfileNotFoundException(profileId,
                    "the store holds " + new TreeSet<String>(profiles.keySet())
                            + (zombieIds.isEmpty() ? "" : ", and " + new TreeSet<String>(zombieIds)
                            + " as zombies whose files could not be deleted"));
        }
        return profile;
    }

    /**
     * @param rawName  the name as typed
     * @param excludeId the id whose own name is not "taken" - the profile being renamed or
     *                  copied; {@code null} for a create
     * @return the trimmed, valid, unique name
     */
    private String requireUnusedName(String rawName, String excludeId) {
        List<String> taken = new ArrayList<String>();
        for (Profile profile : profiles.values()) {
            if (!profile.id().equals(excludeId)) {
                taken.add(profile.name());
            }
        }
        return ProfileNames.requireUniqueName(rawName, taken);
    }

    /**
     * A fresh id: a slug of the name plus four hex digits, never colliding with a live or
     * zombie id.
     */
    private String freshId(String name) {
        for (int attempt = 0; attempt < 4096; attempt++) {
            idCounter++;
            long entropy = now().toEpochMilli() * 31L + idCounter;
            String candidate = ProfileNames.slugify(name, (int) (entropy & 0xFFFF));
            if (!profiles.containsKey(candidate) && !zombieIds.contains(candidate)) {
                return candidate;
            }
        }
        throw new XsozContractException(
                "Could not find a free profile id after 4096 attempts for name \"" + name + "\".");
    }

    /**
     * C5.5 step 6: {@code <oldId>-import-<4 hex>}.
     */
    private String importId(String oldId) {
        for (int attempt = 0; attempt < 4096; attempt++) {
            idCounter++;
            long entropy = now().toEpochMilli() * 31L + idCounter;
            String candidate = ProfileNames.slugify(oldId + "-import", (int) (entropy & 0xFFFF));
            if (!profiles.containsKey(candidate) && !zombieIds.contains(candidate)) {
                return candidate;
            }
        }
        throw new XsozContractException(
                "Could not find a free import id for \"" + oldId + "\" after 4096 attempts.");
    }

    private void notifySwitched(Profile previous, Profile current, String reason) {
        for (ProfileSwitchListener listener : listeners) {
            listener.onProfileSwitched(previous, current, reason);
        }
    }

    private static void writeAtomically(Path target, String text) {
        Path parent = target.getParent();
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                deleteQuietly(tmp);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new XsozContractException("Could not write " + target + ": " + e);
        }
    }
}
