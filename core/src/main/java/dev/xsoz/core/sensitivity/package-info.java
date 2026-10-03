/**
 * Implements contract C6: the canonical {@code SensitivityModel}, its FOV-relative
 * modes, the achievable-range hint, and the 7-stage adaptation ramp.
 *
 * <p><strong>CONTAINS NO GAME TYPES.</strong> This package is pure arithmetic about a
 * physical mouse. That is what makes it testable: the canonical formula, the 1.2
 * coefficient and the frozen ramp table are all provable without launching a game.</p>
 *
 * <p><strong>THE STORED UNIT IS {@code cmPer360}, AND ONLY {@code cmPer360}</strong>
 * (C6.1, {@code [CONTRACT DECISION] D1}). The vanilla slider value {@code s} is
 * derived on read and never persisted.</p>
 *
 * <p><strong>THE COEFFICIENT IS 1.2. IT IS NOT 8.0.</strong> See the trap note at the
 * top of {@link dev.xsoz.core.sensitivity.SensitivityModel}: 8.0 is a half-formed
 * intermediate from one method of a two-method vanilla call chain, and stopping there
 * is a factor of 1 / 0.15 = 6.6667 error.</p>
 *
 * <p>Owner: the sensitivity agent.</p>
 */
package dev.xsoz.core.sensitivity;
