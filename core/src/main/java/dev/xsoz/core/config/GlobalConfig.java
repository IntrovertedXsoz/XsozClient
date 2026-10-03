package dev.xsoz.core.config;

import dev.xsoz.core.XsozContractException;
import dev.xsoz.core.setting.JsonObject;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * {@code config/xsozclient.json}: the store-level state that is not per-profile
 * (contracts.md C5.1).
 *
 * <p>Four fields and no more: the schema version, the client version that wrote the file,
 * which profile was active, the date the bundled rules snapshot was taken, and whether
 * old coaching recordings are pruned. Everything a player configures lives in a profile;
 * anything that would make a profile mean different things on two machines does not.</p>
 *
 * <p>Deeply immutable; every {@code with} returns a copy.</p>
 */
public final class GlobalConfig {

    /** The filename, relative to {@code configDir}. */
    public static final String FILE_NAME = "xsozclient.json";

    private final String clientVersion;
    private final String lastProfileId;
    private final LocalDate rulesSnapshotDate;
    private final boolean autoPruneCoaching;

    private GlobalConfig(String clientVersion, String lastProfileId, LocalDate rulesSnapshotDate,
                         boolean autoPruneCoaching) {
        this.clientVersion = clientVersion;
        this.lastProfileId = lastProfileId;
        this.rulesSnapshotDate = rulesSnapshotDate;
        this.autoPruneCoaching = autoPruneCoaching;
    }

    /**
     * @param clientVersion       the client version that wrote the file
     * @param lastProfileId       the profile that was active, or {@code null} on a first run
     * @param rulesSnapshotDate  the date the bundled rules were read, or {@code null}
     * @param autoPruneCoaching   whether old coaching recordings are pruned
     * @return the global config
     */
    public static GlobalConfig of(String clientVersion, String lastProfileId,
                                  LocalDate rulesSnapshotDate, boolean autoPruneCoaching) {
        if (clientVersion == null || clientVersion.trim().isEmpty()) {
            throw new XsozContractException(
                    "xsozclient.json needs the clientVersion that wrote it. A file with no "
                            + "version cannot be diagnosed after a regression.");
        }
        if (lastProfileId != null && !ProfileNames.isValidId(lastProfileId)) {
            throw new XsozContractException(
                    "lastProfileId \"" + lastProfileId + "\" is not a legal profile id; grammar: "
                            + ProfileNames.ID_GRAMMAR);
        }
        return new GlobalConfig(clientVersion.trim(), lastProfileId, rulesSnapshotDate,
                autoPruneCoaching);
    }

    /** @return the client version that wrote the file */
    public String clientVersion() {
        return clientVersion;
    }

    /** @return the profile that was active, or {@code null} on a first run */
    public String lastProfileId() {
        return lastProfileId;
    }

    /** @return the date the bundled rules were read, or {@code null} */
    public LocalDate rulesSnapshotDate() {
        return rulesSnapshotDate;
    }

    /** @return whether old coaching recordings are pruned */
    public boolean autoPruneCoaching() {
        return autoPruneCoaching;
    }

    /**
     * @param newLastProfileId the newly active profile id
     * @return a copy pointing at that profile
     */
    public GlobalConfig withLastProfileId(String newLastProfileId) {
        return of(clientVersion, newLastProfileId, rulesSnapshotDate, autoPruneCoaching);
    }

    /**
     * @param newAutoPrune the new auto-prune flag
     * @return a copy with the flag changed
     */
    public GlobalConfig withAutoPruneCoaching(boolean newAutoPrune) {
        return of(clientVersion, lastProfileId, rulesSnapshotDate, newAutoPrune);
    }

    /**
     * @return the document, in C5.1's field order
     */
    public JsonObject toJson() {
        JsonObject.Builder builder = JsonObject.builder();
        builder.put(MigrationRegistry.SCHEMA_KEY, MigrationRegistry.CURRENT_VERSION);
        builder.put("clientVersion", clientVersion);
        if (lastProfileId == null) {
            builder.put("lastProfileId", null);
        } else {
            builder.put("lastProfileId", lastProfileId);
        }
        if (rulesSnapshotDate == null) {
            builder.put("rulesSnapshotDate", null);
        } else {
            builder.put("rulesSnapshotDate", rulesSnapshotDate.toString());
        }
        builder.put("autoPruneCoaching", autoPruneCoaching);
        return builder.build();
    }

    /**
     * @param document the document as read
     * @return the decoded global config
     * @throws XsozContractException if a required field is absent or malformed
     */
    public static GlobalConfig fromJson(JsonObject document) {
        if (document == null) {
            throw new XsozContractException("xsozclient.json is empty; there is nothing to read.");
        }
        int declared = document.getInt(MigrationRegistry.SCHEMA_KEY, MigrationRegistry.CURRENT_VERSION);
        if (declared > MigrationRegistry.CURRENT_VERSION) {
            throw new XsozContractException(
                    "xsozclient.json declares schema " + declared + ", newer than the "
                            + MigrationRegistry.CURRENT_VERSION + " this build understands. "
                            + "Refusing rather than guessing.");
        }
        LocalDate snapshot;
        if (document.has("rulesSnapshotDate") && document.raw("rulesSnapshotDate") != null) {
            try {
                snapshot = LocalDate.parse(document.getString("rulesSnapshotDate"));
            } catch (DateTimeParseException e) {
                throw new XsozContractException(
                        "xsozclient.json rulesSnapshotDate is \""
                                + document.raw("rulesSnapshotDate")
                                + "\", which is not an ISO-8601 date.");
            }
        } else {
            snapshot = null;
        }
        String lastProfileId = null;
        if (document.has("lastProfileId") && document.raw("lastProfileId") != null) {
            lastProfileId = document.getString("lastProfileId");
        }
        return of(document.getString("clientVersion", "unknown"), lastProfileId, snapshot,
                document.getBoolean("autoPruneCoaching", true));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GlobalConfig)) {
            return false;
        }
        GlobalConfig that = (GlobalConfig) other;
        return autoPruneCoaching == that.autoPruneCoaching
                && clientVersion.equals(that.clientVersion)
                && Objects.equals(lastProfileId, that.lastProfileId)
                && Objects.equals(rulesSnapshotDate, that.rulesSnapshotDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientVersion, lastProfileId, rulesSnapshotDate,
                Boolean.valueOf(autoPruneCoaching));
    }

    @Override
    public String toString() {
        return "GlobalConfig[clientVersion=" + clientVersion + ", lastProfileId=" + lastProfileId
                + ", rulesSnapshotDate=" + rulesSnapshotDate + ", autoPruneCoaching="
                + autoPruneCoaching + "]";
    }
}
