package dev.xsoz.core.keybind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code KeybindValidatorRejectsMultiBindTest} - contracts.md C7.12,
 * {@code docs/rules-matrix.md} 4.
 *
 * <p>Two cases, both named by the contract: {@code multiBind -> [R, mouse4]} is
 * {@link BindRejection#MULTI_KEY_BIND}, and <em>any combat action bound to a chord</em> is
 * {@link BindRejection#KEY_SEQUENCE_BIND}.</p>
 *
 * <p><strong>The data-model point.</strong> {@link Keybind} holds exactly one
 * {@link InputKey}, so there is <em>no representation of a multi-key bind</em>: the macro
 * has nowhere to live, and the refusal is of the request rather than of a state. That is why
 * the second case below is a chord - a key plus modifiers - rather than two keys.</p>
 */
class KeybindValidatorRejectsMultiBindTest {

    private DefaultBindPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DefaultBindPolicy();
    }

    @Test
    @DisplayName("multiBind -> [R, mouse4] is refused with MULTI_KEY_BIND")
    void twoKeysForOneActionIsRefused() {
        Keybind r = Keybind.of(InputKey.keyboard(82, "R"));
        Keybind mouse4 = Keybind.of(InputKey.mouse(4, "Mouse4"));
        List<Keybind> requested = Arrays.asList(r, mouse4);

        BindRequest request = BindRequest.forVanillaAction("hud.keystrokes")
                .requestedBindings(requested)
                .build();
        BindVerdict verdict = policy.validate(r, request);

        assertFalse(verdict.allowed());
        assertEquals(BindRejection.MULTI_KEY_BIND, verdict.reason());
        assertTrue(verdict.message().contains("One action, one key"), verdict.message());
        assertTrue(verdict.message().contains("Double Key Binds"), verdict.message());
    }

    @Test
    @DisplayName("three keys for one action is the same refusal")
    void threeKeysIsRefusedToo() {
        List<Keybind> requested = Arrays.asList(
                Keybind.of(InputKey.keyboard(82, "R")),
                Keybind.of(InputKey.mouse(4, "Mouse4")),
                Keybind.of(InputKey.keyboard(83, "S")));
        BindRequest request = BindRequest.forModuleToggle("hud.keystrokes")
                .requestedBindings(requested).build();
        assertEquals(BindRejection.MULTI_KEY_BIND,
                policy.validate(requested.get(0), request).reason());
    }

    @Test
    @DisplayName("a stored state that already holds two distinct keys is caught on the next edit")
    void storedMultiKeyStateIsCaught() {
        Keybind requested = Keybind.of(InputKey.keyboard(82, "R"));
        BindRequest request = BindRequest.forModuleToggle("hud.keystrokes")
                .requested(requested)
                .existingBindingsForThisAction(Arrays.asList(
                        Keybind.of(InputKey.keyboard(82, "R")),
                        Keybind.of(InputKey.keyboard(83, "S"))))
                .build();
        BindVerdict verdict = policy.validate(requested, request);
        assertFalse(verdict.allowed());
        assertEquals(BindRejection.MULTI_KEY_BIND, verdict.reason());
        assertTrue(verdict.message().contains("stored state"), verdict.message());
    }

    @Test
    @DisplayName("the same key listed twice is one key, not a sequence")
    void duplicateKeysCollapse() {
        Keybind r = Keybind.of(InputKey.keyboard(82, "R"));
        BindRequest request = BindRequest.forModuleToggle("hud.keystrokes")
                .requested(r)
                .existingBindingsForThisAction(Arrays.asList(r, r))
                .build();
        assertEquals(BindVerdict.allow(), policy.validate(r, request),
                "the same bind twice is idempotent, not a macro");
    }

    @Test
    @DisplayName("attack + SHIFT is KEY_SEQUENCE_BIND: a macro on an attack")
    void chordOnACombatActionIsRefused() {
        Keybind chord = Keybind.of(InputKey.keyboard(82, "R"), ModifierKey.SHIFT);
        assertFalse(chord.isSingleKey());
        BindVerdict verdict = policy.validate(chord,
                BindRequest.forVanillaAction("attack").requested(chord).build());
        assertFalse(verdict.allowed());
        assertEquals(BindRejection.KEY_SEQUENCE_BIND, verdict.reason());
        assertTrue(verdict.message().contains("cannot take a chord"), verdict.message());
    }

    @Test
    @DisplayName("the chord rule fires before the keyboard rule, because a macro is the worse problem")
    void chordIsReportedAsASequenceNotAsAKeyboardBinding() {
        Keybind attackShift = Keybind.of(InputKey.keyboard(82, "R"), ModifierKey.SHIFT);
        Keybind attackMouse4Ctrl = Keybind.of(InputKey.mouse(4, "Mouse4"), ModifierKey.CTRL);
        for (Keybind chord : Arrays.asList(attackShift, attackMouse4Ctrl)) {
            assertEquals(BindRejection.KEY_SEQUENCE_BIND, policy.validate(chord,
                    BindRequest.forVanillaAction("attack").requested(chord).build()).reason(),
                    chord + " must be reported as a key sequence");
        }
    }

    @Test
    @DisplayName("a chord on a NON-combat action is allowed: Ctrl+S is an ordinary vanilla hotkey")
    void chordOnANonCombatActionIsAllowed() {
        Keybind ctrlS = Keybind.of(InputKey.keyboard(83, "S"), ModifierKey.CTRL);
        assertEquals(BindVerdict.allow(), policy.validate(ctrlS,
                BindRequest.forModuleToggle("hud.keystrokes").requested(ctrlS).build()));
        Keybind ctrlR = Keybind.of(InputKey.keyboard(82, "R"), ModifierKey.CTRL, ModifierKey.SHIFT);
        assertEquals(BindVerdict.allow(), policy.validate(ctrlR,
                BindRequest.forVanillaAction("screenshot").requested(ctrlR).build()));
    }

    @Test
    @DisplayName("Keybind really cannot hold two keys: the type makes a macro unrepresentable")
    void keybindHoldsExactlyOneKey() {
        assertEquals(3, instanceFieldCount(InputKey.class),
                "InputKey is (kind, code, displayName) - there is no second key slot");
        assertEquals(2, instanceFieldCount(Keybind.class),
                "Keybind is (key, modifiers) - exactly one key, or none when unbound");
        assertThrowsOn(() -> Keybind.of(null));
        assertTrue(Keybind.unbound().key() == null);
        assertEquals(Collections.emptySet(), Keybind.unbound().modifiers());
    }

    private static int instanceFieldCount(Class<?> type) {
        int count = 0;
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (!field.isSynthetic() && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                count++;
            }
        }
        return count;
    }

    private static void assertThrowsOn(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("expected a NullPointerException");
        } catch (NullPointerException expected) {
            // exactly what we want
        }
    }
}
