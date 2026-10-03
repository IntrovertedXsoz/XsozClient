package dev.xsoz.core.compliance;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The standing prohibitions no profile can unlock (contracts.md C7.4, and
 * {@code docs/rules-matrix.md} 1.2).
 *
 * <p>These are the six behaviours that are banned on every ruleset studied, so they are not
 * per-server data and are not configuration. A {@code ComplianceRow} that names one of these
 * ids in {@link ComplianceRow#prohibitionsAlways()} is a hard deny, and the profile schema
 * cannot express a state that turns one of them off - which is what makes a hand-edited
 * profile inert rather than permissive.</p>
 *
 * <p>Each constant carries its own dated {@link Citation}, so a refused feature can always
 * say why, from where, and as of when.</p>
 */
public enum GlobalProhibition {

    /**
     * Any packet interception, injection, duplication or reordering. Source: the universal
     * network-layer ban.
     */
    PACKET_INTERCEPTION(
            "packet-interception",
            ComplianceCitations.NETWORK_LAYER_STRICTLY_DISALLOWED,
            "Intercepting, injecting, duplicating or reordering a packet"),

    /**
     * Protocol translation. Source: denied by name on five of the seven networks.
     */
    PROTOCOL_TRANSLATION(
            "protocol-translation",
            ComplianceCitations.STRICTEST_KNOWN,
            "Translating the protocol between client and server versions"),

    /**
     * Anything that makes a rotation delta look human. Source: PvP Legacy's
     * "or rotating for you".
     */
    ROTATION_HUMANISING(
            "rotation-humanising",
            ComplianceCitations.ROTATING_FOR_YOU,
            "Changing a rotation delta so it appears human"),

    /**
     * Any log filter. Source: PvPHQ's own-risk note that a filtered log "may be more
     * difficult to be unbanned".
     */
    LOG_FILTER(
            "log-filter",
            ComplianceCitations.LOG_FILTER_OWN_RISK,
            "Filtering the game log"),

    /**
     * Any {@code latest.log} manipulation. Source: the D-hand author's own admission that it
     * is detectable there.
     */
    LATEST_LOG_MANIPULATION(
            "latest-log-manipulation",
            ComplianceCitations.LOG_FILTER_OWN_RISK,
            "Manipulating latest.log"),

    /**
     * Brand impersonation of another client. Source: Grim's {@code ignored-clients} list is a
     * server-side allowlist, so a client claiming another client's brand is lying to the
     * operator, not to us.
     */
    BRAND_IMPERSONATION(
            "brand-impersonation",
            ComplianceCitations.STRICTEST_KNOWN,
            "Impersonating another client's brand");

    private final String id;
    private final Citation citation;
    private final String summary;

    GlobalProhibition(String id, Citation citation, String summary) {
        this.id = id;
        this.citation = citation;
        this.summary = summary;
    }

    /** @return the stable prohibition id used inside a {@link ComplianceRow} */
    public String id() {
        return id;
    }

    /** @return the dated evidence this prohibition rests on */
    public Citation citation() {
        return citation;
    }

    /** @return one line, rendered permanently disabled in the UI with the reason shown */
    public String summary() {
        return summary;
    }

    /**
     * Resolves a prohibition id.
     *
     * @param id the id to resolve; may be {@code null}
     * @return the matching prohibition, or {@code null} if the id is not a known prohibition
     */
    public static GlobalProhibition byId(String id) {
        for (GlobalProhibition prohibition : values()) {
            if (prohibition.id.equals(id)) {
                return prohibition;
            }
        }
        return null;
    }

    /**
     * @return every prohibition id, in declaration order, for stamping onto a profile
     */
    public static Set<String> allIds() {
        Set<String> ids = new LinkedHashSet<String>();
        for (GlobalProhibition prohibition : values()) {
            ids.add(prohibition.id);
        }
        return Collections.unmodifiableSet(ids);
    }
}
