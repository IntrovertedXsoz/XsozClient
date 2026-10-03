package dev.xsoz.core.keybind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code KeybindValidatorRejectsCombatRemapTest} - contracts.md C7.12,
 * {@code docs/rules-matrix.md} 4.
 *
 * <p>The matrix's last combat case: <strong>a screen-wide search-and-replace over every
 * vanilla keybind</strong> - the "Controlling" class. Our keybind editor may rebind
 * <em>non-combat</em> keys freely; the validator is the hard boundary, and this test is the
 * proof that the boundary holds when a remap is applied in bulk rather than one row at a
 * time.</p>
 */
class KeybindValidatorRejectsCombatRemapTest {

    private DefaultBindPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DefaultBindPolicy();
    }

    @Test
    @DisplayName("a screen-wide sweep refuses every combat action and allows every non-combat one")
    void screenWideRemapRefusesCombatAndAllowsTheRest() {
        // The sweep: every vanilla action, moved to a fresh key.
        String[] swept = BindActions.vanillaActions().toArray(new String[0]);

        int refused = 0;
        int allowed = 0;
        for (String actionId : swept) {
            Keybind moved = Keybind.of(InputKey.keyboard(90, "Z"));
            BindVerdict verdict = policy.validate(moved,
                    BindRequest.forVanillaAction(actionId).requested(moved).build());

            if (BindActions.isCombatAction(actionId)) {
                assertFalse(verdict.allowed(),
                        actionId + " is a combat action and a wholesale remap moved it to " + moved);
                BindRejection expected = BindActions.isMouseOnlyAction(actionId)
                        ? BindRejection.COMBAT_ACTION_ON_KEYBOARD
                        : BindRejection.COMBAT_ACTION_REBOUND;
                assertEquals(expected, verdict.reason(),
                        actionId + " must be reported as " + expected);
                refused++;
            } else {
                assertTrue(verdict.allowed(),
                        actionId + " is non-combat and may be rebound freely, but was refused: "
                                + verdict);
                allowed++;
            }
        }

        assertEquals(4, refused, "attack, use, pickBlock and drop are the four no-remap actions");
        assertEquals(swept.length - 4, allowed, "every other vanilla action is freely rebindable");
        assertTrue(allowed > refused, "a wholesale remap is mostly legal; the four rows are the rule");
    }

    @Test
    @DisplayName("a sweep that also renames the action is refused as UNKNOWN_ACTION first")
    void sweepOverAnUnknownActionIsRefused() {
        Keybind moved = Keybind.of(InputKey.keyboard(90, "Z"));
        BindVerdict verdict = policy.validate(moved, BindRequest.forVanillaAction("key.attack")
                .requested(moved).build());
        assertFalse(verdict.allowed());
        assertEquals(BindRejection.UNKNOWN_ACTION, verdict.reason());
    }

    @Test
    @DisplayName("a sweep that tries to move attack off the mouse entirely is refused both ways")
    void sweepMovingAttackToTheKeyboardIsRefused() {
        Keybind z = Keybind.of(InputKey.keyboard(90, "Z"));
        BindVerdict verdict = policy.validate(z, BindRequest.forVanillaAction("attack")
                .requested(z).build());
        assertFalse(verdict.allowed());
        assertEquals(BindRejection.COMBAT_ACTION_ON_KEYBOARD, verdict.reason());
        assertTrue(verdict.message().contains("mouse button"), verdict.message());
    }

    @Test
    @DisplayName("the whitelist exists: fullscreen -> F11 in the same sweep is allowed")
    void nonCombatVanillaRowInTheSameSweepIsAllowed() {
        Keybind f11 = Keybind.of(InputKey.keyboard(344, "F11"));
        assertEquals(BindVerdict.allow(), policy.validate(f11, BindRequest.forVanillaAction("fullscreen")
                .requested(f11).build()));
    }

    @Test
    @DisplayName("a remap applied to a hotbar slot is allowed: the closed set has no rule for it")
    void hotbarSlotRemapIsAllowedAndFlagged() {
        Keybind key4 = Keybind.of(InputKey.keyboard(52, "4"));
        BindRequest request = BindRequest.forVanillaAction("hotbar.4")
                .requested(key4).build();
        assertTrue(request.isMinecraftHotbarSlotAction());
        assertEquals(BindVerdict.allow(), policy.validate(key4, request));
    }

    @Test
    @DisplayName("a sweep can be applied to our own module toggles without touching a combat row")
    void moduleTogglesAreFreelyRebindable() {
        String[] toggles = {"hud.totem-counter", "hud.keystrokes", "latency.crystal-release",
                "perf.sodium-tweaks", "visual.crosshair", "qol.tooltips", "coach.gate-overlay"};
        for (String toggle : Arrays.asList(toggles)) {
            assertTrue(BindActions.isKnown(toggle), toggle + " must be in the closed registry");
            Keybind b = Keybind.of(InputKey.keyboard(66, "B"));
            assertEquals(BindVerdict.allow(), policy.validate(b,
                    BindRequest.forModuleToggle(toggle).requested(b).build()),
                    toggle + " is not a combat action and may be rebound");
        }
    }

    @Test
    @DisplayName("bindingsHeldByOtherActions is consulted on every row, not only combat rows")
    void doubleBindIsCheckedForEveryAction() {
        Keybind b = Keybind.of(InputKey.keyboard(66, "B"));
        BindRequest taken = BindRequest.forModuleToggle("hud.keystrokes")
                .requested(b)
                .bindingsHeldByOtherActions(Collections.singletonList(b))
                .build();
        assertEquals(BindRejection.DOUBLE_BIND,
                policy.validate(b, taken).reason());
    }
}
