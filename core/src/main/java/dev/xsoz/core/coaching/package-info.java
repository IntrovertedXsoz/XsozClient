/**
 * Implements contract C9: the read-only coaching and analytics subsystem - the 11-state
 * FSM, the metric set, the engagement segmentation and the post-match reporting.
 *
 * <p><strong>CONTAINS NO GAME TYPES, AND NEVER ACTS ON THE GAME.</strong> This is the
 * package the server rules care about most, so its import closure is checked separately:
 * it must reach none of the input-emitting or network-writing surfaces. A coaching writer
 * thread never calls the facade; it receives already-built frames handed over by the game
 * thread.</p>
 *
 * <p>Owner: the coaching agent. This package is deliberately empty.</p>
 */
package dev.xsoz.core.coaching;
