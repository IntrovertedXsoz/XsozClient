/**
 * The service provider interface every Minecraft pole implements (contracts.md C10).
 *
 * <p><strong>CONTAINS NO GAME TYPES.</strong> Core resolves exactly one implementation of
 * each interface in this package through {@code java.util.ServiceLoader}, so core never
 * names a pole class and a compile of core alone needs no pole on the classpath.</p>
 *
 * <p>Owned by the platform-API surface, not by the core logic. Adding a pole later is
 * additive: a new {@code platform-*} subproject implementing these interfaces changes no
 * other contract.</p>
 */
package dev.xsoz.core.platform.spi;
