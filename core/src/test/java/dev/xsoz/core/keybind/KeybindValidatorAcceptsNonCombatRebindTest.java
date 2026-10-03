package dev.xsoz.core.keybind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code KeybindValidatorRejectsUnknownActionTest} and the positive half of the policy.
 *
 * <p><strong>A validator that rejects everything is not a validator</strong>
 * ({@code docs/rules-matrix.md} 4), so the control cases matter as much as the refusals: a
 * module toggle to any key, {@code key.forward} to W, {@code fullscreen} to F11, and a
 * sprint rebind - the example the user named - are all ALLOWED.</p>
 */
class KeybindValidatorAcceptsNonCombatRebindTest {

    private DefaultBindPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DefaultBindPolicy();
    }

    @ParameterizedTest(name = "a legitimate vanilla or module rebind is ALLOWED: {0} -> {1}")
    @CsvSource({
            "hud.totem-counter, 66",  // B
            "forward,           87",  // W
            "sprint,            344", // RSHIFT
            "fullscreen,        344", // F11
            "jump,              32",  // SPACE
            "inventory,         69",  // E
            "visual.crosshair,  66",  // B
            "qol.tooltips,      66",  // B
            "coach.gate-overlay, 66", // B
    })
    void legitimateRebindsAreAllowed(String actionId, int keyCode) {
        Keybind bind = Keybind.of(InputKey.keyboard(keyCode, "Key" + keyCode));
        BindRequest request = (actionId.indexOf('.') > 0
                ? BindRequest.forModuleToggle(actionId)
                : BindRequest.forVanillaAction(actionId))
                .requested(bind)
                .build();
        BindVerdict verdict = policy.validate(bind, request);

        assertTrue(verdict.allowed(), actionId + " -> " + bind + " must be allowed, got: " + verdict);
        assertNull(verdict.reason(), "reason is null exactly when allowed");
        assertFalse(verdict.message().isEmpty());
    }

    @Test
    @DisplayName("the user's own example: rebinding sprint to a different key is allowed")
    void sprintToADifferentKeyIsAllowed() {
        Keybind sprint = Keybind.of(InputKey.keyboard(82, "R"));
        assertTrue(policy.validate(sprint,
                BindRequest.forVanillaAction("sprint").requested(sprint).build()).allowed());
        Keybind sprintOnMouse5 = Keybind.of(InputKey.mouse(5, "Mouse5"));
        assertTrue(policy.validate(sprintOnMouse5,
                BindRequest.forVanillaAction("sprint").requested(sprintOnMouse5).build()).allowed());
    }

    @Test
    @DisplayName("hud.totem-counter -> B is ALLOWED, the matrix's named control case")
    void moduleToggleToBIsAllowed() {
        Keybind b = Keybind.of(InputKey.keyboard(66, "B"));
        assertEquals(BindVerdict.allow(), policy.validate(b, BindRequest.forModuleToggle(
                "hud.totem-counter").requested(b).build()));
    }

    @Test
    @DisplayName("key.forward -> W and fullscreen -> F11 are ALLOWED, untouched")
    void untouchedVanillaKeysAreAllowed() {
        Keybind w = Keybind.of(InputKey.keyboard(87, "W"));
        assertEquals(BindVerdict.allow(), policy.validate(w, BindRequest.forVanillaAction("forward")
                .requested(w).build()));
        Keybind f11 = Keybind.of(InputKey.keyboard(344, "F11"));
        assertEquals(BindVerdict.allow(), policy.validate(f11, BindRequest.forVanillaAction("fullscreen")
                .requested(f11).build()));
    }

    @Test
    @DisplayName("a module toggle is refused only for the same reasons a vanilla one is")
    void moduleTogglesAreHeldToTheSameNonCombatRules() {
        Keybind b = Keybind.of(InputKey.keyboard(66, "B"));
        assertEquals(BindRejection.NULL_BIND, policy.validate(Keybind.unbound(),
                BindRequest.forModuleToggle("hud.keystrokes").requested(Keybind.unbound()).build())
                .reason());
        assertEquals(BindRejection.DOUBLE_BIND, policy.validate(b, BindRequest.forModuleToggle(
                "hud.keystrokes").requested(b)
                .bindingsHeldByOtherActions(java.util.Collections.singletonList(b)).build()).reason());
        assertEquals(BindRejection.MULTI_KEY_BIND, policy.validate(b, BindRequest.forModuleToggle(
                "hud.keystrokes").requestedBindings(java.util.Arrays.asList(
                b, Keybind.of(InputKey.keyboard(67, "C")))).build()).reason());
    }

    // ---- UNKNOWN_ACTION: the closed registry is the reason the policy can be trusted ------------

    @ParameterizedTest(name = "\"{0}\" is not in the closed action registry and is refused")
    @ValueSource(strings = {
            "not-a-real-action",
            "key.attack",
            "hud.totemCounter",   // camelCase: the C7.7 grammar rejects it
            "unknownns.thing",    // a namespace we do not own
            "sprint-turbo",       // a module id in no namespace at all
    })
    void unknownActionsAreRefused(String actionId) {
        Keybind b = Keybind.of(InputKey.keyboard(66, "B"));
        BindRequest request = BindRequest.forVanillaAction(actionId).requested(b).build();
        BindVerdict verdict = policy.validate(b, request);
        assertFalse(verdict.allowed(), actionId + " must not be bindable");
        assertEquals(BindRejection.UNKNOWN_ACTION, verdict.reason());
    }

    @Test
    @DisplayName("sneak and sprint are ordinary vanilla actions, and are freely rebindable")
    void otherVanillaActionsAreKnown() {
        for (String actionId : new String[] {"sneak", "sprint", "swapOffhand", "playerList"}) {
            Keybind b = Keybind.of(InputKey.keyboard(66, "B"));
            assertTrue(BindActions.isKnown(actionId), actionId + " is a vanilla action");
            assertEquals(BindVerdict.allow(), policy.validate(b,
                    BindRequest.forVanillaAction(actionId).requested(b).build()), actionId);
        }
    }

    @Test
    @DisplayName("UNKNOWN_ACTION is checked first: combat rules cannot be applied to an unknown id")
    void unknownActionBeatsTheCombatRules() {
        // "attack" with a modifier would be KEY_SEQUENCE_BIND, but the id is not "attack"
        // after canonicalisation of a genuinely unknown spelling, so UNKNOWN_ACTION wins.
        Keybind chord = Keybind.of(InputKey.keyboard(82, "R"), ModifierKey.SHIFT);
        BindRequest request = BindRequest.forVanillaAction("attack2").requested(chord).build();
        assertEquals(BindRejection.UNKNOWN_ACTION, policy.validate(chord, request).reason());
    }

    @Test
    @DisplayName("BindRejection is the closed seven, in the contract's order")
    void rejectionEnumIsClosed() {
        assertEquals(7, BindRejection.values().length);
        assertEquals("NULL_BIND", BindRejection.values()[0].name());
        assertEquals("MULTI_KEY_BIND", BindRejection.values()[1].name());
        assertEquals("DOUBLE_BIND", BindRejection.values()[2].name());
        assertEquals("COMBAT_ACTION_ON_KEYBOARD", BindRejection.values()[3].name());
        assertEquals("COMBAT_ACTION_REBOUND", BindRejection.values()[4].name());
        assertEquals("KEY_SEQUENCE_BIND", BindRejection.values()[5].name());
        assertEquals("UNKNOWN_ACTION", BindRejection.values()[6].name());
    }

    @Test
    @DisplayName("the policy is the only implementation and it is stateless")
    void policyIsStateless() {
        Keybind b = Keybind.of(InputKey.keyboard(66, "B"));
        BindRequest request = BindRequest.forModuleToggle("hud.totem-counter").requested(b).build();
        for (int i = 0; i < 5; i++) {
            assertEquals(BindVerdict.allow(), policy.validate(b, request));
        }
    }
}
