package dev.xsoz.core.keybind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code KeybindValidatorRejectsNullBindTest} - contracts.md C7.12,
 * {@code docs/rules-matrix.md} 4.
 *
 * <p>{@code nullBind -> attack bound to the null key} is
 * {@link BindRejection#NULL_BIND}. The rule is that a {@code null} or unbound action is
 * refused, which is the one case where the <em>unbound</em> key must not be treated as a
 * valid install: a row in the editor with nothing in it is not a bind, it is a broken
 * config.</p>
 */
class KeybindValidatorRejectsNullBindTest {

    private DefaultBindPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DefaultBindPolicy();
    }

    @Test
    @DisplayName("attack bound to the null key is refused with NULL_BIND")
    void attackOnTheNullKeyIsRefused() {
        BindRequest request = BindRequest.forVanillaAction("attack")
                .requested(Keybind.unbound())
                .build();
        BindVerdict verdict = policy.validate(Keybind.unbound(), request);

        assertFalse(verdict.allowed());
        assertEquals(BindRejection.NULL_BIND, verdict.reason());
        assertTrue(verdict.message().contains("not a bind"), verdict.message());
    }

    @Test
    @DisplayName("a literal null Keybind is refused, not thrown on")
    void literalNullIsRefused() {
        BindRequest request = BindRequest.forVanillaAction("attack")
                .requested(Keybind.unbound()).build();
        BindVerdict verdict = policy.validate(null, request);
        assertFalse(verdict.allowed());
        assertEquals(BindRejection.NULL_BIND, verdict.reason());
    }

    @Test
    @DisplayName("Keybind.unbound() is a real, shared, identifiable value")
    void unboundIsAValue() {
        assertEquals(Keybind.unbound(), Keybind.unbound());
        assertFalse(Keybind.unbound().isBound());
        assertFalse(Keybind.unbound().isSingleKey());
        assertFalse(Keybind.unbound().isMouseButton());
        assertEquals("Keybind[UNBOUND]", Keybind.unbound().toString());
        assertTrue(Keybind.of(InputKey.keyboard(82, "R")).isBound());
    }

    @ParameterizedTest(name = "an unbound {0} is refused")
    @ValueSource(strings = {"hud.totem-counter", "forward", "fullscreen", "hotbar.4"})
    void everyActionRefusesAnUnboundRequest(String actionId) {
        BindRequest request = (actionId.indexOf('.') > 0
                ? BindRequest.forModuleToggle(actionId)
                : BindRequest.forVanillaAction(actionId))
                .requested(Keybind.unbound())
                .build();
        assertEquals(BindRejection.NULL_BIND, policy.validate(Keybind.unbound(), request).reason());
        assertEquals(BindRejection.NULL_BIND, policy.validate(null, request).reason());
    }

    @Test
    @DisplayName("an empty requestedBindings list is a null bind too")
    void emptyRequestIsNullBind() {
        BindRequest request = BindRequest.forVanillaAction("forward")
                .requestedBindings(Collections.<Keybind>emptyList())
                .build();
        assertTrue(request.requestedBindings().isEmpty());
        assertEquals(BindRejection.NULL_BIND, policy.validate(Keybind.unbound(), request).reason());
    }

    @Test
    @DisplayName("a null request is a caller bug and throws; a null bind is a value and is refused")
    void nullRequestThrowsButNullBindDoesNot() {
        try {
            policy.validate(Keybind.of(InputKey.keyboard(82, "R")), null);
            throw new AssertionError("expected an XsozContractException");
        } catch (dev.xsoz.core.XsozContractException expected) {
            assertTrue(expected.getMessage().contains("requires a request"),
                    expected.getMessage());
        }
    }
}
