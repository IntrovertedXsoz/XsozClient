package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract C4.1, C4.2 - the minimal setting types.
 *
 * <p>Two properties carry the weight: <strong>validation throws and never coerces</strong>,
 * and <strong>{@code sensitive} is presentational only</strong>.</p>
 */
class SettingTest {

    @Nested
    @DisplayName("BooleanSetting")
    class Booleans {

        private final BooleanSetting setting = new BooleanSetting(
                "hud.totem-counter.show-carry-count", "Show carry count",
                "Draws the number of totems you are carrying", true, false);

        @Test
        @DisplayName("the declared default is preserved and exposed as a Boolean")
        void defaultIsPreserved() {
            assertEquals(SettingKind.BOOLEAN, setting.kind());
            assertEquals(Boolean.TRUE, setting.defaultValue());
            assertTrue(setting.defaultBoolean());
            assertEquals("hud.totem-counter.show-carry-count", setting.key());
            assertEquals("Show carry count", setting.label());
            assertEquals("Draws the number of totems you are carrying", setting.description());
            assertFalse(setting.sensitive());
        }

        @Test
        @DisplayName("both booleans validate")
        void bothBooleansValidate() {
            setting.validate(Boolean.TRUE);
            setting.validate(Boolean.FALSE);
        }

        @ParameterizedTest(name = "refuses {0}: validate throws, it does not coerce")
        @ValueSource(strings = {"0", "1", "true", "yes", ""})
        void nonBooleansAreRefused(String raw) {
            assertThrows(SettingValidationException.class, () -> setting.validate(raw));
        }

        @Test
        @DisplayName("a missing value is a loader decision, not a value this setting can read")
        void nullIsRefused() {
            SettingValidationException thrown = assertThrows(SettingValidationException.class,
                    () -> setting.validate(null));
            assertTrue(thrown.getMessage().contains("hud.totem-counter.show-carry-count"),
                    thrown.getMessage());
            assertEquals("hud.totem-counter.show-carry-count", thrown.key());
        }

        @Test
        @DisplayName("a numeric 1 is refused even though Java boxes it as a Number")
        void oneIsNotTrue() {
            assertThrows(SettingValidationException.class, () -> setting.validate(Integer.valueOf(1)));
        }
    }

    @Nested
    @DisplayName("DoubleSetting range validation")
    class Doubles {

        private final DoubleSetting setting = new DoubleSetting(
                "perf.sodium-tweaks.render-distance-chunks", "Render distance",
                "Client view distance, clamped to the server's own value",
                8.0d, 2.0d, 32.0d, 1.0d, "chunks", false);

        @Test
        @DisplayName("the range, step and unit are all exposed and named")
        void rangeIsExposed() {
            assertEquals(SettingKind.DOUBLE, setting.kind());
            assertEquals(Double.valueOf(8.0d), setting.defaultValue());
            assertEquals(2.0d, setting.minInclusive());
            assertEquals(32.0d, setting.maxInclusive());
            assertEquals(1.0d, setting.step());
            assertEquals("chunks", setting.unit());
        }

        @Test
        @DisplayName("both bounds are inclusive and in-range doubles validate")
        void inRangeValuesValidate() {
            setting.validate(Double.valueOf(2.0d));
            setting.validate(Double.valueOf(32.0d));
            setting.validate(Double.valueOf(8.0d));
            setting.validate(Integer.valueOf(12));
        }

        @ParameterizedTest(name = "refuses {0}, which is outside [2, 32] chunks")
        @ValueSource(doubles = {1.999d, 0.0d, 32.001d, 128.0d, -4.0d})
        void outOfRangeIsRefused(double raw) {
            assertThrows(SettingValidationException.class, () -> setting.validate(Double.valueOf(raw)));
        }

        @Test
        @DisplayName("NaN and infinity are refused: they are not values, and -1 is a legal value elsewhere")
        void nonFiniteIsRefused() {
            assertThrows(SettingValidationException.class, () -> setting.validate(Double.valueOf(Double.NaN)));
            assertThrows(SettingValidationException.class,
                    () -> setting.validate(Double.valueOf(Double.POSITIVE_INFINITY)));
        }

        @Test
        @DisplayName("a negative percentage is a LEGAL value in this product, so it is legal here too")
        void negativeIsNotASentinel() {
            DoubleSetting attackCooldownPct = new DoubleSetting(
                    "hud.cooldown.percent", "Attack cooldown",
                    "Pre-1.9 has no cooldown, so -1 is a legal reading and not a sentinel",
                    -1.0d, -1.0d, 100.0d, 1.0d, "percent", false);
            attackCooldownPct.validate(Double.valueOf(-1.0d));
            assertEquals(Double.valueOf(-1.0d), attackCooldownPct.defaultValue());
        }

        @Test
        @DisplayName("a numeric setting with no unit does not compile: the constructor throws")
        void unitIsMandatory() {
            assertThrows(XsozContractException.class, () -> new DoubleSetting(
                    "perf.x.value", "Value", "A number with no unit", 1.0d, 0.0d, 2.0d, 0.5d,
                    null, false));
            assertThrows(XsozContractException.class, () -> new DoubleSetting(
                    "perf.x.value", "Value", "A number with no unit", 1.0d, 0.0d, 2.0d, 0.5d,
                    "   ", false));
        }

        @Test
        @DisplayName("a step <= 0 throws at construction, and so does an empty range")
        void constructionValidatesTheRange() {
            assertThrows(XsozContractException.class, () -> new DoubleSetting(
                    "perf.x.value", "Value", "A number", 1.0d, 0.0d, 2.0d, 0.0d, "blocks", false));
            assertThrows(XsozContractException.class, () -> new DoubleSetting(
                    "perf.x.value", "Value", "A number", 1.0d, 0.0d, 2.0d, -1.0d, "blocks", false));
            assertThrows(XsozContractException.class, () -> new DoubleSetting(
                    "perf.x.value", "Value", "A number", 1.0d, 5.0d, 2.0d, 1.0d, "blocks", false));
        }

        @Test
        @DisplayName("a default outside the declared range is refused at construction")
        void defaultMustBeInsideTheRange() {
            assertThrows(XsozContractException.class, () -> new DoubleSetting(
                    "perf.x.value", "Value", "A number", 99.0d, 0.0d, 2.0d, 1.0d, "blocks", false));
        }

        @Test
        @DisplayName("coerce snaps to the step and clamps, which is the LOADER's job (C4.4)")
        void coerceSnapsAndClamps() {
            // min + round((v - min) / step) * step, then clamp. min=2, step=1:
            //   4.6 -> 2 + round(2.6) = 2 + 3 = 5
            //   5.4 -> 2 + round(3.4) = 2 + 3 = 5   (Math.round is floor(x + 0.5), so 3.4 -> 3)
            //   5.6 -> 2 + round(3.6) = 2 + 4 = 6
            assertEquals(5.0d, setting.coerce(4.6d));
            assertEquals(5.0d, setting.coerce(5.4d));
            assertEquals(6.0d, setting.coerce(5.6d));
            assertEquals(2.0d, setting.coerce(0.0d), "clamps up to the minimum");
            assertEquals(32.0d, setting.coerce(1000.0d), "clamps down to the maximum");
            assertEquals(8.0d, setting.coerce(8.0d), "an on-step value is unchanged");
        }

        @Test
        @DisplayName("a fractional step snaps to a fractional grid")
        void fractionalStep() {
            DoubleSetting scale = new DoubleSetting("visual.ui.scale", "UI scale",
                    "Interface scale factor", 1.0d, 0.5d, 3.0d, 0.25d, "ratio", false);
            assertEquals(1.0d, scale.coerce(1.1d));
            assertEquals(1.25d, scale.coerce(1.2d));
            assertEquals(0.5d, scale.coerce(0.0d));
        }
    }

    @Nested
    @DisplayName("the declared shape")
    class Declaration {

        @Test
        @DisplayName("a malformed key is refused, camelCase included")
        void keyGrammar() {
            for (String bad : Arrays.asList("Hud.totem", "hud..totem", ".hud", "hud.", "1hud",
                    "hud.totemCounter", "hud.totem counter", "")) {
                assertThrows(XsozContractException.class,
                        () -> new BooleanSetting(bad, "Label", "Description", true, false),
                        "\"" + bad + "\" must be refused");
            }
        }

        @ParameterizedTest(name = "{0} is a valid setting key")
        @ValueSource(strings = {"hud.totem-counter", "latency.crystal-release", "a", "a.b.c.d",
                "mod2.setting_3"})
        void validKeys(String key) {
            assertEquals(key, new BooleanSetting(key, "Label", "Description", false, false).key());
        }

        @Test
        @DisplayName("a blank label, a trailing colon or a trailing period is refused")
        void labelAndDescriptionRules() {
            assertThrows(XsozContractException.class,
                    () -> new BooleanSetting("a.b", "   ", "Description", false, false));
            assertThrows(XsozContractException.class,
                    () -> new BooleanSetting("a.b", "Label:", "Description", false, false));
            assertThrows(XsozContractException.class,
                    () -> new BooleanSetting("a.b", "Label", "Description.", false, false));
            assertThrows(XsozContractException.class,
                    () -> new BooleanSetting("a.b", "Label", "   ", false, false));
        }

        @Test
        @DisplayName("SettingKind is the contract's closed set of eight")
        void settingKindIsClosed() {
            assertEquals(8, SettingKind.values().length);
            assertEquals("BOOLEAN", SettingKind.values()[0].name());
            assertEquals("KEYBIND", SettingKind.values()[6].name());
            assertThrows(IllegalArgumentException.class, () -> SettingKind.valueOf("FLOAT"));
        }

        @Test
        @DisplayName("a Setting has no setters, and its fields are final")
        void immutability() throws Exception {
            for (java.lang.reflect.Method method : Setting.class.getMethods()) {
                assertFalse(method.getName().startsWith("set"),
                        "Setting must not expose a mutator, found " + method);
            }
            for (java.lang.reflect.Field field : Setting.class.getDeclaredFields()) {
                if (!field.isSynthetic()) {
                    assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()),
                            "Setting field " + field.getName() + " must be final");
                }
            }
        }
    }

    @Nested
    @DisplayName("sensitive is PRESENTATIONAL ONLY")
    class SensitiveIsPresentational {

        private final BooleanSetting plain = new BooleanSetting(
                "hud.totem-counter.show-carry-count", "Show carry count",
                "Draws the number of totems you are carrying", true, false);
        private final BooleanSetting masked = new BooleanSetting(
                "hud.totem-counter.show-carry-count", "Show carry count",
                "Draws the number of totems you are carrying", true, true);

        @Test
        @DisplayName("the flag is readable, and it is the only difference between the twins")
        void theFlagIsReadable() {
            assertFalse(plain.sensitive());
            assertTrue(masked.sensitive());
        }

        @Test
        @DisplayName("validation is byte-identical: the flag reaches no validation path")
        void validationIsIdentical() {
            for (Object candidate : new Object[] {Boolean.TRUE, Boolean.FALSE, null, "true", 1,
                    Integer.valueOf(0)}) {
                Class<? extends Throwable> plainFailure = failureOf(plain, candidate);
                Class<? extends Throwable> maskedFailure = failureOf(masked, candidate);
                assertEquals(plainFailure, maskedFailure,
                        "sensitive changed validation for " + candidate);
                if (plainFailure == null) {
                    continue;
                }
                assertEquals(failureMessageOf(plain, candidate),
                        failureMessageOf(masked, candidate),
                        "sensitive changed the message for " + candidate);
            }
        }

        @Test
        @DisplayName("the default is identical, and a numeric range is identical")
        void defaultsAndRangesAreIdentical() {
            assertEquals(plain.defaultValue(), masked.defaultValue());
            DoubleSetting a = new DoubleSetting("a.b", "B", "C", 4.0d, 0.0d, 8.0d, 0.5d, "blocks", false);
            DoubleSetting b = new DoubleSetting("a.b", "B", "C", 4.0d, 0.0d, 8.0d, 0.5d, "blocks", true);
            assertEquals(a.defaultValue(), b.defaultValue());
            assertEquals(a.coerce(3.3d), b.coerce(3.3d));
            assertEquals(failureOf(a, 99.0d), failureOf(b, 99.0d));
        }

        @Test
        @DisplayName("the flag does not change a setting's identity, so a refactor is not a compliance change")
        void identityIsUnaffected() {
            assertEquals(plain, masked);
            assertEquals(plain.hashCode(), masked.hashCode());
            assertNotEquals(plain.kind(), SettingKind.STRING);
        }

        @Test
        @DisplayName("toString is the ONE place the flag is surfaced")
        void toStringIsTheOnlyPresentation() {
            assertFalse(plain.toString().contains("sensitive"));
            assertTrue(masked.toString().contains("sensitive"));
        }

        private Class<? extends Throwable> failureOf(Setting setting, Object candidate) {
            try {
                setting.validate(candidate);
                return null;
            } catch (Throwable t) {
                return t.getClass();
            }
        }

        private String failureMessageOf(Setting setting, Object candidate) {
            try {
                setting.validate(candidate);
                return null;
            } catch (RuntimeException e) {
                return e.getMessage();
            }
        }
    }
}
