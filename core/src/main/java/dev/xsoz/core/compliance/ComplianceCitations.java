package dev.xsoz.core.compliance;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * The frozen evidence the engine cites on its refuse paths (contracts.md C7.2, C7.4, 0.5).
 *
 * <p>Every clause below is quoted from the retrieved source in
 * {@code docs/rules-matrix.md} or {@code docs/anticheat-risk.md}, and every retrieval date
 * is the date those documents record. They are constants because a citation that is built
 * at runtime from a template is a citation nobody checked.</p>
 *
 * <p><strong>Each is verified at class-initialisation against
 * {@link Citation#MAX_CLAUSE_WORDS}</strong>, so a future edit that lengthens a clause fails
 * the build rather than shipping.</p>
 */
public final class ComplianceCitations {

    /**
     * The retrieval date every bundled source was read on, as recorded in
     * {@code docs/anticheat-risk.md} ("Every URL below was retrieved 2026-09-28") and
     * {@code docs/rules-matrix.md}.
     */
    public static final LocalDate SOURCES_RETRIEVED_ON = LocalDate.of(2026, 9, 28);

    private static final String GRIM_INCOMPATIBLE_MODS =
            "https://raw.githubusercontent.com/wiki/GrimAnticheat/Grim/Known-incompatible-client-mods.md";

    /** The strictest clause studied: the deny clause, cited whenever nothing else is known. */
    public static final Citation STRICTEST_KNOWN = citation(
            "pvphq-website",
            "Mods that circumvent or bypass normal gameplay mechanics",
            "https://pvphq.com/rules");

    /** PvPHQ's performance category, which is what Tier A features are allowed under. */
    public static final Citation PERF_ALLOWED = citation(
            "pvphq-website",
            "improve FPS or client performance without affecting gameplay",
            "https://pvphq.com/rules");

    /** PvP Land's performance category, the second independent Tier A blanket. */
    public static final Citation PERF_ALLOWED_PVP_LAND = citation(
            "pvp-land",
            "Performance Mods: Generally allowed unless specified otherwise",
            "https://pvp.land/rules");

    /**
     * Grim's own incompatible-mods page, <em>false positive</em> half: a prediction that
     * yields is a documented false-positive source ({@code anticheat-risk.md} 7.0).
     */
    public static final Citation GRIM_YIELDING_PREDICTION_FALSE_POSITIVE = citation(
            "grim-wiki",
            "Removes anchors client-side, flagging Simulation.",
            GRIM_INCOMPATIBLE_MODS);

    /**
     * Grim's own incompatible-mods page, <em>detection</em> half: discarding a
     * server-authoritative packet is a detection, not a false positive.
     */
    public static final Citation GRIM_FIGHTING_PREDICTION_DETECTION = citation(
            "grim-wiki",
            "Ignores the server's item use state, flagging NoSlow.",
            GRIM_INCOMPATIBLE_MODS);

    /** The falsifiability shape itself, quoted from the reference implementation. */
    public static final Citation FALSIFIABILITY_IS_A_PROPERTY = citation(
            "derivation",
            "acknowledged(sequence) releases on the server's confirmation",
            "https://github.com/Bram1903/MarlowsCrystalOptimizer");

    /** PvPHQ's own statement of the network-layer ban. */
    public static final Citation NETWORK_LAYER_STRICTLY_DISALLOWED = citation(
            "pvphq-website",
            "strictly disallowed",
            "https://pvphq.com/rules");

    /** PvP Legacy's ban on rotating for the player. */
    public static final Citation ROTATING_FOR_YOU = citation(
            "pvplegacy",
            "or rotating for you",
            "https://pvplegacy.net/mods");

    /** PvPHQ's own-risk note on log filtering. */
    public static final Citation LOG_FILTER_OWN_RISK = citation(
            "pvphq-sheet",
            "It may be more difficult to be unbanned",
            "https://pvphq.com/rules");

    /** The keybind clause, verbatim. This is what {@code BindPolicy} enforces. */
    public static final Citation ABNORMAL_KEYBINDING_DISALLOWED = citation(
            "pvphq-website",
            "Internal or external modifications that allow abnormal keybinding or double-binding",
            "https://pvphq.com/rules");

    /** PvP Land's short name for the same behaviour. */
    public static final Citation DOUBLE_KEY_BINDS = citation(
            "pvp-land",
            "Double Key Binds",
            "https://pvp.land/rules");

    /** PvP Land's specific-allowed list, which names the own-state totem counter. */
    public static final Citation TOTEM_POP_COUNTER_ALLOWED = citation(
            "pvp-land",
            "Totem Pop Counter",
            "https://pvp.land/rules");

    /**
     * CubeCraft bans the <em>presence</em> of a cheat module, not its use. This is the
     * structural reason Tier C is absent from the binary rather than disabled.
     */
    public static final Citation CHEAT_MODULE_PRESENCE_BANNED = citation(
            "cubecraft",
            "Clients with cheat modules, even if you don't use them",
            "https://www.cubecraft.net/threads/allowed-mods-and-clients.228596/");

    private ComplianceCitations() {
        throw new AssertionError("ComplianceCitations is a constant holder and is not instantiable.");
    }

    /**
     * The default-deny profile's row for a feature we know nothing about. Every refusal
     * path in {@link DefaultComplianceEngine} that has no better evidence cites this, so the
     * refusal is still explained and still dated.
     *
     * @return a single-element, immutable list holding {@link #STRICTEST_KNOWN}
     */
    public static List<Citation> strictestKnown() {
        return Collections.singletonList(STRICTEST_KNOWN);
    }

    private static Citation citation(String rulesetId, String clause, String url) {
        return new Citation(rulesetId, clause, url, SOURCES_RETRIEVED_ON);
    }
}
