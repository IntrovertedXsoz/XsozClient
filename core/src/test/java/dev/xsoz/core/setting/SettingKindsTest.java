package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;
import dev.xsoz.core.keybind.BindPolicy;
import dev.xsoz.core.keybind.BindRejection;
import dev.xsoz.core.keybind.BindRequest;
import dev.xsoz.core.keybind.BindVerdict;
import dev.xsoz.core.keybind.InputKey;
import dev.xsoz.core.keybind.Keybind;
import dev.xsoz.core.keybind.ModifierKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract C4.2 - the six remaining concrete setting types, each one's validation,
 * coercion, default and serialisation round-trip.
 */
class SettingKindsTest {

    private enum Style { COMPACT, ROOMY, DENSE }

    @Nested
    @DisplayName("IntSetting")
    class Ints {

        private final IntSetting setting = new IntSetting(
                "latency.crystal-release.release-timeout-millis", "Release timeout",
                "How long a released crystal stays predicted before the prediction is dropped",
                1500, 0, 5000, 50, "millis", false);

        @Test
        @DisplayName("range, step and unit are exposed and named, per C4.2")
        void shape() {
            assertEquals(SettingKind.INT, setting.kind());
            assertEquals(Integer.valueOf(1500), setting.defaultValue());
            assertEquals(1500, setting.defaultInt());
            assertEquals(0, setting.minInclusive());
            assertEquals(5000, setting.maxInclusive());
            assertEquals(50, setting.step());
            assertEquals("millis", setting.unit());
        }

        @Test
        @DisplayName("a whole number inside the range validates; both bounds are inclusive")
        void inRangeValidates() {
            setting.validate(Integer.valueOf(0));
            setting.validate(Integer.valueOf(5000));
            setting.validate(Integer.valueOf(1500));
            setting.validate(Long.valueOf(1500L));
            setting.validate(Double.valueOf(1500.0d));
        }

        @Test
        @DisplayName("a fractional value is REFUSED, not rounded: the declared kind is INT")
        void fractionalIsRefused() {
            SettingValidationException thrown = assertThrows(SettingValidationException.class,
                    () -> setting.validate(Double.valueOf(1500.5d)));
            assertTrue(thrown.getMessage().contains("whole number"), thrown.getMessage());
        }

        @ParameterizedTest(name = "refuses {0}: out of [0, 5000] millis")
        @ValueSource(ints = {-1, 5001, Integer.MIN_VALUE, Integer.MAX_VALUE})
        void outOfRangeIsRefused(int raw) {
            assertThrows(SettingValidationException.class,
                    () -> setting.validate(Integer.valueOf(raw)));
        }

        @Test
        @DisplayName("step <= 0 throws at construction, and so does an empty range or a default outside it")
        void constructionValidates() {
            assertThrows(XsozContractException.class, () -> new IntSetting(
                    "a.b", "B", "C", 1, 0, 10, 0, "ticks", false));
            assertThrows(XsozContractException.class, () -> new IntSetting(
                    "a.b", "B", "C", 1, 0, 10, -5, "ticks", false));
            assertThrows(XsozContractException.class, () -> new IntSetting(
                    "a.b", "B", "C", 1, 10, 0, 1, "ticks", false));
            assertThrows(XsozContractException.class, () -> new IntSetting(
                    "a.b", "B", "C", 99, 0, 10, 1, "ticks", false));
            assertThrows(XsozContractException.class, () -> new IntSetting(
                    "a.b", "B", "C", 1, 0, 10, 1, null, false));
        }

        @Test
        @DisplayName("coerce snaps to the step then clamps, in long arithmetic")
        void coerceSnapsAndClamps() {
            // min 0, step 50: 1512 -> round(30.24) = 30 -> 1500; -100 -> clamps to 0
            assertEquals(1500, setting.coerce(1512));
            assertEquals(1550, setting.coerce(1525));
            assertEquals(0, setting.coerce(-100));
            assertEquals(5000, setting.coerce(999999));
            assertEquals(1500, setting.coerce(1500));
        }

        @Test
        @DisplayName("coerce does not overflow across the whole int domain")
        void coerceDoesNotOverflow() {
            IntSetting huge = new IntSetting("a.b.span", "Span", "A wide range",
                    0, Integer.MIN_VALUE, Integer.MAX_VALUE, 1, "ticks", false);
            assertEquals(Integer.MAX_VALUE, huge.coerce(Integer.MAX_VALUE));
            assertEquals(Integer.MIN_VALUE, huge.coerce(Integer.MIN_VALUE));
        }
    }

    @Nested
    @DisplayName("EnumSetting")
    class Enums {

        private final EnumSetting<Style> setting = new EnumSetting<>(
                "hud.totem-counter.style", "Style", "How the totem readout is laid out",
                Style.COMPACT, Style.class, null, false);

        @Test
        @DisplayName("the kind, default and permitted values are exposed in DECLARATION order")
        void shape() {
            assertEquals(SettingKind.ENUM, setting.kind());
            assertEquals(Style.COMPACT, setting.defaultValue());
            assertEquals(Style.class, setting.type());
            assertEquals(Arrays.asList(Style.COMPACT, Style.ROOMY, Style.DENSE),
                    setting.permittedValues());
        }

        @Test
        @DisplayName("serialised by NAME, never by ordinal: a reorder must not move a saved value")
        void serialisedByNameNotOrdinal() {
            for (Style value : Style.values()) {
                EnumSetting<Style> other = new EnumSetting<>(
                        "hud.totem-counter.style", "Style", "How the totem readout is laid out",
                        value, Style.class, null, false);
                assertEquals(Optional.of(value), other.byName(other.defaultValue().name()),
                        "name() must resolve back to the same constant");
            }
            assertEquals("DENSE", Style.DENSE.name());
            assertEquals(0, Style.COMPACT.ordinal(),
                    "COMPACT is ordinal 0, which is exactly why ordinals are not persisted");
        }

        @Test
        @DisplayName("a name that names no constant resolves to empty, which is the loader's reset path")
        void unknownNameIsEmpty() {
            assertFalse(setting.byName("FANCY").isPresent());
            assertFalse(setting.byName("compact").isPresent(), "byName is case-sensitive: name() is exact");
            assertFalse(setting.byName(null).isPresent());
        }

        @Test
        @DisplayName("validate accepts the constant, not its name and not an ordinal")
        void validateAcceptsTheConstant() {
            setting.validate(Style.DENSE);
            assertThrows(SettingValidationException.class, () -> setting.validate("DENSE"));
            assertThrows(SettingValidationException.class, () -> setting.validate(Integer.valueOf(2)));
            assertThrows(SettingValidationException.class, () -> setting.validate(null));
        }

        @Test
        @DisplayName("a null unit is legal for an enum; a blank one is not")
        void unitMayBeNull() {
            assertEquals(null, setting.unit());
            assertEquals("percent", new EnumSetting<>(
                    "a.b.mode", "Mode", "A mode", Style.ROOMY, Style.class, "percent", false).unit());
            assertThrows(XsozContractException.class, () -> new EnumSetting<>(
                    "a.b.mode", "Mode", "A mode", Style.ROOMY, Style.class, "   ", false));
        }
    }

    @Nested
    @DisplayName("StringSetting")
    class Strings {

        private final StringSetting setting = new StringSetting(
                "hud.totem-counter.label-prefix", "Label prefix", "Prefix drawn before the readout",
                "TOTEMS", 8, Pattern.compile("[A-Z ]+"), false);

        @Test
        @DisplayName("maxLength is in CODE POINTS, so a name with an emoji is not half-allowed")
        void maxLengthIsCodePoints() {
            StringSetting wide = new StringSetting("a.b.wide", "Wide", "A wide field",
                    "", 4, null, false);
            // U+1F600 is a surrogate PAIR: two char()s, one code point.
            String fourEmoji = new StringBuilder().appendCodePoint(0x1F600).appendCodePoint(0x1F601)
                    .appendCodePoint(0x1F602).appendCodePoint(0x1F603).toString();
            assertEquals(8, fourEmoji.length());
            assertEquals(4, fourEmoji.codePointCount(0, fourEmoji.length()));
            wide.validate(fourEmoji);
            String fiveEmoji = fourEmoji + new String(Character.toChars(0x1F604));
            assertThrows(SettingValidationException.class, () -> wide.validate(fiveEmoji));
        }

        @Test
        @DisplayName("allowedChars is matched against the WHOLE string, not a substring")
        void patternMatchesTheWholeString() {
            setting.validate("TOTEMS");
            assertThrows(SettingValidationException.class, () -> setting.validate("TOTEMS 42"),
                    "a substring match would have accepted this, which is not what allowed characters means");
            assertThrows(SettingValidationException.class, () -> setting.validate("totems"));
        }

        @Test
        @DisplayName("a null pattern means unrestricted, and is documented as such")
        void nullPatternIsUnrestricted() {
            StringSetting free = new StringSetting("a.b.free", "Free", "Anything goes",
                    "anything at all", 32, null, false);
            free.validate("anything at all");
            free.validate("");
            assertEquals(null, free.allowedChars());
        }

        @Test
        @DisplayName("truncation lands on a code-point boundary, never mid-surrogate")
        void truncationIsCodePointSafe() {
            StringSetting wide = new StringSetting("a.b.wide", "Wide", "A wide field", "", 2, null, false);
            String threeEmoji = new StringBuilder().appendCodePoint(0x1F600).appendCodePoint(0x1F601)
                    .appendCodePoint(0x1F602).toString();
            String truncated = wide.truncateToMaxLength(threeEmoji);
            assertEquals(2, truncated.codePointCount(0, truncated.length()));
            assertEquals(4, truncated.length(), "two emoji are four UTF-16 units, and both survived");
            wide.validate(truncated);
        }

        @Test
        @DisplayName("a non-positive maxLength is refused: chat text and server names must be bounded")
        void maxLengthIsMandatory() {
            assertThrows(XsozContractException.class,
                    () -> new StringSetting("a.b", "B", "C", "", 0, null, false));
            assertThrows(XsozContractException.class,
                    () -> new StringSetting("a.b", "B", "C", "", -1, null, false));
            assertThrows(XsozContractException.class,
                    () -> new StringSetting("a.b", "B", "C", "too long for the cap", 4, null, false));
        }

        @Test
        @DisplayName("the default is validated at construction, so a default cannot violate its own rules")
        void defaultIsValidated() {
            assertThrows(SettingValidationException.class,
                    () -> new StringSetting("a.b", "B", "C", "lower", 8, Pattern.compile("[A-Z]+"), false));
        }
    }

    @Nested
    @DisplayName("ColorSetting")
    class Colours {

        @Test
        @DisplayName("the value is packed 0xAARRGGBB and the unit is named")
        void shape() {
            ColorSetting opaque = new ColorSetting("hud.totem-counter.colour", "Colour",
                    "The readout colour", 0xFFFF8000, false, false);
            assertEquals(SettingKind.COLOR, opaque.kind());
            assertEquals(Integer.valueOf(0xFFFF8000), opaque.defaultValue());
            assertEquals(0xFFFF8000, opaque.defaultArgb());
            assertFalse(opaque.allowAlpha());
            assertEquals("hex #AARRGGBB", opaque.unit());
            assertEquals(0xFF, ColorSetting.alphaOf(0xFFFF8000));
            assertEquals(0xFF, ColorSetting.redOf(0xFFFF8000));
            assertEquals(0x80, ColorSetting.greenOf(0xFFFF8000));
            assertEquals(0x00, ColorSetting.blueOf(0xFFFF8000));
            assertEquals("#FFFF8000", ColorSetting.toHexString(0xFFFF8000));
        }

        @Test
        @DisplayName("a packed ARGB is unsigned: 0xFF...... is a NEGATIVE int and is not refused")
        void packedArgbIsUnsigned() {
            ColorSetting opaque = new ColorSetting("a.b.colour", "Colour", "A colour",
                    0xFF112233, false, false);
            assertTrue(opaque.defaultArgb() < 0,
                    "0xFF112233 is the signed int -16711936; the type has no unsigned form");
            opaque.validate(Integer.valueOf(0xFF112233));
            opaque.validate(Long.valueOf(0xFF112233L));
            assertEquals(0xFF112233, ColorSetting.unsignedArgbOf("a.b.colour",
                    Integer.valueOf(0xFF112233)));
            assertEquals(0xFF, ColorSetting.alphaOf(0xFF112233));
            assertThrows(SettingValidationException.class,
                    () -> opaque.validate(Long.valueOf(4294967296L)));
            assertThrows(SettingValidationException.class,
                    () -> opaque.validate(Long.valueOf(-1L)));
        }

        @Test
        @DisplayName("alpha 0x00 is the vanilla invisible value and is REFUSED when alpha is forbidden")
        void invisibleIsRefusedWhenAlphaForbidden() {
            ColorSetting opaque = new ColorSetting("a.b.colour", "Colour", "A colour",
                    0xFF112233, false, false);
            SettingValidationException thrown = assertThrows(SettingValidationException.class,
                    () -> opaque.validate(Integer.valueOf(0x00FFFFFF)));
            assertTrue(thrown.getMessage().contains("invisible"), thrown.getMessage());
        }

        @Test
        @DisplayName("an invisible DEFAULT is refused at construction")
        void invisibleDefaultIsRefused() {
            assertThrows(XsozContractException.class, () -> new ColorSetting(
                    "a.b.colour", "Colour", "A colour", 0x00FFFFFF, false, false));
        }

        @Test
        @DisplayName("allowAlpha true accepts the invisible value; it is a legal state then")
        void invisibleIsLegalWithAlpha() {
            ColorSetting translucent = new ColorSetting("a.b.colour", "Colour", "A colour",
                    0x00FFFFFF, true, false);
            translucent.validate(Integer.valueOf(0x00FFFFFF));
            assertTrue(translucent.allowAlpha());
        }

        @Test
        @DisplayName("forceAlpha is the LOADER's half: it is the forcing, not a validation rule")
        void forceAlphaIsTheLoaderHalf() {
            ColorSetting opaque = new ColorSetting("a.b.colour", "Colour", "A colour",
                    0xFF112233, false, false);
            // 0x80 alpha is a real colour and validates; the loader is what forces it opaque.
            opaque.validate(Integer.valueOf(0x80112233));
            assertEquals(0xFF000000 | 0x80112233, opaque.forceAlpha(0x80112233));
            assertEquals(0xFF000000 | 0x00112233, opaque.forceAlpha(0x00112233));
        }

        @Test
        @DisplayName("a non-integral or out-of-64-bit value is refused")
        void malformedIsRefused() {
            ColorSetting opaque = new ColorSetting("a.b.colour", "Colour", "A colour",
                    0xFF112233, false, false);
            assertThrows(SettingValidationException.class,
                    () -> opaque.validate(Double.valueOf(1.5d)));
            assertThrows(SettingValidationException.class,
                    () -> opaque.validate(Double.valueOf(-1.0d)));
            assertThrows(SettingValidationException.class,
                    () -> opaque.validate(Double.valueOf(4294967296.0d)));
            assertThrows(SettingValidationException.class, () -> opaque.validate("red"));
        }
    }

    @Nested
    @DisplayName("KeybindSetting")
    class Keybinds {

        @Test
        @DisplayName("the default may be unbound, and a null default is not a bind")
        void defaultMayBeUnbound() {
            KeybindSetting unbound = new KeybindSetting("a.b.bind", "Bind", "The bind",
                    Keybind.unbound(), false);
            assertEquals(SettingKind.KEYBIND, unbound.kind());
            assertEquals(Keybind.unbound(), unbound.defaultValue());
            assertEquals(Keybind.unbound(), unbound.defaultKeybind());
            assertFalse(unbound.hasPolicy());
            unbound.validate(Keybind.unbound());
            assertThrows(XsozContractException.class, () -> new KeybindSetting(
                    "a.b.bind", "Bind", "The bind", null, false));
        }

        @Test
        @DisplayName("with a policy, a refused bind is a validation failure naming the action")
        void policyRefuses() {
            BindPolicy refuseEverything = new BindPolicy() {
                @Override
                public BindVerdict validate(Keybind bind, BindRequest req) {
                    return BindVerdict.reject(BindRejection.NULL_BIND, "test policy refuses all");
                }
            };
            KeybindSetting strict = new KeybindSetting("a.b.bind", "Bind", "The bind",
                    Keybind.unbound(), "attack", refuseEverything, false);
            assertTrue(strict.hasPolicy());
            assertEquals("attack", strict.policyActionId());
            SettingValidationException thrown = assertThrows(SettingValidationException.class,
                    () -> strict.validate(Keybind.of(InputKey.mouse(1, "Mouse1"))));
            assertTrue(thrown.getMessage().contains("attack"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("test policy refuses all"), thrown.getMessage());
        }

        @Test
        @DisplayName("a policy with no action id is refused: a policy with no action allows everything")
        void policyNeedsAnAction() {
            BindPolicy refuse = new BindPolicy() {
                @Override
                public BindVerdict validate(Keybind bind, BindRequest req) {
                    return BindVerdict.reject(BindRejection.NULL_BIND, "no");
                }
            };
            assertThrows(XsozContractException.class, () -> new KeybindSetting(
                    "a.b.bind", "Bind", "The bind", Keybind.unbound(), null, refuse, false));
            assertThrows(XsozContractException.class, () -> new KeybindSetting(
                    "a.b.bind", "Bind", "The bind", Keybind.unbound(), "  ", refuse, false));
        }

        @Test
        @DisplayName("a key NAME is not a bind: the editor resolves it, the setting does not guess")
        void namesAreNotBinds() {
            KeybindSetting plain = new KeybindSetting("a.b.bind", "Bind", "The bind",
                    Keybind.unbound(), false);
            assertThrows(SettingValidationException.class, () -> plain.validate("RSHIFT"));
        }
    }

    @Nested
    @DisplayName("ListSetting")
    class Lists {

        private final SettingsSchema elementSchema = SettingsSchema.builder("a.b")
                .add(new BooleanSetting("a.b.flag", "Flag", "A flag", true, false))
                .build();

        private final ListSetting<Map<String, Object>> setting = new ListSetting<Map<String, Object>>(
                "coach.drill.markers", "Markers", "The drill markers, in order",
                Arrays.asList(marker("a"), marker("b")), 3, elementSchema, false);

        @Test
        @DisplayName("the default is unmodifiable and the maxSize is exposed")
        void shape() {
            assertEquals(SettingKind.LIST, setting.kind());
            assertEquals(2, setting.defaultValue().size());
            assertEquals(3, setting.maxSize());
            assertEquals(elementSchema, setting.elementSchema());
            assertThrows(UnsupportedOperationException.class,
                    () -> setting.defaultValue().add(marker("c")));
        }

        @Test
        @DisplayName("an over-length list on load is TRUNCATED FROM THE END and the count is reported")
        void overLengthTruncatesFromTheEnd() {
            List<Object> tooLong = new ArrayList<Object>(
                    Arrays.asList(marker("a"), marker("b"), marker("c"), marker("d")));
            assertEquals(1, setting.droppedCount(tooLong));
            List<Object> kept = setting.truncateToMaxSize(tooLong);
            assertEquals(3, kept.size());
            assertEquals(marker("a"), kept.get(0), "the first entry is kept: order is priority order");
            assertEquals(marker("b"), kept.get(1));
            assertEquals(marker("c"), kept.get(2));
        }

        @Test
        @DisplayName("validate refuses an over-length list: the LOADER truncates, it does not reject the file")
        void validateRefusesOverLength() {
            SettingValidationException thrown = assertThrows(SettingValidationException.class,
                    () -> setting.validate(Arrays.asList(marker("a"), marker("b"), marker("c"),
                            marker("d"))));
            assertTrue(thrown.getMessage().contains("truncates"), thrown.getMessage());
        }

        @Test
        @DisplayName("an element the element schema refuses makes the whole value unusable")
        void elementSchemaIsEnforced() {
            Map<String, Object> bad = new LinkedHashMap<String, Object>();
            bad.put("a.b.not-declared", Boolean.TRUE);
            assertThrows(SettingValidationException.class,
                    () -> setting.validate(Arrays.asList(bad)));
            assertThrows(SettingValidationException.class,
                    () -> setting.validate(Arrays.asList("a string, not a map")));
        }

        @Test
        @DisplayName("a default longer than maxSize, a negative maxSize or a null entry is refused")
        void constructionValidates() {
            assertThrows(XsozContractException.class, () -> new ListSetting<Map<String, Object>>(
                    "a.b.list", "List", "A list", Arrays.asList(marker("a"), marker("b")), 1,
                    elementSchema, false));
            assertThrows(XsozContractException.class, () -> new ListSetting<Map<String, Object>>(
                    "a.b.list", "List", "A list", Collections.<Map<String, Object>>emptyList(), -1,
                    elementSchema, false));
            assertThrows(XsozContractException.class, () -> new ListSetting<Map<String, Object>>(
                    "a.b.list", "List", "A list",
                    Arrays.asList(marker("a"), null), 3, elementSchema, false));
            assertThrows(XsozContractException.class, () -> new ListSetting<Map<String, Object>>(
                    "a.b.list", "List", "A list", null, 3, elementSchema, false));
        }

        private Map<String, Object> marker(String flag) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("a.b.flag", Boolean.valueOf("b".equals(flag) || "c".equals(flag)
                    || "d".equals(flag)));
            return map;
        }
    }

    @Nested
    @DisplayName("sensitive is PRESENTATIONAL ONLY, across every kind")
    class SensitiveIsPresentationalOnly {

        @Test
        @DisplayName("toggling the flag on the same key changes nothing but the flag and toString")
        void flagChangesNothingElse() {
            assertFlagIsInertFor(new BooleanSetting("a.b.f", "F", "A flag", true, false),
                    new BooleanSetting("a.b.f", "F", "A flag", true, true));
            assertFlagIsInertFor(new IntSetting("a.b.i", "I", "A number", 5, 0, 10, 1, "ticks", false),
                    new IntSetting("a.b.i", "I", "A number", 5, 0, 10, 1, "ticks", true));
            assertFlagIsInertFor(new DoubleSetting("a.b.d", "D", "A real", 1.5d, 0.0d, 4.0d, 0.5d, "blocks", false),
                    new DoubleSetting("a.b.d", "D", "A real", 1.5d, 0.0d, 4.0d, 0.5d, "blocks", true));
            assertFlagIsInertFor(new StringSetting("a.b.s", "S", "Some text", "x", 8, null, false),
                    new StringSetting("a.b.s", "S", "Some text", "x", 8, null, true));
            assertFlagIsInertFor(new ColorSetting("a.b.c", "C", "A colour", 0xFF112233, false, false),
                    new ColorSetting("a.b.c", "C", "A colour", 0xFF112233, false, true));
            assertFlagIsInertFor(new KeybindSetting("a.b.k", "K", "A bind", Keybind.unbound(), false),
                    new KeybindSetting("a.b.k", "K", "A bind", Keybind.unbound(), true));
            assertFlagIsInertFor(new ListSetting<String>("a.b.l", "L", "A list",
                            Collections.singletonList("x"), 2, null, false),
                    new ListSetting<String>("a.b.l", "L", "A list",
                            Collections.singletonList("x"), 2, null, true));
        }

        @Test
        @DisplayName("redact() is the ONE behaviour the flag drives, and it drives nothing else")
        void redactIsTheOnlySurface() {
            StringSetting plain = new StringSetting("a.b.s", "S", "Some text", "x", 8, null, false);
            StringSetting masked = new StringSetting("a.b.s", "S", "Some text", "x", 8, null, true);
            assertEquals("secret", plain.redact("secret"));
            assertEquals(Setting.REDACTED, masked.redact("secret"));
            assertFalse(masked.redact("secret").contains("secret"));
            assertFalse(masked.toString().contains("secret"));
        }

        @Test
        @DisplayName("a schema's load report is identical with and without the flag")
        void loadReportIsIdentical() {
            Map<String, Object> stored = new LinkedHashMap<String, Object>();
            stored.put("a.b.i", Integer.valueOf(999));
            stored.put("a.b.unknown", "x");
            String plainReport = describe(SettingsSchema.builder("a.b")
                    .add(new IntSetting("a.b.i", "I", "A number", 5, 0, 10, 1, "ticks", false))
                    .build().view(stored).lastReport());
            String maskedReport = describe(SettingsSchema.builder("a.b")
                    .add(new IntSetting("a.b.i", "I", "A number", 5, 0, 10, 1, "ticks", true))
                    .build().view(stored).lastReport());
            assertEquals(plainReport, maskedReport);
        }

        private void assertFlagIsInertFor(Setting plain, Setting masked) {
            assertNotEquals(plain.sensitive(), masked.sensitive());
            assertEquals(plain.key(), masked.key());
            assertEquals(plain.kind(), masked.kind());
            assertEquals(plain.defaultValue(), masked.defaultValue());
            assertEquals(plain, masked, "the flag must not change a setting's identity");
            assertEquals(plain.hashCode(), masked.hashCode());
            for (Object candidate : new Object[] {null, Boolean.TRUE, Integer.valueOf(0),
                    Double.valueOf(1.0d), "text", Arrays.asList("a"), Keybind.unbound()}) {
                assertEquals(failureOf(plain, candidate), failureOf(masked, candidate),
                        "the flag changed validation for " + candidate);
            }
        }

        private String describe(SettingsMutationReport report) {
            return report.unknownKeysDropped().toString() + report.valuesCoerced()
                    + report.valuesResetToDefault() + report.valuesTruncated();
        }

        private String failureOf(Setting setting, Object candidate) {
            try {
                setting.validate(candidate);
                return "OK";
            } catch (RuntimeException e) {
                return e.getClass().getName() + ": " + e.getMessage();
            }
        }
    }
}
