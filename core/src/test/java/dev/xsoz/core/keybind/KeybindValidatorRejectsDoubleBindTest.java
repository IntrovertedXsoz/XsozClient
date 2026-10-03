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
 * {@code KeybindValidatorRejectsDoubleBindTest} - contracts.md C7.12,
 * {@code docs/rules-matrix.md} 4.
 *
 * <p>{@link BindRejection#DOUBLE_BIND}: the same key is held by a second action. PvPHQ
 * disallows "abnormal keybinding or double-binding"; PvP Land names it outright, "Double Key
 * Binds".</p>
 *
 * <p>The matrix's example is {@code doubleBind -> attack on LMB + mouse4}, i.e. a request for
 * {@code attack} on a key another action already holds. Both the combat case and the ordinary
 * case are asserted here, because a double-bind rule that only applied to combat actions
 * would still leave "Quick Hotkeys" (the matrix's disallowed row 47) unaddressed.</p>
 */
class KeybindValidatorRejectsDoubleBindTest {

    private DefaultBindPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DefaultBindPolicy();
    }

    @Test
    @DisplayName("attack on LMB while another action already holds LMB is refused")
    void attackOnAKeyAnotherActionHolds() {
        Keybind lmb = BindActions.vanillaDefaultBind("attack");
        assertEquals("Mouse1", lmb.key().displayName());

        BindRequest request = BindRequest.forVanillaAction("attack")
                .requested(lmb)
                .bindingsHeldByOtherActions(Collections.singletonList(lmb))
                .build();
        BindVerdict verdict = policy.validate(lmb, request);

        assertFalse(verdict.allowed(), "the same key may not be bound twice, even to its own owner");
        assertEquals(BindRejection.DOUBLE_BIND, verdict.reason());
        assertTrue(verdict.message().contains("already held by another action"), verdict.message());
        assertTrue(verdict.message().contains("Double Key Binds"), verdict.message());
    }

    @Test
    @DisplayName("two module toggles cannot share a key")
    void twoModuleTogglesCannotShareAKey() {
        Keybind b = Keybind.of(InputKey.keyboard(66, "B"));
        BindRequest second = BindRequest.forModuleToggle("hud.keystrokes")
                .requested(b)
                .bindingsHeldByOtherActions(Collections.singletonList(b))
                .build();
        BindVerdict verdict = policy.validate(b, second);
        assertFalse(verdict.allowed());
        assertEquals(BindRejection.DOUBLE_BIND, verdict.reason());

        Keybind c = Keybind.of(InputKey.keyboard(67, "C"));
        BindRequest first = BindRequest.forModuleToggle("hud.totem-counter")
                .requested(c)
                .bindingsHeldByOtherActions(Collections.singletonList(b))
                .build();
        assertEquals(BindVerdict.allow(), policy.validate(c, first),
                "a key held by another action only blocks THAT key, not every rebind");
    }

    @Test
    @DisplayName("the double-bind check runs on the stored set, not on one key")
    void everyHeldKeyIsChecked() {
        Keybind r = Keybind.of(InputKey.keyboard(82, "R"));
        Keybind s = Keybind.of(InputKey.keyboard(83, "S"));
        BindRequest request = BindRequest.forModuleToggle("hud.keystrokes")
                .requested(r)
                .bindingsHeldByOtherActions(Arrays.asList(s))
                .build();
        assertEquals(BindVerdict.allow(), policy.validate(r, request));
        assertEquals(BindRejection.DOUBLE_BIND, policy.validate(s, request).reason());
    }

    @Test
    @DisplayName("a bind that only collides on its modifiers is a different key, not a double bind")
    void modifiersArePartOfTheBindIdentity() {
        Keybind plainB = Keybind.of(InputKey.keyboard(66, "B"));
        Keybind ctrlB = Keybind.of(InputKey.keyboard(66, "B"), ModifierKey.CTRL);
        assertFalse(plainB.equals(ctrlB), "a chord and a bare key are different binds");
        BindRequest request = BindRequest.forModuleToggle("hud.keystrokes")
                .requested(ctrlB)
                .bindingsHeldByOtherActions(Collections.singletonList(plainB))
                .build();
        assertEquals(BindVerdict.allow(), policy.validate(ctrlB, request));
    }

    @Test
    @DisplayName("a double bind is refused for a combat action before the combat rule is consulted")
    void doubleBindPrecedesTheCombatRules() {
        Keybind mouse4 = Keybind.of(InputKey.mouse(4, "Mouse4"));
        BindRequest request = BindRequest.forVanillaAction("attack")
                .requested(mouse4)
                .bindingsHeldByOtherActions(Collections.singletonList(mouse4))
                .build();
        // Both rules would fire; the message a player can act on is the collision.
        assertEquals(BindRejection.DOUBLE_BIND, policy.validate(mouse4, request).reason());

        BindRequest notHeld = BindRequest.forVanillaAction("attack")
                .requested(mouse4).build();
        assertEquals(BindRejection.COMBAT_ACTION_REBOUND,
                policy.validate(mouse4, notHeld).reason(),
                "with no collision, the combat rule is what refuses it");
    }
}
