package dev.xsoz.core.compliance;

/**
 * The closed inbound signal surface as the compliance subsystem sees it (contracts.md C1.5).
 *
 * <p><strong>This is the C7-local mirror of C1.5.</strong> {@code ServerSignal} is specified
 * in the C1 platform package, which a separate agent owns and which is not written yet. The
 * compliance package must be compilable and testable today, so the constant set is mirrored
 * here verbatim rather than depending on a package that does not exist. When
 * {@code dev.xsoz.core.platform} lands its own {@code ServerSignal}, this file is deleted
 * and the import is added; the constant names and meanings are already frozen and will not
 * move.</p>
 *
 * <p><strong>There is no "give me a packet" method and there is none to add</strong> (C1.5).
 * A feature that needs to know the server did something names one constant here, and the
 * adapter maps a specific received packet type to it in exactly one place per pole.</p>
 *
 * <p>Signals are produced by non-cancellable observation of packets the client has already
 * received. There is no cancel, no send, no reorder and no inject anywhere on this
 * surface.</p>
 */
public enum ServerSignal {

    /** The server removed an entity we know about. The falsifier for post-break crystal release. */
    ENTITY_REMOVED,

    /** The server set a block at a position. */
    BLOCK_STATE_AT,

    /** The server acknowledged a swing/interact sequence. */
    PLAYER_ACTION_ACK,

    /**
     * The server told us to stop using an item. Named here precisely so the
     * Consumable-Optimizer class is describable - and precisely so a {@link Predictor} that
     * names it is visibly naming a signal whose entire function would be to discard it.
     */
    ITEM_USE_STATE_RESET,

    /** An entity metadata update arrived. */
    PLAYER_METADATA,

    /** A keep-alive round trip completed. */
    KEEP_ALIVE_ACK,

    /** The server moved us. The rubber-band signature. */
    POSITION_CORRECTION,

    /** A server-originated text line. */
    CHAT_SERVER,

    /** The join packet's spawn data. */
    SPAWN_POSITION,

    /** A serverbound-to-us {@code xsoz:opt_out} payload. */
    XSOZ_OPT_OUT,

    /** A serverbound-to-us {@code xsoz:challenge} payload. */
    XSOZ_CHALLENGE,

    /** A serverbound-to-us {@code xsoz:state_query} payload. */
    XSOZ_STATE_QUERY,

    /** The server sent a disconnect. */
    DISCONNECT_REASON
}
