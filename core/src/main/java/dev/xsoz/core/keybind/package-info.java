/**
 * Implements contract C7.12: the keybind validator, {@code BindPolicy}.
 *
 * <p><strong>ENFORCED IN CODE, NOT OMITTED FROM THE UI.</strong> The client must never
 * permit rebinding an attack/click action to a non-mouse key, a multi-key bind, a double
 * bind, or a null bind. A UI that merely hides the option is one merge away from showing it
 * again; a validator every load runs through is not.
 *
 * <p><strong>CONTAINS NO GAME TYPES AND NO DEPENDENCIES.</strong> The keybind editor is the
 * one part of the product a player can drive with no world loaded, so the whole package is
 * plain value types and one policy.
 *
 * <p>Source: "Internal or external modifications that allow abnormal keybinding or
 * double-binding (except standard mouse-required bindings)" - PvPHQ, and "Double Key Binds"
 * - PvP Land.
 *
 * <p>Owner: the compliance agent. Values mirrored from the C1 platform package
 * ({@code InputKey}, {@code InputKeyKind}) are declared locally here so this package stays
 * dependency-free until that package lands.
 */
package dev.xsoz.core.keybind;
