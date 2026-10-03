package dev.xsoz.core.config;

import java.nio.file.Path;
import java.util.List;

/**
 * The profile lifecycle (contracts.md C5.6).
 *
 * <p><strong>Every method here is post-condition-checked.</strong> "Exactly one profile
 * carries {@code isDefault == true}" and "an id is never reused" are asserted after
 * every mutation, because a store that can hold two defaults has a settings screen that
 * shows two "default" ticks and no way for the player to tell which one is live.</p>
 *
 * <p><strong>{@code null} is used for exactly two arguments</strong> - the
 * {@code reassignDefaultTo} and {@code activateInstead} of {@link #delete} - where absence
 * is the semantic "you did not name a successor", and the corresponding guard must fire.
 * Every other absence is a return value.</p>
 *
 * <p>A switch never requires a restart: {@link #switchTo(String)} applies the new profile
 * and then notifies a {@link ProfileSwitchListener}, which is where the tick loop and the
 * enable gate are re-run (C5.6, C2.4 row 5).</p>
 */
public interface ProfileStore {

    /** @return every profile, sorted by {@link Profile#id()} ascending, byte order */
    List<Profile> all();

    /**
     * @return the profile with {@code isDefault == true}; never {@code null}, and
     *         asserted after every mutation
     */
    Profile defaultProfile();

    /** @return the profile currently applied; never {@code null} */
    Profile activeProfile();

    /**
     * Applies another profile. Never requires a restart.
     *
     * @param profileId the profile to apply
     * @return the newly active profile
     * @throws ProfileNotFoundException if no profile has that id
     */
    Profile switchTo(String profileId);

    /**
     * Clones the <strong>currently active</strong> profile under a new name.
     *
     * <p>C5.6's rationale, kept: a blank profile is a worse starting point than a copy of
     * what the player was just using. The copy gets a fresh id, {@code isDefault=false}
     * and {@code USER_DEFINED} provenance - it stops claiming the original's numbers came
     * from someone else's published settings.</p>
     *
     * @param name the new name; trimmed, validated and unique case-insensitively
     * @return the new profile
     * @throws ProfileNameInvalidException if the name is blank, over-long, carries a
     *                                     forbidden character, or is already taken
     */
    Profile create(String name);

    /**
     * Renames a profile. <strong>The id never changes</strong> (C5.2).
     *
     * @param profileId the profile to rename
     * @param newName   the new name; trimmed, validated and unique among the others
     * @return the renamed profile
     * @throws ProfileNotFoundException      if no profile has that id
     * @throws ProfileNameInvalidException    if the name is unusable or already taken
     */
    Profile rename(String profileId, String newName);

    /**
     * Deep-copies a profile under a new name, with a fresh id, {@code isDefault=false},
     * a new creation instant, and the provenance copied verbatim (C5.6).
     *
     * @param profileId the profile to copy
     * @param newName   the new name
     * @return the copy
     * @throws ProfileNotFoundException   if no profile has that id
     * @throws ProfileNameInvalidException if the name is unusable or already taken
     */
    Profile duplicate(String profileId, String newName);

    /**
     * Deletes a profile, subject to C5.6's four hard guards.
     *
     * @param profileId         the profile to delete
     * @param reassignDefaultTo the profile to make default, required when the target is the
     *                          default; may be {@code null}
     * @param activateInstead   the profile to activate, required when the target is the
     *                          active one; may be {@code null}
     * @return the profile that is active afterwards
     * @throws ProfileNotFoundException      if no profile has that id
     * @throws ProfileDeleteRefusedException if a guard fired; nothing is changed
     */
    Profile delete(String profileId, String reassignDefaultTo, String activateInstead);

    /**
     * Moves the default flag. Sets it on one profile and clears it on every other.
     *
     * @param profileId the new default
     * @return the new default profile
     * @throws ProfileNotFoundException if no profile has that id
     */
    Profile setDefault(String profileId);

    /**
     * Writes one export document.
     *
     * @param profileId the profile to export
     * @param target    the file to write, atomically
     * @return {@code target}
     * @throws ProfileNotFoundException if no profile has that id
     */
    Path exportTo(String profileId, Path target);

    /**
     * Imports one export document as a <strong>new</strong> profile. Never overwrites and
     * never changes which profile is active (C5.5 step 7).
     *
     * @param source the file to read
     * @throws ProfileImportRejectedException if any of C5.5's seven ordered checks fails.
     *                                        Nothing is changed when this is thrown.
     */
    void importFrom(Path source);

    /**
     * The value-returning form of {@link #importFrom(Path)}, for a caller that needs to
     * select the imported profile.
     *
     * @param source the file to read
     * @return the inserted profile
     * @throws ProfileImportRejectedException if any of C5.5's seven ordered checks fails
     */
    Profile importProfileFrom(Path source);

    /**
     * Writes a modified profile, and the store-level pointer, atomically.
     *
     * @param profile the profile to persist; its id must already exist in the store
     * @return the profile as persisted, with {@code modifiedUtc} advanced
     * @throws ProfileNotFoundException if no profile has that id
     */
    Profile save(Profile profile);

    /**
     * The default export filename C5.5 names:
     * {@code xsoz-profile-<profileId>-<yyyyMMdd-HHmmss>.xsozprofile.json}.
     *
     * @param profileId the profile being exported
     * @param atUtc     the export instant. Unit: ISO-8601 instant, UTC.
     * @return the filename
     */
    String defaultExportFileName(String profileId, java.time.Instant atUtc);

    /**
     * Everything the store had to recover from at open time, newest last.
     *
     * <p>Empty on a clean open. Non-empty means the mod-inventory screen must show a
     * recovery notice (C5.4 rule 5).</p>
     *
     * @return the notices, unmodifiable
     */
    List<RecoveryNotice> recoveryLog();

    /**
     * @return the most recent recovery notice, empty on a clean open
     */
    java.util.Optional<RecoveryNotice> lastRecovery();

    /**
     * A zombie id is one whose file could not be deleted. The in-memory state is correct
     * and the profile is not offered, but the id is held so it cannot be resurrected as a
     * duplicate by a later {@code create} or {@code import} (C5.6 guard 4).
     *
     * @param profileId the id to ask about
     * @return whether the id is a zombie
     */
    boolean isZombie(String profileId);

    /**
     * Registers a listener notified after a switch has been applied.
     *
     * @param listener the listener
     */
    void addSwitchListener(ProfileSwitchListener listener);
}
