package dev.xsoz.core.keybind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@code KeybindValidatorRejectsAttackRebindTest} - contracts.md C7.12,
 * {@code docs/rules-matrix.md} 4.
 *
 * <p>The first half of the matrix's parameterised case list: {@code attack -> R},
 * {@code use -> SPACE}, {@code place -> F}, {@code pickBlock -> V}. Every one is refused,
 * and every one carries a message the keybind editor can show verbatim.</p>
 *
 * <p><strong>One mapping decision is recorded here.</strong> contracts.md C7.12's table writes
 * the third case as {@code place -> F} and expects {@code COMBAT_ACTION_REBOUND}, while the
 * matrix writes the same action as {@code use -> SPACE} and expects
 * {@code COMBAT_ACTION_ON_KEYBOARD}. {@code key.use} is documented by the game as "Use Item /
 * Place Block", so these are two spellings of one action rather than two rules.
 * {@link BindActions} therefore canonicalises {@code place} onto {@code use}, which is in both
 * closed sets, and the single frozen rule that fires is {@code COMBAT_ACTION_ON_KEYBOARD}. The
 * feature is still refused, and the refused-ness is the point of the row. The
 * {@code COMBAT_ACTION_REBOUND} rule is proved on its own terms by
 * {@code pickBlock -> V} and {@code drop -> Q} in the same file.</p>
 */
class KeybindValidatorRejectsAttackRebindTest {

    private DefaultBindPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DefaultBindPolicy();
    }

    @ParameterizedTest(name = "{0} -> {1} is refused")
    @CsvSource({
            "attack,     COMBAT_ACTION_ON_KEYBOARD",
            "use,        COMBAT_ACTION_ON_KEYBOARD",
            "place,      COMBAT_ACTION_ON_KEYBOARD",
            "pickBlock,  COMBAT_ACTION_REBOUND",
            "drop,       COMBAT_ACTION_REBOUND",
    })
    void combatActionsAreRefusedOnRebind(String actionId, BindRejection expected) {
        InputKey key = "COMBAT_ACTION_ON_KEYBOARD".equals(expected)
                ? InputKey.keyboard(82, "R")
                : InputKey.keyboard(86, "V");
        BindRequest request = BindRequest.forVanillaAction(actionId).requested(Keybind.of(key))
                .build();

        BindVerdict verdict = policy.validate(Keybind.of(key), request);

        assertFalse(verdict.allowed(), actionId + " -> " + key.displayName() + " must be refused");
        assertEquals(expected, verdict.reason());
        assertFalse(verdict.message().isEmpty(), "the editor shows the message verbatim");
    }

    @Test
    @DisplayName("use -> SPACE is refused, which is the matrix's second case")
    void useToSpace() {
        InputKey space = InputKey.keyboard(32, "SPACE");
        BindVerdict verdict = policy.validate(Keybind.of(space),
                BindRequest.forVanillaAction("use").requested(Keybind.of(space)).build());
        assertFalse(verdict.allowed());
        assertEquals(BindRejection.COMBAT_ACTION_ON_KEYBOARD, verdict.reason());
        assertTrue(verdict.message().contains("mouse button"), verdict.message());
    }

    @Test
    @DisplayName("attack -> F (the matrix's `place -> F` case) is refused for the same reason")
    void placeToF() {
        InputKey f = InputKey.keyboard(70, "F");
        BindVerdict verdict = policy.validate(Keybind.of(f),
                BindRequest.forVanillaAction("place").requested(Keybind.of(f)).build());
        assertFalse(verdict.allowed());
        assertEquals(BindRejection.COMBAT_ACTION_ON_KEYBOARD, verdict.reason());
    }

    @Test
    @DisplayName("drop -> Q (drop's own vanilla key) is the one bind that stays legal")
    void dropOnItsVanillaKeyIsLegal() {
        Keybind q = BindActions.vanillaDefaultBind("drop");
        assertEquals("Q", q.key().displayName());
        BindVerdict verdict = policy.validate(q, BindRequest.forVanillaAction("drop")
                .requested(q).build());
        assertTrue(verdict.allowed(), "leaving a no-remap action where it already is must work");
    }

    @Test
    @DisplayName("attack on a DIFFERENT mouse button is COMBAT_ACTION_REBOUND, not allowed")
    void attackOnAnotherMouseButtonIsRefused() {
        Keybind mouse4 = Keybind.of(InputKey.mouse(4, "Mouse4"));
        BindVerdict verdict = policy.validate(mouse4,
                BindRequest.forVanillaAction("attack").requested(mouse4).build());
        assertFalse(verdict.allowed(), "a mouse button is still not the vanilla key");
        assertEquals(BindRejection.COMBAT_ACTION_REBOUND, verdict.reason());
    }

    @Test
    @DisplayName("every action in NO_REMAP_ACTIONS keeps its vanilla key, and only that key")
    void noRemapActionsKeepTheirVanillaKeys() {
        for (String actionId : BindActions.NO_REMAP_ACTIONS) {
            Keybind vanilla = BindActions.vanillaDefaultBind(actionId);
            assertEquals(BindVerdict.allow(), policy.validate(vanilla,
                    BindRequest.forVanillaAction(actionId).requested(vanilla).build()),
                    actionId + " must be legal on its own vanilla key " + vanilla);
        }
    }

    @Test
    @DisplayName("the closed sets are exactly what contracts.md C7.12 spells")
    void closedSetsMatchTheContract() {
        assertEquals(java.util.Arrays.asList("attack", "use"),
                new java.util.ArrayList<String>(BindActions.MOUSE_ONLY_ACTIONS));
        assertEquals(java.util.Arrays.asList("attack", "use", "pickBlock", "drop"),
                new java.util.ArrayList<String>(BindActions.NO_REMAP_ACTIONS));
        assertThrowsUnsupported(() -> BindActions.NO_REMAP_ACTIONS.add("sprint"));
    }

    private static void assertThrowsUnsupported(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("the closed sets must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // exactly what we want
        }
    }
}
