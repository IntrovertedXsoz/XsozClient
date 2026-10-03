package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The hand-written JSON model. A config file this class cannot read exactly is a file the
 * recovery path owns, so the refusals matter as much as the reads.
 */
class JsonObjectTest {

    @Test
    @DisplayName("a document round-trips: parse then toJson gives the same text back")
    void roundTrip() {
        String text = "{\n"
                + "  \"a\": 1,\n"
                + "  \"b\": \"two\",\n"
                + "  \"c\": true,\n"
                + "  \"d\": null,\n"
                + "  \"e\": 0.744140625,\n"
                + "  \"f\": [\n"
                + "    1,\n"
                + "    \"two\",\n"
                + "    false,\n"
                + "    null\n"
                + "  ],\n"
                + "  \"g\": {\n"
                + "    \"h\": 1\n"
                + "  }\n"
                + "}\n";
        assertEquals(text, JsonObject.parse(text).toJson());
    }

    @Test
    @DisplayName("0.744140625 survives as an exact double, which is the whole point of the codec")
    void exactDoubleSurvives() {
        JsonObject parsed = JsonObject.parse("{\"cmPer360\": 0.744140625}");
        assertEquals(0.744140625d, parsed.getDouble("cmPer360"), 0.0d);
        assertEquals("0.744140625", JsonObject.builder()
                .put("cmPer360", parsed.getDouble("cmPer360")).build().toJson()
                .replace("{\n  \"cmPer360\": ", "").replace("\n}\n", ""));
    }

    @Test
    @DisplayName("an integral number decodes as a Long, a fractional one as a Double")
    void numberTypesAreDistinguished() {
        JsonObject parsed = JsonObject.parse("{\"i\": 2000, \"d\": 2000.5}");
        assertEquals(Long.class, parsed.raw("i").getClass());
        assertEquals(Double.class, parsed.raw("d").getClass());
        assertEquals(2000, parsed.getInt("i"));
        assertEquals(2000.5d, parsed.getDouble("d"), 0.0d);
    }

    @Test
    @DisplayName("insertion order is preserved on read and on write")
    void orderIsPreserved() {
        JsonObject forward = JsonObject.builder().put("a", 1L).put("b", 2L).put("c", 3L).build();
        JsonObject backward = JsonObject.builder().put("c", 3L).put("b", 2L).put("a", 1L).build();
        assertEquals(forward, backward, "equality is by content: order is presentation");
        assertEquals(Arrays.asList("a", "b", "c"), new ArrayList<String>(forward.keys()));
        assertEquals(Arrays.asList("c", "b", "a"), new ArrayList<String>(backward.keys()));
        assertEquals("{\n  \"a\": 1,\n  \"b\": 2,\n  \"c\": 3\n}\n", forward.toJson());
        assertEquals("{\n  \"c\": 3,\n  \"b\": 2,\n  \"a\": 1\n}\n", backward.toJson());
    }

    @ParameterizedTest(name = "refuses {0}")
    @ValueSource(strings = {
            "",
            "   ",
            "{",
            "{\"a\": }",
            "{\"a\": 1,}",
            "{a: 1}",
            "{'a': 1}",
            "{\"a\": 01x}",
            "{\"a\": 1} trailing",
            "{\"a\": \"unterminated}",
            "{\"a\": \"bad \\q escape\"}",
            "{\"a\": 1, \"a\": 2}",
            "{\"a\": 1} {\"b\": 2}",
            "[]",
            "\"just a string\"",
    })
    void malformedIsRefused(String text) {
        assertThrows(XsozContractException.class, () -> JsonObject.parse(text));
    }

    @Test
    @DisplayName("a repeated key is refused: a document with two spellings of one key has no meaning")
    void duplicateKeyIsRefused() {
        XsozContractException thrown = assertThrows(XsozContractException.class,
                () -> JsonObject.parse("{\"a\": 1, \"a\": 2}"));
        assertTrue(thrown.getMessage().contains("twice"), thrown.getMessage());
    }

    @Test
    @DisplayName("a member that is absent is distinguished from one that is null")
    void absentIsNotNull() {
        JsonObject parsed = JsonObject.parse("{\"present\": null}");
        assertTrue(parsed.has("present"));
        assertFalse(parsed.has("absent"));
        assertEquals(null, parsed.raw("present"));
        assertEquals("fallback", parsed.getString("absent", "fallback"));
        assertEquals(7, parsed.getInt("absent", 7));
        assertEquals(1.5d, parsed.getDouble("absent", 1.5d), 0.0d);
        assertTrue(parsed.getBoolean("absent", true));
        assertFalse(parsed.optObject("absent").isPresent());
        assertEquals(0, parsed.getStringArray("absent").size());
    }

    @Test
    @DisplayName("reading a member as the wrong type is refused with the path named")
    void wrongTypeIsRefused() {
        JsonObject parsed = JsonObject.parse("{\"n\": 1, \"s\": \"x\", \"o\": {}, \"a\": [1]}");
        assertThrows(XsozContractException.class, () -> parsed.getString("n"));
        assertThrows(XsozContractException.class, () -> parsed.getInt("s"));
        assertThrows(XsozContractException.class, () -> parsed.getDouble("s"));
        assertThrows(XsozContractException.class, () -> parsed.getBoolean("n"));
        assertThrows(XsozContractException.class, () -> parsed.getObject("n"));
        assertThrows(XsozContractException.class, () -> parsed.getArray("n"));
        assertThrows(XsozContractException.class, () -> parsed.getString("absent"));
    }

    @Test
    @DisplayName("with() and without() return copies; the receiver is untouched")
    void copiesOnWrite() {
        JsonObject original = JsonObject.parse("{\"a\": 1}");
        JsonObject added = original.with("b", 2L);
        JsonObject removed = original.without("a");
        assertEquals(1, original.size());
        assertEquals(2, added.size());
        assertEquals(0, removed.size());
        assertFalse(original.equals(added));
    }

    @Test
    @DisplayName("empty containers render compactly, so an empty object is not five lines of braces")
    void emptyContainersAreCompact() {
        assertEquals("{}\n", JsonObject.empty().toJson());
        assertEquals("{\n  \"a\": []\n}\n",
                JsonObject.builder().putArray("a", new ArrayList<Object>()).build().toJson());
    }

    @Test
    @DisplayName("control characters are escaped on the way out, so a round-trip is lossless")
    void controlCharactersEscape() {
        JsonObject built = JsonObject.builder().put("a", "line1\nline2\ttab\"quote\\slash").build();
        JsonObject parsed = JsonObject.parse(built.toJson());
        assertEquals("line1\nline2\ttab\"quote\\slash", parsed.getString("a"));
    }

    @Test
    @DisplayName("nested objects and arrays keep their shape")
    void nesting() {
        JsonObject nested = JsonObject.parse(
                "{\"a\": {\"b\": {\"c\": [1, {\"d\": 2}]}}}");
        assertEquals(2, nested.getObject("a").getObject("b").getArray("c").size());
        assertEquals(2L, ((JsonObject) nested.getObject("a").getObject("b")
                .getArray("c").get(1)).raw("d"));
    }

    @Test
    @DisplayName("a getInt on a huge number is refused rather than silently narrowed")
    void narrowingIsRefused() {
        JsonObject parsed = JsonObject.parse("{\"big\": 4294967296, \"frac\": 1.5}");
        assertThrows(XsozContractException.class, () -> parsed.getInt("big"));
        assertThrows(XsozContractException.class, () -> parsed.getInt("frac"));
        assertEquals(0, parsed.getInt("big", 0), "the fallback overload is lenient by design");
    }
}
