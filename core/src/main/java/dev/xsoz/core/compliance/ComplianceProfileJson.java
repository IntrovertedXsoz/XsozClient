package dev.xsoz.core.compliance;

import dev.xsoz.core.XsozContractException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The profile JSON codec for {@link ComplianceProfile#toJson()} /
 * {@link ComplianceProfile#fromJson(String)}.
 *
 * <p><strong>Hand-written, zero dependencies, and strict.</strong> {@code :core} declares no
 * library beyond JUnit (contracts.md C10.3), so a JSON parser is a hand-written one. It
 * covers exactly the subset the profile schema needs - objects, arrays of strings, strings,
 * booleans and null - and refuses anything else with a named field path rather than
 * guessing.</p>
 *
 * <p>Fields are <strong>appended, never removed or reused</strong> (contracts.md C7.2, the
 * forward-compatibility rule from the {@code xsoz:*} spec applied to the schema). A newer
 * document therefore carries unknown fields that a reader ignores, and an older reader
 * reading a newer document still finds every field it knows.</p>
 *
 * <p>On decode, every citation is rebuilt through the {@link Citation} constructor, so the
 * 15-word limit, the {@code https} requirement and the mandatory retrieval date are
 * re-applied to an edited file. A hand-edited profile cannot smuggle in a 400-word
 * paraphrase.</p>
 */
public final class ComplianceProfileJson {

    /** The {@code format} discriminator written into and required from every profile document. */
    public static final String FORMAT = "xsoz-compliance-profile";

    /** The document version this codec writes and accepts. */
    public static final int FORMAT_VERSION = 1;

    private ComplianceProfileJson() {
        throw new AssertionError("ComplianceProfileJson is a codec and is not instantiable.");
    }

    /**
     * @param profile the profile to serialise
     * @return the canonical JSON text; keys are emitted in a fixed order, so two equal
     *         profiles produce byte-identical output
     */
    public static String encode(ComplianceProfile profile) {
        if (profile == null) {
            throw new XsozContractException("ComplianceProfileJson.encode requires a profile.");
        }
        StringBuilder out = new StringBuilder(1024);
        out.append("{\n");
        field(out, "format", quote(FORMAT), true, 1);
        field(out, "formatVersion", Integer.toString(FORMAT_VERSION), true, 1);
        field(out, "id", quote(profile.id()), true, 1);
        field(out, "name", quote(profile.name()), true, 1);
        field(out, "defaultDeny", Boolean.toString(profile.defaultDeny()), true, 1);
        field(out, "retrievedOn", quote(profile.retrievedOn().toString()), true, 1);
        field(out, "rulesets", array(profile.rulesets()), true, 1);
        field(out, "allowList", array(profile.allowList()), true, 1);
        out.append("  \"verdicts\": ");
        if (profile.verdicts().isEmpty()) {
            out.append("{}\n");
        } else {
            out.append("{\n");
            int index = 0;
            int size = profile.verdicts().size();
            for (Map.Entry<String, ComplianceRow> entry : profile.verdicts().entrySet()) {
                out.append("    ").append(quote(entry.getKey())).append(": ");
                encodeRow(out, entry.getValue(), 2);
                out.append(index == size - 1 ? "\n" : ",\n");
                index++;
            }
            out.append("  }\n");
        }
        out.append("}\n");
        return out.toString();
    }

    /**
     * @param json the profile document
     * @return the decoded profile
     * @throws XsozContractException if the document is malformed, has the wrong format or
     *                                version, or contains a citation the {@link Citation}
     *                                constructor refuses
     */
    public static ComplianceProfile decode(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new XsozContractException("ComplianceProfileJson.decode received an empty document.");
        }
        Object parsed = new Parser(json).parseDocument();
        Map<String, Object> root = asObject(parsed, "$");
        String format = asString(root, "format", "$");
        if (!FORMAT.equals(format)) {
            throw new XsozContractException(
                    "Compliance profile has format \"" + format + "\", expected \"" + FORMAT + "\".");
        }
        int formatVersion = (int) asLong(root, "formatVersion", "$");
        if (formatVersion > FORMAT_VERSION) {
            throw new XsozContractException(
                    "Compliance profile formatVersion " + formatVersion + " is newer than the "
                            + FORMAT_VERSION + " this build reads. Refusing rather than guessing.");
        }
        Set<String> rulesets = new java.util.LinkedHashSet<String>(asStringArray(root, "rulesets", "$"));
        Set<String> allowList = new java.util.LinkedHashSet<String>(asStringArray(root, "allowList", "$"));
        Map<String, ComplianceRow> verdicts = new LinkedHashMap<String, ComplianceRow>();
        Object rawVerdicts = root.get("verdicts");
        if (rawVerdicts != null) {
            Map<String, Object> verdictObject = asObject(rawVerdicts, "$.verdicts");
            for (Map.Entry<String, Object> entry : verdictObject.entrySet()) {
                verdicts.put(entry.getKey(), decodeRow(asObject(entry.getValue(),
                        "$.verdicts." + entry.getKey())));
            }
        }
        return new ComplianceProfile(
                asString(root, "id", "$"),
                asString(root, "name", "$"),
                rulesets,
                asBoolean(root, "defaultDeny", "$"),
                verdicts,
                allowList,
                LocalDate.parse(asString(root, "retrievedOn", "$")));
    }

    private static void encodeRow(StringBuilder out, ComplianceRow row, int depth) {
        out.append("{\n");
        field(out, "verdict", quote(row.verdict().name()), true, depth + 1);
        field(out, "tier", quote(row.tier().name()), true, depth + 1);
        field(out, "summary", quote(row.summary()), true, depth + 1);
        field(out, "conditions", array(row.conditions()), true, depth + 1);
        field(out, "prohibitionsAlways", array(row.prohibitionsAlways()), true, depth + 1);
        out.append(indent(depth + 1)).append("\"citations\": ");
        if (row.citations().isEmpty()) {
            out.append("[]\n");
        } else {
            out.append("[\n");
            for (int i = 0; i < row.citations().size(); i++) {
                Citation citation = row.citations().get(i);
                out.append(indent(depth + 2)).append("{\n");
                field(out, "rulesetId", quote(citation.rulesetId()), true, depth + 3);
                field(out, "clause", quote(citation.clause()), true, depth + 3);
                field(out, "url", quote(citation.url()), true, depth + 3);
                field(out, "retrievedOn", quote(citation.retrievedOn().toString()), false, depth + 3);
                out.append(indent(depth + 2)).append("}").append(i == row.citations().size() - 1 ? "\n" : ",\n");
            }
            out.append(indent(depth + 1)).append("]\n");
        }
        out.append(indent(depth)).append("}");
    }

    private static ComplianceRow decodeRow(Map<String, Object> object) {
        List<Citation> citations = new ArrayList<Citation>();
        Object rawCitations = object.get("citations");
        if (rawCitations != null) {
            for (Object entry : asArray(rawCitations, "$.citations")) {
                Map<String, Object> citationObject = asObject(entry, "$.citations[]");
                citations.add(new Citation(
                        asString(citationObject, "rulesetId", "$.citations[]"),
                        asString(citationObject, "clause", "$.citations[]"),
                        asString(citationObject, "url", "$.citations[]"),
                        LocalDate.parse(asString(citationObject, "retrievedOn", "$.citations[]"))));
            }
        }
        if (citations.isEmpty()) {
            throw new MissingCitationException(
                    "A compliance row in the imported profile carries no citation. contracts.md C7.2: "
                            + "a verdict without a citation is invalid.");
        }
        return new ComplianceRow(
                Verdict.valueOf(asString(object, "verdict", "$.verdicts[]")),
                ComplianceTier.valueOf(asString(object, "tier", "$.verdicts[]")),
                citations,
                new java.util.LinkedHashSet<String>(asStringArray(object, "conditions", "$.verdicts[]")),
                new java.util.LinkedHashSet<String>(
                        asStringArray(object, "prohibitionsAlways", "$.verdicts[]")),
                asString(object, "summary", "$.verdicts[]"));
    }

    // ---- writing -------------------------------------------------------------------------------

    private static void field(StringBuilder out, String key, String renderedValue, boolean comma,
                              int depth) {
        out.append(indent(depth)).append(quote(key)).append(": ").append(renderedValue);
        out.append(comma ? ",\n" : "\n");
    }

    private static String array(Set<String> values) {
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (String value : values) {
            if (!first) {
                out.append(", ");
            }
            out.append(quote(value));
            first = false;
        }
        return out.append("]").toString();
    }

    private static String indent(int depth) {
        StringBuilder out = new StringBuilder(depth * 2);
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
        return out.toString();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.append('"').toString();
    }

    // ---- reading -------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String path) {
        if (!(value instanceof Map)) {
            throw new XsozContractException("Expected an object at " + path + ", found " + kindOf(value) + ".");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArray(Object value, String path) {
        if (!(value instanceof List)) {
            throw new XsozContractException("Expected an array at " + path + ", found " + kindOf(value) + ".");
        }
        return (List<Object>) value;
    }

    private static String asString(Map<String, Object> object, String key, String path) {
        Object value = object.get(key);
        if (!(value instanceof String)) {
            throw new XsozContractException(
                    "Expected a string at " + path + "." + key + ", found " + kindOf(value) + ".");
        }
        return (String) value;
    }

    private static boolean asBoolean(Map<String, Object> object, String key, String path) {
        Object value = object.get(key);
        if (!(value instanceof Boolean)) {
            throw new XsozContractException(
                    "Expected a boolean at " + path + "." + key + ", found " + kindOf(value) + ".");
        }
        return (Boolean) value;
    }

    private static long asLong(Map<String, Object> object, String key, String path) {
        Object value = object.get(key);
        if (!(value instanceof Long)) {
            throw new XsozContractException(
                    "Expected an integer at " + path + "." + key + ", found " + kindOf(value) + ".");
        }
        return (Long) value;
    }

    private static List<String> asStringArray(Map<String, Object> object, String key, String path) {
        Object value = object.get(key);
        if (value == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (Object entry : asArray(value, path + "." + key)) {
            if (!(entry instanceof String)) {
                throw new XsozContractException(
                        "Expected strings in " + path + "." + key + ", found " + kindOf(entry) + ".");
            }
            out.add((String) entry);
        }
        return out;
    }

    private static String kindOf(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "a string";
        }
        if (value instanceof Boolean) {
            return "a boolean";
        }
        if (value instanceof Long) {
            return "an integer";
        }
        if (value instanceof List) {
            return "an array";
        }
        if (value instanceof Map) {
            return "an object";
        }
        return value.getClass().getSimpleName();
    }

    /**
     * A minimal recursive-descent reader for the JSON subset this schema uses. It is
     * deliberately not lenient: a malformed document is a configuration bug, and guessing at
     * it is how a permissive profile gets imported.
     */
    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Object parseDocument() {
            skipWhitespace();
            Object value = parseValue("$");
            skipWhitespace();
            if (pos != text.length()) {
                throw new XsozContractException(
                        "Trailing content at offset " + pos + " in the compliance profile document.");
            }
            return value;
        }

        private Object parseValue(String path) {
            skipWhitespace();
            if (pos >= text.length()) {
                throw new XsozContractException("Unexpected end of document at " + path + ".");
            }
            char c = text.charAt(pos);
            if (c == '{') {
                return parseObject(path);
            }
            if (c == '[') {
                return parseArray(path);
            }
            if (c == '"') {
                return parseString(path);
            }
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return parseInteger(path);
            }
            throw new XsozContractException(
                    "Unexpected character '" + c + "' at offset " + pos + " (" + path + ").");
        }

        private Map<String, Object> parseObject(String path) {
            Map<String, Object> object = new LinkedHashMap<String, Object>();
            pos++; // '{'
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == '}') {
                pos++;
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString(path);
                skipWhitespace();
                expect(':', path);
                Object value = parseValue(path + "." + key);
                if (object.put(key, value) != null) {
                    throw new XsozContractException(
                            "Duplicate key \"" + key + "\" at " + path + ". A duplicated field is an "
                                    + "ambiguous field.");
                }
                skipWhitespace();
                if (pos >= text.length()) {
                    throw new XsozContractException("Unterminated object at " + path + ".");
                }
                char c = text.charAt(pos++);
                if (c == '}') {
                    return object;
                }
                if (c != ',') {
                    throw new XsozContractException(
                            "Expected ',' or '}' at offset " + (pos - 1) + " (" + path + ").");
                }
            }
        }

        private List<Object> parseArray(String path) {
            List<Object> array = new ArrayList<Object>();
            pos++; // '['
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == ']') {
                pos++;
                return array;
            }
            while (true) {
                array.add(parseValue(path + "[]"));
                skipWhitespace();
                if (pos >= text.length()) {
                    throw new XsozContractException("Unterminated array at " + path + ".");
                }
                char c = text.charAt(pos++);
                if (c == ']') {
                    return array;
                }
                if (c != ',') {
                    throw new XsozContractException(
                            "Expected ',' or ']' at offset " + (pos - 1) + " (" + path + ").");
                }
            }
        }

        private String parseString(String path) {
            expect('"', path);
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw new XsozContractException("Unterminated string at " + path + ".");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= text.length()) {
                    throw new XsozContractException("Unterminated escape at " + path + ".");
                }
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (pos + 4 > text.length()) {
                            throw new XsozContractException("Truncated unicode escape at " + path + ".");
                        }
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw new XsozContractException(
                                "Unknown escape '\\" + escape + "' at offset " + (pos - 1) + ".");
                }
            }
        }

        private Long parseInteger(String path) {
            int start = pos;
            if (pos < text.length() && text.charAt(pos) == '-') {
                pos++;
            }
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            try {
                return Long.valueOf(Long.parseLong(text.substring(start, pos)));
            } catch (NumberFormatException e) {
                throw new XsozContractException(
                        "Malformed integer at offset " + start + " (" + path + ").", e);
            }
        }

        private void expect(char expected, String path) {
            if (pos >= text.length() || text.charAt(pos) != expected) {
                throw new XsozContractException(
                        "Expected '" + expected + "' at offset " + pos + " (" + path + ").");
            }
            pos++;
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }
}
