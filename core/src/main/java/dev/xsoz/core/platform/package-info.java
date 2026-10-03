/**
 * Implements contract C1: the {@code GameAccessFacade} seam between core and the game,
 * its immutable snapshot value types, the closed {@code Capability} set and
 * {@code PlatformIdentity}.
 *
 * <p><strong>CONTAINS NO GAME TYPES.</strong> Not a style preference: a facade method
 * never returns a live game object, so every type crossing this boundary is a
 * primitive, a JDK type, or a value type defined in this package.</p>
 *
 * <p>Owner: the facade/snapshots agent. This package is deliberately empty.</p>
 */
package dev.xsoz.core.platform;
