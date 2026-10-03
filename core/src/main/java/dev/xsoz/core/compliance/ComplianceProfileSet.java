package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The bundled set of compliance profiles and the host patterns that select between them
 * (contracts.md C7.4).
 *
 * <p><strong>Seven competitive networks are bound by default, with an EMPTY allow list.</strong>
 * Every one of them is {@code defaultDeny}. Tier A is {@code ALLOWED} by definition -
 * that is what Tier A <em>is</em> - and the {@code DENIED} applies to the optimising set,
 * Tier B, "at install". The allow list of {@code (host, featureId)} grants that would
 * resolve a Tier B row to allowed is empty on install and add-only.</p>
 *
 * <p><strong>An unmatched host resolves to {@code default-deny}</strong>, the strictest known
 * behaviour. A server we have never read is not a server we assume well of.</p>
 *
 * <p>Resolution is <em>longest matching host pattern wins</em>; a tie is broken by
 * declaration order so two agents cannot disagree about which profile matched.</p>
 */
public final class ComplianceProfileSet {

    private final Map<String, ComplianceProfile> profiles;
    private final List<HostBinding> bindings;

    private ComplianceProfileSet(Map<String, ComplianceProfile> profiles, List<HostBinding> bindings) {
        this.profiles = Collections.unmodifiableMap(profiles);
        this.bindings = Collections.unmodifiableList(bindings);
    }

    /**
     * Builds a set from profiles and bindings. Used for the bundled set and for tests that
     * need a different host table; every set must contain {@code default-deny}, because an
     * unmatched host resolves to it.
     *
     * @param profiles profiles by id, defensively copied
     * @param bindings host bindings, defensively copied
     * @return the assembled set
     * @throws XsozContractException if a profile is {@code null}, a profile id is duplicated,
     *                                or {@code default-deny} is missing
     */
    public static ComplianceProfileSet of(Map<String, ComplianceProfile> profiles,
                                          List<HostBinding> bindings) {
        if (profiles == null || profiles.isEmpty()) {
            throw new XsozContractException("A compliance profile set needs at least default-deny.");
        }
        Map<String, ComplianceProfile> copy = new LinkedHashMap<String, ComplianceProfile>();
        for (Map.Entry<String, ComplianceProfile> entry : profiles.entrySet()) {
            if (entry.getValue() == null) {
                throw new XsozContractException(
                        "Profile set holds a null profile for id \"" + entry.getKey() + "\".");
            }
            if (copy.put(entry.getKey(), entry.getValue()) != null) {
                throw new XsozContractException(
                        "Duplicate compliance profile id \"" + entry.getKey() + "\".");
            }
        }
        if (!copy.containsKey(ComplianceProfile.DEFAULT_DENY_ID)) {
            throw new XsozContractException(
                    "A compliance profile set must contain \"" + ComplianceProfile.DEFAULT_DENY_ID
                            + "\". An unmatched host resolves to it, so omitting it would leave the "
                            + "strictest known behaviour undefined.");
        }
        List<HostBinding> bindingCopy = new ArrayList<HostBinding>();
        if (bindings != null) {
            for (HostBinding binding : bindings) {
                if (binding == null) {
                    throw new XsozContractException("A profile set must not hold a null host binding.");
                }
                if (!copy.containsKey(binding.profileId())) {
                    throw new XsozContractException(
                            "Host binding " + binding + " names profile \"" + binding.profileId()
                                    + "\", which is not in the set. A binding to a missing profile "
                                    + "resolves to a profile the gate cannot read.");
                }
                bindingCopy.add(binding);
            }
        }
        return new ComplianceProfileSet(copy, bindingCopy);
    }

    /**
     * @return the profile with the given id
     * @throws XsozContractException if no such profile is bundled
     */
    public ComplianceProfile byId(String profileId) {
        ComplianceProfile profile = profileId == null ? null : profiles.get(profileId);
        if (profile == null) {
            throw new XsozContractException(
                    "No bundled compliance profile with id \"" + profileId + "\". Bundled ids: "
                            + profiles.keySet());
        }
        return profile;
    }

    /** @return every bundled profile, in declaration order, {@code default-deny} first */
    public List<ComplianceProfile> all() {
        return Collections.unmodifiableList(new ArrayList<ComplianceProfile>(profiles.values()));
    }

    /** @return the {@code default-deny} profile, the strictest known behaviour */
    public ComplianceProfile defaultDeny() {
        return profiles.get(ComplianceProfile.DEFAULT_DENY_ID);
    }

    /** @return every host binding, in declaration order */
    public List<HostBinding> bindings() {
        return bindings;
    }

    /** @return the date every bundled profile's evidence was retrieved */
    public LocalDate rulesSnapshotDate() {
        LocalDate oldest = null;
        for (ComplianceProfile profile : profiles.values()) {
            if (oldest == null || profile.retrievedOn().isBefore(oldest)) {
                oldest = profile.retrievedOn();
            }
        }
        return oldest;
    }

    /**
     * Resolves a typed server address to a profile.
     *
     * @param serverAddress exactly what the player typed, optionally with a port or scheme
     * @return the matched profile and, when there was one, the binding that matched
     */
    public Resolution resolve(String serverAddress) {
        String host = HostBinding.normaliseHost(serverAddress);
        HostBinding best = null;
        for (HostBinding binding : bindings) {
            if (!binding.matches(host)) {
                continue;
            }
            if (best == null || binding.specificity() > best.specificity()) {
                best = binding;
            }
        }
        if (best == null) {
            return new Resolution(defaultDeny(), Optional.<HostBinding>empty());        }
        return new Resolution(byId(best.profileId()), Optional.of(best));
    }

    /** The outcome of host resolution: the profile, and the binding if any. */
    public static final class Resolution {

        private final ComplianceProfile profile;
        private final Optional<HostBinding> binding;

        Resolution(ComplianceProfile profile, Optional<HostBinding> binding) {
            this.profile = profile;
            this.binding = binding;
        }

        /** @return the resolved profile; never {@code null} */
        public ComplianceProfile profile() {
            return profile;
        }

        /** @return the binding that matched, or empty when the host is unmatched */
        public Optional<HostBinding> binding() {
            return binding;
        }
    }

    /**
     * The bundled set: {@code default-deny} plus one profile per studied competitive network,
     * with the host patterns contracts.md C7.4 binds by default.
     *
     * @return the shipped, immutable set
     */
    public static ComplianceProfileSet bundled() {
        LocalDate on = ComplianceCitations.SOURCES_RETRIEVED_ON;

        ComplianceProfile defaultDeny = new ComplianceProfile(
                ComplianceProfile.DEFAULT_DENY_ID,
                "Strictest known behaviour (no network matched)",
                rulesetSet("pvphq-website", "pvp-land", "pvplegacy", "hypixel", "cubecraft",
                        "mineplex", "mcc-island", "grim-wiki", "anticheck-doc"),
                true,
                shippedVerdicts(ComplianceCitations.STRICTEST_KNOWN, ComplianceCitations.PERF_ALLOWED),
                new LinkedHashSet<String>(),
                on);

        ComplianceProfile pvphq = new ComplianceProfile(
                "pvphq", "PvPHQ / FlowPvP",
                rulesetSet("pvphq-website", "pvphq-sheet"),
                true,
                shippedVerdicts(ComplianceCitations.STRICTEST_KNOWN, ComplianceCitations.PERF_ALLOWED),
                new LinkedHashSet<String>(),
                on);

        ComplianceProfile pvpLand = new ComplianceProfile(
                "pvp-land", "PvP Land",
                rulesetSet("pvp-land"),
                true,
                shippedVerdicts(ComplianceCitations.DOUBLE_KEY_BINDS,
                        ComplianceCitations.PERF_ALLOWED_PVP_LAND),
                new LinkedHashSet<String>(),
                on);

        ComplianceProfile pvpLegacy = new ComplianceProfile(
                "pvplegacy", "PvP Legacy",
                rulesetSet("pvplegacy"),
                true,
                shippedVerdicts(ComplianceCitations.ROTATING_FOR_YOU, ComplianceCitations.PERF_ALLOWED),
                new LinkedHashSet<String>(),
                on);

        ComplianceProfile hypixel = new ComplianceProfile(
                "hypixel", "Hypixel",
                rulesetSet("hypixel"),
                true,
                shippedVerdicts(ComplianceCitations.STRICTEST_KNOWN, ComplianceCitations.PERF_ALLOWED),
                new LinkedHashSet<String>(),
                on);

        ComplianceProfile cubeCraft = new ComplianceProfile(
                "cubecraft", "CubeCraft",
                rulesetSet("cubecraft"),
                true,
                shippedVerdicts(ComplianceCitations.CHEAT_MODULE_PRESENCE_BANNED,
                        ComplianceCitations.PERF_ALLOWED),
                new LinkedHashSet<String>(),
                on);

        ComplianceProfile mineplex = new ComplianceProfile(
                "mineplex", "Mineplex",
                rulesetSet("mineplex"),
                true,
                shippedVerdicts(ComplianceCitations.STRICTEST_KNOWN, ComplianceCitations.PERF_ALLOWED),
                new LinkedHashSet<String>(),
                on);

        ComplianceProfile mccIsland = new ComplianceProfile(
                "mcc-island", "MCC Island",
                rulesetSet("mcc-island"),
                true,
                shippedVerdicts(ComplianceCitations.STRICTEST_KNOWN, ComplianceCitations.PERF_ALLOWED),
                new LinkedHashSet<String>(),
                on);

        Map<String, ComplianceProfile> profiles = new LinkedHashMap<String, ComplianceProfile>();
        profiles.put(ComplianceProfile.DEFAULT_DENY_ID, defaultDeny);
        profiles.put("pvphq", pvphq);
        profiles.put("pvp-land", pvpLand);
        profiles.put("pvplegacy", pvpLegacy);
        profiles.put("hypixel", hypixel);
        profiles.put("cubecraft", cubeCraft);
        profiles.put("mineplex", mineplex);
        profiles.put("mcc-island", mccIsland);

        List<HostBinding> bindings = Arrays.asList(
                new HostBinding("pvphq.com", "pvphq", "https://pvphq.com/rules", on),
                new HostBinding("*.flowpvp.gg", "pvphq", "https://pvphq.com/rules", on),
                new HostBinding("pvp.land", "pvp-land", "https://pvp.land/rules", on),
                new HostBinding("*.pvp.land", "pvp-land", "https://pvp.land/rules", on),
                new HostBinding("*.pvplegacy.net", "pvplegacy", "https://pvplegacy.net/mods", on),
                new HostBinding("*.hypixel.net", "hypixel", "https://support.hypixel.net/", on),
                new HostBinding("*.cubecraft.net", "cubecraft",
                        "https://www.cubecraft.net/threads/allowed-mods-and-clients.228596/", on),
                new HostBinding("*.mineplex.com", "mineplex", "https://www.mineplex.com/rules", on),
                new HostBinding("*.mcchampionship.com", "mcc-island",
                        "https://www.mcchampionship.com/rules", on));

        return new ComplianceProfileSet(profiles, bindings);
    }

    /**
     * The shipped verdict table, identical in shape across every bundled profile because the
     * posture is identical: Tier A allowed, Tier B denied at install, Tier C absent.
     *
     * @param denyCitation   the clause the Tier B DENIED row cites
     * @param allowCitation  the clause the Tier A ALLOWED row cites
     * @return a mutable verdict map, ready for the {@code ComplianceProfile} constructor
     */
    private static Map<String, ComplianceRow> shippedVerdicts(Citation denyCitation, Citation allowCitation) {
        Map<String, ComplianceRow> verdicts = new LinkedHashMap<String, ComplianceRow>();

        verdicts.put("hud.totem-counter", new ComplianceRow(
                Verdict.ALLOWED,
                ComplianceTier.A,
                Collections.singletonList(ComplianceCitations.TOTEM_POP_COUNTER_ALLOWED),
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                "Own-state counter, named on PvP Land's specific-allowed list. Renders only your own "
                        + "inventory."));

        verdicts.put("perf.render-distance", new ComplianceRow(
                Verdict.CONDITIONAL,
                ComplianceTier.A,
                Collections.singletonList(allowCitation),
                new LinkedHashSet<String>(Arrays.asList(
                        "render-distance-checks-server-value", "simulation-distance-checks-server-value")),
                Collections.<String>emptySet(),
                "Clamped to the server's own join-packet values; exceeding them is the failure."));

        verdicts.put("coach.gate-overlay", new ComplianceRow(
                Verdict.ALLOWED,
                ComplianceTier.A,
                Collections.singletonList(allowCitation),
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                "A read-only overlay showing the coaching gate's predicate chain. Inert unless armed, "
                        + "and it acts on no input."));

        verdicts.put("latency.crystal-release", new ComplianceRow(
                Verdict.DENIED,
                ComplianceTier.B,
                Collections.singletonList(denyCitation),
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                "CONTESTED and Tier B. Denied at install on every bundled profile; the empty allow "
                        + "list is why it ships off."));

        verdicts.put("latency.anchor-ghost", new ComplianceRow(
                Verdict.DENIED,
                ComplianceTier.B,
                Collections.singletonList(ComplianceCitations.GRIM_YIELDING_PREDICTION_FALSE_POSITIVE),
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                "Grim's own page names this class a false-positive source. Yielding, and still not "
                        + "shipped on by default."));

        verdicts.put("latency.consumable-optimizer", new ComplianceRow(
                Verdict.DENIED,
                ComplianceTier.C,
                Collections.singletonList(ComplianceCitations.GRIM_FIGHTING_PREDICTION_DETECTION),
                Collections.<String>emptySet(),
                Collections.singleton(GlobalProhibition.PACKET_INTERCEPTION.id()),
                "Discards the server's authoritative stop-eating recall. Never compiled; this row "
                        + "exists so a hand-edited profile cannot express a state that enables it."));

        return verdicts;
    }

    private static Set<String> rulesetSet(String... ids) {
        Set<String> set = new LinkedHashSet<String>();
        for (String id : ids) {
            set.add(id);
        }
        return set;
    }
}
