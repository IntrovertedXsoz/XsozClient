package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract C4.3 and C4.4 - the schema container, the view, the mutation report, and the
 * ordered coercion table.
 */
class SettingsSchemaTest {

    private enum Style { COMPACT, ROOMY }

    private SettingsSchema schema;
    private Map<String, Object> stored;

    @BeforeEach
    void declareSchema() {
        schema = SettingsSchema.builder("hud.totem-counter")
                .add(new BooleanSetting("hud.totem-counter.show-carry-count", "Show carry count",
                        "Draws the number of totems you are carrying", true, false))
                .add(new IntSetting("hud.totem-counter.max-rows", "Max rows",
                        "How many rows the readout may grow to", 3, 1, 9, 1, "rows", false))
                .add(new DoubleSetting("hud.totem-counter.scale", "Scale",
                        "Interface scale for the readout", 1.0d, 0.5d, 3.0d, 0.25d, "ratio", false))
                .add(new EnumSetting<Style>("hud.totem-counter.style", "Style",
                        "How the readout is laid out", Style.COMPACT, Style.class, null, false))
                .add(new StringSetting("hud.totem-counter.prefix", "Prefix",
                        "Text drawn before the readout", "T", 4, null, true))
                .add(new ColorSetting("hud.totem-counter.colour", "Colour",
                        "The readout colour", 0xFFFFFFFF, false, false))
                .add(new ListSetting<String>("hud.totem-counter.rows", "Rows",
                        "Row labels, in order", Collections.<String>emptyList(), 2, null, false))
                .build();
        stored = new LinkedHashMap<String, Object>();
    }

    @Nested
    @DisplayName("declaration and lookup")
    class Declaration {

        @Test
        @DisplayName("declaration order IS the UI order IS the JSON key order (C4.3)")
        void declarationOrderIsTheOrder() {
            assertEquals("hud.totem-counter", schema.moduleId());
            assertEquals(Arrays.asList(
                    "hud.totem-counter.show-carry-count", "hud.totem-counter.max-rows",
                    "hud.totem-counter.scale", "hud.totem-counter.style",
                    "hud.totem-counter.prefix", "hud.totem-counter.colour",
                    "hud.totem-counter.rows"), schema.keys());
        }

        @Test
        @DisplayName("byKey finds a declared setting and returns empty for anything else")
        void lookup() {
            assertTrue(schema.byKey("hud.totem-counter.max-rows").isPresent());
            assertEquals(SettingKind.INT, schema.byKey("hud.totem-counter.max-rows").get().kind());
            assertFalse(schema.byKey("hud.totem-counter.nope").isPresent());
            assertFalse(schema.byKey(null).isPresent());
        }

        @Test
        @DisplayName("a key that does not belong to the module is refused at build time")
        void keyMustBelongToTheModule() {
            XsozContractException thrown = assertThrows(XsozContractException.class,
                    () -> SettingsSchema.builder("hud.totem-counter")
                            .add(new BooleanSetting("latency.crystal-release.flag", "Flag",
                                    "A flag on the wrong module", false, false))
                            .build());
            assertTrue(thrown.getMessage().contains("hud.totem-counter"), thrown.getMessage());
        }

        @Test
        @DisplayName("a repeated key is refused: keys are globally unique (C4.1)")
        void duplicateKeyIsRefused() {
            assertThrows(XsozContractException.class, () -> SettingsSchema.builder("a.b")
                    .add(new BooleanSetting("a.b.flag", "Flag", "A flag", false, false))
                    .add(new BooleanSetting("a.b.flag", "Flag", "The same flag", true, false))
                    .build());
        }

        @Test
        @DisplayName("the returned lists are unmodifiable")
        void collectionsAreUnmodifiable() {
            assertThrows(UnsupportedOperationException.class, () -> schema.settings().clear());
            assertThrows(UnsupportedOperationException.class, () -> schema.keys().clear());
        }
    }

    @Nested
    @DisplayName("defaultsView")
    class Defaults {

        @Test
        @DisplayName("every declared key answers, and the report is clean")
        void everyKeyAnswers() {
            SettingsView view = schema.defaultsView();
            assertTrue(view.getBoolean("hud.totem-counter.show-carry-count"));
            assertEquals(3, view.getInt("hud.totem-counter.max-rows"));
            assertEquals(1.0d, view.getDouble("hud.totem-counter.scale"), 0.0d);
            assertEquals(Style.COMPACT, view.getEnum("hud.totem-counter.style"));
            assertEquals("T", view.getString("hud.totem-counter.prefix"));
            assertEquals(0xFFFFFFFF, view.getArgb("hud.totem-counter.colour"));
            assertEquals(Collections.emptyList(), view.getList("hud.totem-counter.rows"));
            assertTrue(view.lastReport().isClean());
        }
    }

    @Nested
    @DisplayName("C4.4's ordered coercion table")
    class Coercion {

        @Test
        @DisplayName("a key the schema does not declare is DROPPED and reported")
        void unknownKeyIsDropped() {
            stored.put("hud.totem-counter.show-carry-count", Boolean.TRUE);
            stored.put("hud.totem-counter.made-up", "surprise");
            SettingsMutationReport report = schema.view(stored).lastReport();
            assertEquals(1, report.unknownKeysDropped().size());
            assertTrue(report.unknownKeysDropped().get(0).contains("hud.totem-counter.made-up"),
                    report.unknownKeysDropped().toString());
        }

        @Test
        @DisplayName("a whole number for a boolean coerces, because 0/1 is lossless")
        void integerCoercesToBoolean() {
            stored.put("hud.totem-counter.show-carry-count", Integer.valueOf(0));
            SettingsView view = schema.view(stored);
            assertFalse(view.getBoolean("hud.totem-counter.show-carry-count"));
            assertTrue(view.lastReport().valuesCoerced()
                    .containsKey("hud.totem-counter.show-carry-count"));
        }

        @Test
        @DisplayName("a number for a double is lossless and is not even reported as a coercion")
        void integerForDoubleIsExact() {
            stored.put("hud.totem-counter.scale", Integer.valueOf(2));
            SettingsView view = schema.view(stored);
            assertEquals(2.0d, view.getDouble("hud.totem-counter.scale"), 0.0d);
        }

        @Test
        @DisplayName("an out-of-range value is CLAMPED then snapped to the step, and reported")
        void outOfRangeClampsAndSnaps() {
            stored.put("hud.totem-counter.max-rows", Integer.valueOf(9999));
            stored.put("hud.totem-counter.scale", Double.valueOf(100.0d));
            SettingsView view = schema.view(stored);
            assertEquals(9, view.getInt("hud.totem-counter.max-rows"));
            assertEquals(3.0d, view.getDouble("hud.totem-counter.scale"), 0.0d);
            assertTrue(view.lastReport().valuesCoerced().containsKey("hud.totem-counter.max-rows"));
            assertTrue(view.lastReport().valuesCoerced().containsKey("hud.totem-counter.scale"));
        }

        @Test
        @DisplayName("an off-step value is snapped and reported")
        void offStepSnaps() {
            stored.put("hud.totem-counter.scale", Double.valueOf(1.1d));
            SettingsView view = schema.view(stored);
            assertEquals(1.0d, view.getDouble("hud.totem-counter.scale"), 0.0d);
            assertTrue(view.lastReport().valuesCoerced().get("hud.totem-counter.scale")
                    .contains("step"));
        }

        @Test
        @DisplayName("a value that is not a member of the enum is reset to the default")
        void unknownEnumResets() {
            seedComplete();
            stored.put("hud.totem-counter.style", "FANCY");
            SettingsView view = schema.view(stored);
            assertEquals(Style.COMPACT, view.getEnum("hud.totem-counter.style"));
            assertEquals(1, view.lastReport().valuesResetToDefault().size(),
                    "exactly one key was bad; the rest loaded exactly as stored");
        }

        @Test
        @DisplayName("a stored enum NAME resolves to the constant and is reported as a coercion")
        void enumNameResolves() {
            stored.put("hud.totem-counter.style", "ROOMY");
            SettingsView view = schema.view(stored);
            assertEquals(Style.ROOMY, view.getEnum("hud.totem-counter.style"));
            assertTrue(view.lastReport().valuesCoerced().containsKey("hud.totem-counter.style"));
        }

        @Test
        @DisplayName("text over its length is TRUNCATED from the front-kept prefix and reported")
        void textOverLengthTruncates() {
            stored.put("hud.totem-counter.prefix", "TOO LONG");
            SettingsView view = schema.view(stored);
            assertEquals("TOO ", view.getString("hud.totem-counter.prefix"));
            assertEquals(1, view.lastReport().valuesTruncated().size());
        }

        @Test
        @DisplayName("an over-length list is truncated from the END and reported")
        void listTruncatesFromTheEnd() {
            stored.put("hud.totem-counter.rows", Arrays.asList("a", "b", "c"));
            SettingsView view = schema.view(stored);
            assertEquals(Arrays.asList("a", "b"), view.getList("hud.totem-counter.rows"));
            assertEquals(1, view.lastReport().valuesTruncated().size());
        }

        @Test
        @DisplayName("a translucent colour for an opaque-only setting is FORCED and reported")
        void alphaIsForced() {
            stored.put("hud.totem-counter.colour", Integer.valueOf(0x80112233));
            SettingsView view = schema.view(stored);
            assertEquals(0xFF112233, view.getArgb("hud.totem-counter.colour"));
            assertTrue(view.lastReport().valuesCoerced().containsKey("hud.totem-counter.colour"));
        }

        @Test
        @DisplayName("the invisible colour is reset to the default, not forced: a row nobody can see is not a value")
        void invisibleColourResets() {
            seedComplete();
            stored.put("hud.totem-counter.colour", Integer.valueOf(0x00112233));
            SettingsView view = schema.view(stored);
            assertEquals(0xFFFFFFFF, view.getArgb("hud.totem-counter.colour"));
            assertEquals(1, view.lastReport().valuesResetToDefault().size());
        }

        @Test
        @DisplayName("a value of entirely the wrong type is reset to the default")
        void wrongTypeResets() {
            seedComplete();
            stored.put("hud.totem-counter.max-rows", "three");
            SettingsView view = schema.view(stored);
            assertEquals(3, view.getInt("hud.totem-counter.max-rows"));
            assertEquals(1, view.lastReport().valuesResetToDefault().size());
        }

        @Test
        @DisplayName("an absent key uses the declared default and says so")
        void absentKeyUsesDefault() {
            SettingsView view = schema.view(stored);
            assertEquals(3, view.getInt("hud.totem-counter.max-rows"));
            assertEquals(schema.settings().size(), view.lastReport().valuesResetToDefault().size());
            assertTrue(view.lastReport().valuesResetToDefault().get(0).contains("absent"),
                    view.lastReport().valuesResetToDefault().toString());
        }

        @Test
        @DisplayName("a report never prints the value of a sensitive setting")
        void sensitiveValuesAreNotPrinted() {
            stored.put("hud.totem-counter.prefix", "TOO LONG");
            stored.put("hud.totem-counter.colour", Integer.valueOf(0x00112233));
            SettingsMutationReport report = schema.view(stored).lastReport();
            for (String line : report.valuesTruncated()) {
                assertFalse(line.contains("TOO LONG"), line);
            }
            for (String line : report.valuesResetToDefault()) {
                if (line.startsWith("hud.totem-counter.prefix")) {
                    assertFalse(line.contains("TOO LONG"), line);
                }
            }
        }

        @Test
        @DisplayName("view() NEVER throws, whatever it is handed")
        void viewNeverThrows() {
            Map<String, Object> hostile = new LinkedHashMap<String, Object>();
            hostile.put("hud.totem-counter.max-rows", new Object());
            hostile.put("hud.totem-counter.rows", "not a list");
            hostile.put("hud.totem-counter.colour", Double.valueOf(1.5d));
            hostile.put("hud.totem-counter.style", Integer.valueOf(7));
            SettingsView view = schema.view(hostile);
            assertEquals(3, view.getInt("hud.totem-counter.max-rows"));
            assertEquals(0xFFFFFFFF, view.getArgb("hud.totem-counter.colour"));
            assertEquals(Style.COMPACT, view.getEnum("hud.totem-counter.style"));
            assertFalse(view.lastReport().isClean());
        }
    }

    @Nested
    @DisplayName("validateAll is the STRICT pass")
    class Strict {

        @Test
        @DisplayName("a fully valid map passes")
        void validMapPasses() {
            Map<String, Object> complete = completeValidMap();
            schema.validateAll(complete);
        }

        @Test
        @DisplayName("an undeclared key is refused rather than dropped: the caller asked for exactness")
        void undeclaredKeyIsRefused() {
            Map<String, Object> complete = completeValidMap();
            complete.put("hud.totem-counter.made-up", "x");
            SettingValidationException thrown = assertThrows(SettingValidationException.class,
                    () -> schema.validateAll(complete));
            assertTrue(thrown.getMessage().contains("not declared"), thrown.getMessage());
        }

        @Test
        @DisplayName("an absent declared key is refused")
        void absentKeyIsRefused() {
            Map<String, Object> complete = completeValidMap();
            complete.remove("hud.totem-counter.max-rows");
            assertThrows(SettingValidationException.class, () -> schema.validateAll(complete));
        }

        @Test
        @DisplayName("an out-of-range value is refused, not clamped: a strict pass has no clamp")
        void outOfRangeIsRefused() {
            Map<String, Object> complete = completeValidMap();
            complete.put("hud.totem-counter.max-rows", Integer.valueOf(9999));
            assertThrows(SettingValidationException.class, () -> schema.validateAll(complete));
        }
    }

    @Nested
    @DisplayName("the view refuses a wrong-kind question")
    class TypedGetters {

        @Test
        @DisplayName("asking a BOOLEAN row for a double is a named failure, not a null")
        void wrongGetterIsNamed() {
            SettingsView view = schema.defaultsView();
            SettingValidationException thrown = assertThrows(SettingValidationException.class,
                    () -> view.getDouble("hud.totem-counter.show-carry-count"));
            assertTrue(thrown.getMessage().contains("BOOLEAN"), thrown.getMessage());
            assertThrows(SettingValidationException.class,
                    () -> view.getInt("hud.totem-counter.scale"));
            assertThrows(SettingValidationException.class,
                    () -> view.raw("hud.totem-counter.nope"));
        }

        @Test
        @DisplayName("getList hands back an unmodifiable list")
        void listIsUnmodifiable() {
            SettingsView view = schema.defaultsView();
            List<String> rows = view.getList("hud.totem-counter.rows");
            assertThrows(UnsupportedOperationException.class, () -> rows.add("x"));
        }
    }

    private void seedComplete() {
        stored.putAll(completeValidMap());
    }

    private Map<String, Object> completeValidMap() {
        Map<String, Object> complete = new LinkedHashMap<String, Object>();
        complete.put("hud.totem-counter.show-carry-count", Boolean.TRUE);
        complete.put("hud.totem-counter.max-rows", Integer.valueOf(4));
        complete.put("hud.totem-counter.scale", Double.valueOf(1.5d));
        complete.put("hud.totem-counter.style", Style.ROOMY);
        complete.put("hud.totem-counter.prefix", "TOT");
        complete.put("hud.totem-counter.colour", Integer.valueOf(0xFF00FF00));
        complete.put("hud.totem-counter.rows", new ArrayList<Object>());
        return complete;
    }
}
