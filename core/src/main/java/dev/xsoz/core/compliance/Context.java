package dev.xsoz.core.compliance;

/**
 * The single-value context the compliance gate is asked about (contracts.md C7.6,
 * {@code [CONTRACT DECISION] D3}).
 *
 * <p><strong>This is a single-value enum resolved by a total function. It is not a set of
 * flags.</strong> A flag set makes {@code authorize(moduleId, Context)} non-total in a way
 * that two engineers read differently - one writes "in WORLD and SERVER", another writes
 * "in WORLD". A single value resolved by {@link #resolve} has exactly one answer, and the
 * answer is auditable on the readiness overlay.</p>
 */
public enum Context {

    /** No level is loaded: the title screen, the launcher, the mod inventory. */
    WORLD_ABSENT,

    /** A level is loaded and no screen is open. Singleplayer, or a LAN world without a remote server. */
    WORLD,

    /** A level is loaded and a screen is open, <em>including our own screens</em>. */
    SCREEN_OPEN,

    /** A level is loaded, no screen is open, and a remote server connection is present. */
    SERVER;

    /**
     * The documented resolution priority, top to bottom (contracts.md C7.6).
     *
     * <p>This is a total function of its three inputs, so there is no fourth answer and no
     * ambiguity for a second agent to resolve differently.</p>
     *
     * @param worldPresent   whether a level is loaded; absence is semantic, not an error
     * @param screenOpen     whether any screen is open
     * @param serverPresent  whether a remote server connection is present
     * @return the one context that applies
     */
    public static Context resolve(boolean worldPresent, boolean screenOpen, boolean serverPresent) {
        if (!worldPresent) {
            return WORLD_ABSENT;
        }
        if (screenOpen) {
            return SCREEN_OPEN;
        }
        if (serverPresent) {
            return SERVER;
        }
        return WORLD;
    }
}
