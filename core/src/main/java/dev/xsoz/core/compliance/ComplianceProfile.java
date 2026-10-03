package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A per-network or fallback verdict table, carrying its evidence and its date
 * (contracts.md C7.4).
 *
 * <p><strong>Default-deny is the posture, without exception.</strong> Every bundled profile
 * has {@code defaultDeny == true}, and a module id with no row in the table is
 * <em>not allowed</em> rather than allowed by omission. A feature that nobody researched is
 * excluded, not shipped.</p>
 *
 * <p><strong>The allow list is empty on install and add-only.</strong> It holds
 * {@code (host, featureId)} grants in the form of the user-editable feature-id set; an entry
 * may be added, never removed through the UI, and removal requires editing the file by hand,
 * which is the user's prerogative (contracts.md C7.4).</p>
 *
 * <p>Deeply immutable. Every collection is copied on the way in and wrapped on the way out.
 * The import/export pair is a strict round trip: {@code decode(encode(p)).equals(p)}.</p>
 */
public final class ComplianceProfile {

    /** The id of the profile an unmatched host resolves to (contracts.md C7.4). */
    public static final String DEFAULT_DENY_ID = "default-deny";

    private final String id;
    private final String name;
    private final Set<String> rulesets;
    private final boolean defaultDeny;
    private final Map<String, ComplianceRow> verdicts;
    private final Set<String> allowList;
    private final LocalDate retrievedOn;

    /**
     * @param id          the profile id, e.g. {@code "pvphq"}; non-blank
     * @param name        the human name, e.g. {@code "PvPHQ"}; non-blank
     * @param rulesets    the ruleset ids this table was built from; must be non-empty
     * @param defaultDeny the posture; every bundled profile passes {@code true}
     * @param verdicts    featureId to row; defensively copied, values must be non-null
     * @param allowList   the user-granted feature-id allow list; <strong>empty on install</strong>
     * @param retrievedOn the OLDEST retrieval date among this profile's citations
     * @throws XsozContractException   if any argument is absent or empty
     * @throws MissingCitationException if a supplied date is not backed by any citation
     */
    public ComplianceProfile(String id,
                             String name,
                             Set<String> rulesets,
                             boolean defaultDeny,
                             Map<String, ComplianceRow> verdicts,
                             Set<String> allowList,
                             LocalDate retrievedOn) {
        if (id == null || id.trim().isEmpty()) {
            throw new XsozContractException("ComplianceProfile.id must not be blank (contracts.md C7.4).");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new XsozContractException("ComplianceProfile.name must not be blank (contracts.md C7.4).");
        }
        if (rulesets == null || rulesets.isEmpty()) {
            throw new XsozContractException(
                    "ComplianceProfile.rulesets must name at least one ruleset. A profile with no "
                            + "sources is a preference, not a finding. id=" + id);
        }
        if (retrievedOn == null) {
            throw new MissingCitationException(
                    "ComplianceProfile.retrievedOn must not be null. It is the oldest retrieval date "
                            + "among the profile's citations, and a profile with no date cannot answer "
                            + "the question a player actually asks. id=" + id);
        }
        this.id = id;
        this.name = name;
        this.rulesets = unmodifiableSet(rulesets, "rulesets");
        this.defaultDeny = defaultDeny;
        Map<String, ComplianceRow> copiedVerdicts = new LinkedHashMap<String, ComplianceRow>();
        if (verdicts != null) {
            for (Map.Entry<String, ComplianceRow> entry : verdicts.entrySet()) {
                if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                    throw new XsozContractException(
                            "ComplianceProfile.verdicts holds a null or blank feature id. A row with "
                                    + "no id addresses nothing. id=" + id);
                }
                if (entry.getValue() == null) {
                    throw new XsozContractException(
                            "ComplianceProfile.verdicts[" + entry.getKey() + "] is null in profile "
                                    + id + ". An absent row already means not-allowed, so a null row "
                                    + "is a second, different way of saying the same thing.");
                }
                copiedVerdicts.put(entry.getKey(), entry.getValue());
            }
        }
        this.verdicts = Collections.unmodifiableMap(copiedVerdicts);
        this.allowList = unmodifiableSet(
                allowList == null ? Collections.<String>emptySet() : allowList, "allowList");
        this.retrievedOn = retrievedOn;
        verifyTierCIsUnreachable(this.verdicts, id);
    }

    /** @return the profile id */
    public String id() {
        return id;
    }

    /** @return the human name */
    public String name() {
        return name;
    }

    /** @return the ruleset ids this table was built from */
    public Set<String> rulesets() {
        return rulesets;
    }

    /** @return {@code true} for every bundled profile, without exception */
    public boolean defaultDeny() {
        return defaultDeny;
    }

    /**
     * @return the immutable verdict table, featureId to row. An <strong>absent</strong> key
     *         means "not allowed", never "allowed by omission".
     */
    public Map<String, ComplianceRow> verdicts() {
        return verdicts;
    }

    /**
     * @return the row for a feature, or {@code null} when the profile has no opinion - which
     *         the engine reads as a refusal, not as an absence of refusal
     */
    public ComplianceRow rowFor(String featureId) {
        return featureId == null ? null : verdicts.get(featureId);
    }

    /**
     * The user-editable allow list of {@code (host, featureId)} grants, held here as the
     * resolved feature-id set for this profile. <strong>Empty on install and add-only.</strong>
     *
     * @return the granted feature ids
     */
    public Set<String> allowList() {
        return allowList;
    }

    /** @return the OLDEST retrieval date among this profile's citations */
    public LocalDate retrievedOn() {
        return retrievedOn;
    }

    /**
     * Staleness is surfaced, never silently upgraded (contracts.md C7.4).
     *
     * @param clientReleaseDate the date of the running client build
     * @return {@code true} when the profile's evidence predates the running build, so the
     *         build may have changed what the server permits
     */
    public boolean isStale(LocalDate clientReleaseDate) {
        return Staleness.of(this, clientReleaseDate) == Staleness.STALE;
    }

    /**
     * Adds one grant to the allow list. This is the <em>only</em> mutating operation on a
     * profile and it can only add; removing a grant means editing the file by hand, which is
     * the user's prerogative (contracts.md C7.4).
     *
     * @param featureId the feature to grant
     * @return a new profile; this instance is unchanged
     * @throws XsozContractException if {@code featureId} is blank or is a known Tier C id
     */
    public ComplianceProfile withAllowGrant(String featureId) {
        if (featureId == null || featureId.trim().isEmpty()) {
            throw new XsozContractException("An allow-list grant needs a feature id.");
        }
        if (GlobalProhibition.byId(featureId) != null) {
            throw new XsozContractException(
                    "A standing prohibition cannot be allow-listed: " + featureId
                            + ". contracts.md C7.4: prohibitedAlways in every profile, and no profile "
                            + "can unlock these.");
        }
        Set<String> grants = new LinkedHashSet<String>(allowList);
        grants.add(featureId);
        return new ComplianceProfile(id, name, rulesets, defaultDeny, verdicts, grants, retrievedOn);
    }

    /**
     * Serialises to the profile JSON of {@link ComplianceProfileJson#FORMAT}.
     *
     * @return the canonical JSON text, keys in a fixed order
     */
    public String toJson() {
        return ComplianceProfileJson.encode(this);
    }

    /**
     * Parses a profile from JSON. Every citation is rebuilt through the
     * {@link Citation} constructor, so the 15-word limit, the {@code https} requirement and
     * the retrieval date are re-validated on import rather than trusted.
     *
     * @param json the profile text
     * @return the decoded profile
     * @throws XsozContractException if the document is not a well-formed profile
     */
    public static ComplianceProfile fromJson(String json) {
        return ComplianceProfileJson.decode(json);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ComplianceProfile)) {
            return false;
        }
        ComplianceProfile that = (ComplianceProfile) other;
        return defaultDeny == that.defaultDeny
                && id.equals(that.id)
                && name.equals(that.name)
                && rulesets.equals(that.rulesets)
                && verdicts.equals(that.verdicts)
                && allowList.equals(that.allowList)
                && retrievedOn.equals(that.retrievedOn);
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + name.hashCode();
        result = 31 * result + rulesets.hashCode();
        result = 31 * result + (defaultDeny ? 1 : 0);
        result = 31 * result + verdicts.hashCode();
        result = 31 * result + allowList.hashCode();
        result = 31 * result + retrievedOn.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "ComplianceProfile[" + id + " \"" + name + "\" defaultDeny=" + defaultDeny
                + " rulesets=" + rulesets
                + " rows=" + verdicts.size()
                + " allowList=" + allowList
                + " snapshot=" + retrievedOn + "]";
    }

    /**
     * The three-state freshness answer. {@code UNKNOWN} exists because a profile that
     * carries no date at all is not "current" either, and calling it current would be the
     * silent upgrade the contract forbids.
     */
    public enum Staleness {

        /** The evidence is dated on or after the running build. */
        CURRENT,

        /** The evidence predates the running build. Surfaced, never silently upgraded. */
        STALE,

        /** There is no date to compare, so freshness cannot be claimed. */
        UNKNOWN;

        /**
         * @param profile           the resolved profile
         * @param clientReleaseDate the running build's date, or {@code null} if unknown
         * @return the freshness state
         */
        public static Staleness of(ComplianceProfile profile, LocalDate clientReleaseDate) {
            if (profile == null || profile.retrievedOn == null || clientReleaseDate == null) {
                return UNKNOWN;
            }
            return profile.retrievedOn.isBefore(clientReleaseDate) ? STALE : CURRENT;
        }
    }

    private static Set<String> unmodifiableSet(Set<String> source, String fieldName) {
        Set<String> copy = new LinkedHashSet<String>();
        for (String value : source) {
            if (value == null || value.trim().isEmpty()) {
                throw new XsozContractException(
                        "ComplianceProfile." + fieldName + " holds a null or blank entry.");
            }
            copy.add(value);
        }
        return Collections.unmodifiableSet(copy);
    }

    /**
     * A profile may hold a Tier C row only to DENY it. This is the structural reason a
     * hand-edited profile is inert rather than permissive (contracts.md C7.4,
     * {@code ComplianceProfileCannotEnableTierCTest}).
     */
    private static void verifyTierCIsUnreachable(Map<String, ComplianceRow> rows, String profileId) {
        for (Map.Entry<String, ComplianceRow> entry : rows.entrySet()) {
            ComplianceRow row = entry.getValue();
            if (row.tier() == ComplianceTier.C
                    && row.verdict() != Verdict.DENIED
                    && row.prohibitionsAlways().isEmpty()) {
                throw new XsozContractException(
                        "Profile " + profileId + " row " + entry.getKey() + " is Tier C with verdict "
                                + row.verdict() + " and no standing prohibition. contracts.md C7.4: the "
                                + "profile schema cannot express a state that turns a Tier C feature on.");
            }
        }
    }
}
