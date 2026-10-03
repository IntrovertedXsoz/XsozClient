package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract C7.4 - {@code ComplianceProfile} and the bundled set.
 *
 * <p><strong>Default-deny is the posture without exception</strong>, and the seven
 * competitive networks ship DENIED with an EMPTY allow list.</p>
 */
class ComplianceProfileTest {

    private static final LocalDate ON = ComplianceCitations.SOURCES_RETRIEVED_ON;

    @Test
    @DisplayName("seven competitive networks are bound by default, each default-deny")
    void sevenNetworksAreBound() {
        ComplianceProfileSet bundled = ComplianceProfileSet.bundled();
        assertEquals(8, bundled.all().size(), "default-deny plus the seven studied networks");
        for (String networkId : Arrays.asList("pvphq", "pvp-land", "pvplegacy", "hypixel",
                "cubecraft", "mineplex", "mcc-island")) {
            ComplianceProfile profile = bundled.byId(networkId);
            assertTrue(profile.defaultDeny(), networkId + " must be default-deny without exception");
        }
        assertTrue(bundled.defaultDeny().defaultDeny());
    }

    @Test
    @DisplayName("every shipped profile ships an EMPTY allow list")
    void allowListIsEmptyOnInstall() {
        for (ComplianceProfile profile : ComplianceProfileSet.bundled().all()) {
            assertTrue(profile.allowList().isEmpty(),
                    profile.id() + " shipped a non-empty allow list: " + profile.allowList());
        }
    }

    @ParameterizedTest(name = "the Tier B optimising set is DENIED in the {0} profile")
    @ValueSource(strings = {"default-deny", "pvphq", "pvp-land", "pvplegacy", "hypixel",
            "cubecraft", "mineplex", "mcc-island"})
    void tierBIsDeniedEverywhere(String profileId) {
        ComplianceProfile profile = ComplianceProfileSet.bundled().byId(profileId);
        for (String featureId : Arrays.asList("latency.crystal-release", "latency.anchor-ghost")) {
            ComplianceRow row = profile.rowFor(featureId);
            assertNotNull(row, profileId + " has no row for " + featureId);
            assertEquals(Verdict.DENIED, row.verdict(), profileId + " / " + featureId);
            assertEquals(ComplianceTier.B, row.tier(), profileId + " / " + featureId);
        }
    }

    @Test
    @DisplayName("every row carries at least one dated citation, and the profile date is the oldest")
    void everyRowCarriesADatedCitation() {
        LocalDate oldest = null;
        for (ComplianceProfile profile : ComplianceProfileSet.bundled().all()) {
            for (Map.Entry<String, ComplianceRow> entry : profile.verdicts().entrySet()) {
                ComplianceRow row = entry.getValue();
                assertFalse(row.citations().isEmpty(),
                        profile.id() + "/" + entry.getKey() + " has no citation");
                for (Citation citation : row.citations()) {
                    assertNotNull(citation.retrievedOn(),
                            profile.id() + "/" + entry.getKey() + " has an undated citation");
                    if (oldest == null || citation.retrievedOn().isBefore(oldest)) {
                        oldest = citation.retrievedOn();
                    }
                }
            }
            assertEquals(oldest == null ? ON : oldest, profile.retrievedOn(),
                    profile.id() + " retrievedOn is not the oldest of its own citations");
        }
        assertEquals(ON, oldest);
    }

    @Test
    @DisplayName("an unmatched host resolves to default-deny, the strictest known behaviour")
    void unmatchedHostIsStrictest() {
        ComplianceProfileSet bundled = ComplianceProfileSet.bundled();
        for (String host : Arrays.asList("example.com", "play.someunknownserver.net",
                "localhost", "192.168.1.4:25565")) {
            ComplianceProfileSet.Resolution resolution = bundled.resolve(host);
            assertEquals(ComplianceProfile.DEFAULT_DENY_ID, resolution.profile().id(), host);
            assertFalse(resolution.binding().isPresent(), host + " matched a binding it should not");
        }
    }

    @ParameterizedTest(name = "{0} resolves to the {1} profile")
    @CsvSource({
            "pvphq.com,             pvphq",
            "eu.flowpvp.gg,         pvphq",
            "pvp.land,              pvp-land",
            "eu.pvp.land,           pvp-land",
            "play.pvplegacy.net,    pvplegacy",
            "mc.hypixel.net,        hypixel",
            "www.cubecraft.net,     cubecraft",
            "play.mineplex.com,     mineplex",
            "play.mcchampionship.com, mcc-island",
    })
    void hostResolution(String host, String expectedProfileId) {
        ComplianceProfileSet.Resolution resolution = ComplianceProfileSet.bundled().resolve(host);
        assertEquals(expectedProfileId, resolution.profile().id());
        assertTrue(resolution.binding().isPresent());
    }

    @Test
    @DisplayName("a port and a scheme are stripped before matching")
    void hostNormalisation() {
        assertEquals("pvphq.com", HostBinding.normaliseHost("PvPHQ.com:25565"));
        assertEquals("play.pvphq.com", HostBinding.normaliseHost("  play.pvphq.com  "));
        assertEquals("mc.hypixel.net", HostBinding.normaliseHost("https://mc.hypixel.net/"));
        assertEquals("pvp.land", HostBinding.normaliseHost("minecraft://pvp.land:25566"));
    }

    @Test
    @DisplayName("the longest matching pattern wins")
    void longestPatternWins() {
        ComplianceProfile specific = profileWithId("specific");
        ComplianceProfile general = profileWithId("general");
        Map<String, ComplianceProfile> profiles = new LinkedHashMap<String, ComplianceProfile>();
        profiles.put("specific", specific);
        profiles.put("general", general);
        profiles.put(ComplianceProfile.DEFAULT_DENY_ID,
                ComplianceProfileSet.bundled().defaultDeny());
        ComplianceProfileSet set = ComplianceProfileSet.of(profiles, Arrays.asList(
                new HostBinding("*.example.test", "general", "https://example.test/r", ON),
                new HostBinding("eu.example.test", "specific", "https://example.test/r", ON)));

        assertEquals("specific", set.resolve("eu.example.test").profile().id());
        assertEquals("general", set.resolve("us.example.test").profile().id());
        assertEquals(ComplianceProfile.DEFAULT_DENY_ID, set.resolve("example.test").profile().id(),
                "a bare domain does not match a wildcard");
        assertEquals(ComplianceProfile.DEFAULT_DENY_ID, set.resolve("nowhere.test").profile().id());
    }

    private static ComplianceProfile profileWithId(String id) {
        return new ComplianceProfile(id, id, ruleset("derivation"), true,
                new LinkedHashMap<String, ComplianceRow>(), Collections.<String>emptySet(), ON);
    }

    private static Set<String> ruleset(String id) {
        return new LinkedHashSet<String>(Collections.singletonList(id));
    }

    @Test
    @DisplayName("isStale is true when the snapshot predates the running build, and only then")
    void staleness() {
        ComplianceProfile profile = ComplianceProfileSet.bundled().byId("pvphq");
        assertTrue(profile.isStale(ON.plusDays(1)), "a build newer than the evidence is STALE");
        assertFalse(profile.isStale(ON), "the same day is not stale");
        assertFalse(profile.isStale(ON.minusDays(30)), "an older build cannot be stale");
        assertEquals(ComplianceProfile.Staleness.CURRENT, ComplianceProfile.Staleness.of(profile, ON));
        assertEquals(ComplianceProfile.Staleness.UNKNOWN,
                ComplianceProfile.Staleness.of(profile, null),
                "no release date means freshness cannot be claimed, and is not CURRENT either");
    }

    @Test
    @DisplayName("the allow list is add-only: withAllowGrant adds and returns a new profile")
    void allowListIsAddOnly() {
        ComplianceProfile shipped = ComplianceProfileSet.bundled().byId("pvphq");
        ComplianceProfile granted = shipped.withAllowGrant("latency.crystal-release");
        assertTrue(granted.allowList().contains("latency.crystal-release"));
        assertTrue(shipped.allowList().isEmpty(), "the original must be unchanged");
        assertFalse(granted == shipped);
    }

    @Test
    @DisplayName("a standing prohibition cannot be allow-listed")
    void prohibitionsCannotBeGranted() {
        ComplianceProfile shipped = ComplianceProfileSet.bundled().byId("pvphq");
        for (GlobalProhibition prohibition : GlobalProhibition.values()) {
            assertThrows(XsozContractException.class,
                    () -> shipped.withAllowGrant(prohibition.id()),
                    prohibition.id() + " must not be grantable");
        }
    }

    @Test
    @DisplayName("a profile cannot express a state that turns a Tier C feature on")
    void profileCannotEnableTierC() {
        Map<String, ComplianceRow> tampered = new LinkedHashMap<String, ComplianceRow>();
        // The hand-edited profile: the Tier C row emptied of its prohibition and moved to OWN_RISK.
        tampered.put("latency.consumable-optimizer", new ComplianceRow(
                Verdict.OWN_RISK, ComplianceTier.C,
                Collections.singletonList(ComplianceCitations.GRIM_FIGHTING_PREDICTION_DETECTION),
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                "moved by hand"));
        XsozContractException thrown = assertThrows(XsozContractException.class,
                () -> new ComplianceProfile("tampered", "Tampered", singleton("pvphq-website"), true,
                        tampered, Collections.<String>emptySet(), ON));
        assertTrue(thrown.getMessage().contains("Tier C"), thrown.getMessage());
    }

    @Test
    @DisplayName("a Tier A row cannot be DENIED: that would contradict what Tier A is")
    void tierACannotBeDenied() {
        assertThrows(XsozContractException.class,
                () -> new ComplianceRow(Verdict.DENIED, ComplianceTier.A,
                        Collections.singletonList(ComplianceCitations.STRICTEST_KNOWN),
                        Collections.<String>emptySet(), Collections.<String>emptySet(),
                        "a lie"));
    }

    @Test
    @DisplayName("a row with no citation is refused")
    void rowNeedsACitation() {
        assertThrows(MissingCitationException.class,
                () -> new ComplianceRow(Verdict.ALLOWED, ComplianceTier.A,
                        Collections.<Citation>emptyList(), Collections.<String>emptySet(),
                        Collections.<String>emptySet(), "no evidence"));
    }

    @Test
    @DisplayName("CONDITIONAL rows must state their conditions; other verdicts must not")
    void conditionsMatchTheVerdict() {
        assertThrows(XsozContractException.class,
                () -> new ComplianceRow(Verdict.CONDITIONAL, ComplianceTier.A,
                        Collections.singletonList(ComplianceCitations.PERF_ALLOWED),
                        Collections.<String>emptySet(), Collections.<String>emptySet(),
                        "conditional with no condition"));
        assertThrows(XsozContractException.class,
                () -> new ComplianceRow(Verdict.ALLOWED, ComplianceTier.A,
                        Collections.singletonList(ComplianceCitations.PERF_ALLOWED),
                        singleton("a-condition"), Collections.<String>emptySet(),
                        "allowed with a condition nobody checks"));
    }

    @Test
    @DisplayName("import/export is a strict round trip, and re-validates every citation on the way in")
    void importExportRoundTrip() {
        for (ComplianceProfile profile : ComplianceProfileSet.bundled().all()) {
            String json = profile.toJson();
            ComplianceProfile decoded = ComplianceProfile.fromJson(json);
            assertEquals(profile, decoded, profile.id() + " did not survive the round trip");
            assertEquals(json, decoded.toJson(), profile.id() + " is not byte-stable");
        }
    }

    @Test
    @DisplayName("an imported citation is re-validated: an over-long clause is refused, not trusted")
    void importRevalidatesCitations() {
        String json = ComplianceProfileSet.bundled().byId("pvphq").toJson()
                .replace("Totem Pop Counter", "one two three four five six seven eight nine ten "
                        + "eleven twelve thirteen fourteen fifteen sixteen");
        assertThrows(CitationTooLongException.class, () -> ComplianceProfile.fromJson(json));
    }

    @Test
    @DisplayName("a document with a non-https citation URL is refused on import")
    void importRejectsInsecureUrls() {
        String json = ComplianceProfileSet.bundled().byId("pvphq").toJson()
                .replace("https://pvp.land/rules", "http://pvp.land/rules");
        assertThrows(XsozContractException.class, () -> ComplianceProfile.fromJson(json));
    }

    @Test
    @DisplayName("a malformed document is refused with the field path")
    void malformedDocumentsAreRefused() {
        assertThrows(XsozContractException.class, () -> ComplianceProfile.fromJson(""));
        assertThrows(XsozContractException.class, () -> ComplianceProfile.fromJson("not json"));
        assertThrows(XsozContractException.class,
                () -> ComplianceProfile.fromJson("{\"format\":\"something-else\"}"));
        assertThrows(XsozContractException.class, () -> ComplianceProfile.fromJson(
                "{\"format\":\"xsoz-compliance-profile\",\"formatVersion\":99}"));
    }

    @Test
    @DisplayName("a host binding without provenance is refused")
    void hostBindingNeedsProvenance() {
        assertThrows(XsozContractException.class,
                () -> new HostBinding("pvphq.com", "pvphq", "http://pvphq.com/rules", ON));
        assertThrows(XsozContractException.class,
                () -> new HostBinding("pvphq.com", "pvphq", "https://pvphq.com/rules", null));
        assertThrows(XsozContractException.class,
                () -> new HostBinding("  ", "pvphq", "https://pvphq.com/rules", ON));
    }

    private static Set<String> singleton(String value) {
        return new LinkedHashSet<String>(Collections.singletonList(value));
    }
}
