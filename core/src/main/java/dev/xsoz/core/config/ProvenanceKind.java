package dev.xsoz.core.config;

/**
 * Where a profile's numbers came from (contracts.md C5.2, {@code provenance.kind}).
 *
 * <p>Closed enum, the three values C5.2 names exactly. The distinction is not
 * bookkeeping: for the two non-user kinds, {@link Provenance} <em>requires</em> a source
 * URL and a retrieval date, and refuses an {@code https}-less one. A value with no
 * provenance is a rumour, and the type says so at construction rather than at review.</p>
 */
public enum ProvenanceKind {

    /**
     * Numbers a named person published about themselves, in their own words.
     *
     * <p>Requires a citation. "A profile named after a real person must carry its citation"
     * (C5.2) is why this kind exists at all.</p>
     */
    PUBLISHED_SETTINGS,

    /**
     * A community convention reproduced from a server-maintained source, explicitly
     * <em>not</em> anybody's personal setup.
     */
    COMMUNITY_CONVENTION,

    /**
     * The player's own numbers, entered by hand. No source and no retrieval date, and
     * none required: there is nothing to cite but the player.
     */
    USER_DEFINED
}
