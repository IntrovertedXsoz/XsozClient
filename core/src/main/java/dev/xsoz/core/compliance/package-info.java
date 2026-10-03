/**
 * Implements contract C7: the compliance gate - tiers, verdicts, citations, the
 * decision engine, the startup self-test and the {@code xsoz:*} server opt-out
 * protocol.
 *
 * <p><strong>CONTAINS NO GAME TYPES.</strong> The gate reads a description of what the
 * product does; it never observes or manipulates the game to do it.</p>
 *
 * <p><strong>A VERDICT WITHOUT A CITATION IS INVALID AND MUST THROW</strong> (C7.2).
 * Tier C is never compiled into the product at all - not disabled, absent.</p>
 *
 * <p>Owner: the compliance agent.</p>
 */
package dev.xsoz.core.compliance;
