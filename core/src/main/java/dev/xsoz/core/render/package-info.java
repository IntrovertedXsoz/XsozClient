/**
 * Implements contract C8: the HUD draw commands ({@code DrawList}) and the compositor that
 * replays them through a pole's own renderer.
 *
 * <p>This is the package the task briefs sometimes call "hud"; the frozen name is
 * {@code render} (contracts.md 0.3).</p>
 *
 * <p><strong>CONTAINS NO GAME TYPES.</strong> These are our own immediate-mode commands,
 * not calls into a game's draw API - which is the only reason one HUD implementation can
 * be shared across poles whose draw APIs differ by eight years.</p>
 *
 * <p>Owner: the HUD/render agent. This package is deliberately empty.</p>
 */
package dev.xsoz.core.render;
