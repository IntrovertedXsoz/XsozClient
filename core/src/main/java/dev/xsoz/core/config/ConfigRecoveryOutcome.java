package dev.xsoz.core.config;

/**
 * What happened when a config file was loaded (contracts.md C5.4).
 *
 * <p>The names are the ones C5.4 names, because a recovery notice appears on the
 * mod-inventory screen and staff may be looking at a screenshot of that screen. A
 * config that silently reset itself is a config the player cannot explain.</p>
 */
public enum ConfigRecoveryOutcome {

    /** The primary file parsed and validated. Nothing happened. */
    USED_PRIMARY,

    /**
     * The primary file was unusable; {@code .bak} was used and immediately rewritten over
     * the primary (C5.4 step 3).
     */
    CONFIG_RECOVERED_FROM_BACKUP,

    /**
     * Both the primary file and the backup were unusable; the bundled default was built and
     * written (C5.4 step 4).
     */
    CONFIG_RESET_TO_DEFAULT,

    /**
     * The in-memory state committed but the profile file could not be deleted, so the id is
     * held as a zombie and cannot be resurrected as a duplicate (C5.6 guard 4).
     */
    PROFILE_FILE_DELETE_FAILED,

    /**
     * The store could not be opened at all and is running entirely in memory. The session
     * works; nothing will be written.
     */
    STORE_OPENED_IN_MEMORY
}
