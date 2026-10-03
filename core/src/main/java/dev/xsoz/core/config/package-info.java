/**
 * Implements contract C5: the versioned profile store, {@code Profile}, the bundled
 * {@code chosen-one} default profile and the migration chain.
 *
 * <p><strong>CONTAINS NO GAME TYPES.</strong> A stored sensitivity is {@code cmPer360}
 * only; a stored raw slider percentage is a schema error and is reported and dropped.</p>
 *
 * <p>What lives here, and why each piece exists:</p>
 * <ul>
 *   <li>{@link dev.xsoz.core.config.Profile} and its value types - one profile is the whole
 *       of what a player configures.</li>
 *   <li>{@link dev.xsoz.core.config.ProfileSensitivity} - the canonical {@code cmPer360}
 *       plus the adaptation ramp's position. The ramp table itself is code, in
 *       {@code dev.xsoz.core.sensitivity}, because a 1.5x generator overshoots the
 *       target.</li>
 *   <li>{@link dev.xsoz.core.config.Provenance} - a value with no provenance is a
 *       rumour, and the citation travels with the data.</li>
 *   <li>{@link dev.xsoz.core.config.FileProfileStore} - the atomic write, the four-step
 *       corruption recovery, the ordered import, the lifecycle and its four delete guards.
 *       A corrupt config degrades to a working default; it never throws on startup.</li>
 *   <li>{@link dev.xsoz.core.config.MigrationRegistry} - the schema version and its chain.
 *       No downgrade path: a file newer than this build is refused, not guessed at.</li>
 * </ul>
 *
 * <p>The store does not own the module registry (C2.5 belongs to
 * {@code dev.xsoz.core.module}) and does not reach a tick loop or a module gate, so both
 * arrive as interfaces: {@link dev.xsoz.core.config.ModuleCatalog} and
 * {@link dev.xsoz.core.config.ProfileSwitchListener}.</p>
 */
package dev.xsoz.core.config;
