package dev.xsoz.core.config;

import dev.xsoz.core.setting.JsonObject;

/**
 * One step of the config migration chain (contracts.md C4.5).
 *
 * <p><strong>{@link #migrate(JsonObject)} is PURE.</strong> It must not touch the
 * filesystem and must not read the clock. A migration that reads the clock produces a
 * different file every run, so a player cannot tell whether their settings changed or the
 * migration did.</p>
 *
 * <p><strong>There is no downgrade path.</strong> A document whose {@code schema} is
 * newer than {@link MigrationRegistry#currentVersion()} is refused, not guessed at
 * (C4.5, C5.4).</p>
 */
public interface Migration {

    /**
     * @return the schema version this step upgrades FROM, inclusive. C4.5: steps run in
     *         ascending {@code fromVersion} until {@code currentVersion} is reached.
     */
    int fromVersion();

    /**
     * @return a one-line description, shown in the migration log. It has to say what
     *         changed, because a player looking at a diffed config deserves to know.
     */
    String description();

    /**
     * @param input the document at version {@link #fromVersion()}
     * @return the document at {@code fromVersion() + 1}; a defensive copy, never the input
     */
    JsonObject migrate(JsonObject input);
}
