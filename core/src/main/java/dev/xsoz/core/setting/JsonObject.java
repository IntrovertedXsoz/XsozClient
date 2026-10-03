package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A hand-written JSON object model: the argument and result type of
 * {@link dev.xsoz.core.config.Migration} (contracts.md C4.5) and the substrate of the
 * profile codec (C5).
 *
 * <p><strong>Zero dependencies, deliberately.</strong> {@code :core} declares no library
 * beyond JUnit (contracts.md C10.3), so the JSON model is written here rather than
 * borrowed. It covers the subset the config schema needs - objects, arrays, strings,
 * numbers, booleans and null - and refuses anything else.</p>
 *
 * <p><strong>Numbers decode as {@link Long} when they are integral and {@link Double}
 * otherwise.</strong> That distinction is load-bearing for this project: a profile's
 * {@code cmPer360} is {@code 0.744140625}, and a codec that rounded it to a {@code float}
 * or an {@code int} would reintroduce exactly the class of arithmetic error C6 exists to
 * prevent.</p>
 *
 * <p><strong>Insertion order is preserved on both read and write</strong>, so two equal
 * objects serialise to identical bytes and a round-trip test can compare strings.</p>
 *
 * <p>Instances are immutable; every mutator returns a copy. A {@code null} value inside
 * the tree means "explicitly absent", which JSON has and this product's absence-is-
 * {@code Optional} convention does not.</p>
 */
public final class JsonObject {

    private final Map<String, Object> members;

    private JsonObject(Map<String, Object> members) {
        this.members = Collections.unmodifiableMap(members);
    }

    /**
     * @param text a complete JSON document
     * @return the decoded object
     * @throws XsozContractException if the text is absent, malformed, or is not a JSON
     *                               object. There is no lenient mode: a config file this
     *                               class cannot read exactly is a file the recovery path
     *                               (C5.4) must own, not a file to guess at.
     */
    public static JsonObject parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new XsozContractException("Cannot parse an empty document as JSON.");
        }
        Object parsed = new JsonParser(text).parseDocument();
        if (parsed instanceof JsonObject) {
            return (JsonObject) parsed;
        }
        throw new XsozContractException(
                "Expected a JSON object at the root, found " + describe(parsed) + ".");
    }

    /**
     * @param members the initial members, in order
     * @return a new object holding a defensive copy of {@code members}
     */
    public static JsonObject of(Map<String, Object> members) {
        return new JsonObject(new LinkedHashMap<String, Object>(members));
    }

    /** @return a new empty builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return an object with no members */
    public static JsonObject empty() {
        return new JsonObject(new LinkedHashMap<String, Object>());
    }

    /** @return the member names, in document order */
    public Set<String> keys() {
        return members.keySet();
    }

    /** @return the number of members */
    public int size() {
        return members.size();
    }

    /**
     * @param key the member name
     * @return whether the member is present, even if its value is JSON null
     */
    public boolean has(String key) {
        return members.containsKey(key);
    }

    /**
     * @param key the member name
     * @return the raw value: a {@link String}, {@link Long}, {@link Double},
     *         {@link Boolean}, {@code JsonObject}, {@code List} or {@code null}
     */
    public Object raw(String key) {
        return members.get(key);
    }

    /**
     * @param key the member name
     * @return the string value
     * @throws XsozContractException if the member is absent or is not a string
     */
    public String getString(String key) {
        Object value = require(key);
        if (!(value instanceof String)) {
            throw new XsozContractException(
                    "Expected a string at \"" + key + "\", found " + describe(value) + ".");
        }
        return (String) value;
    }

    /**
     * @param key      the member name
     * @param fallback the value to return when the member is absent
     * @return the string value, or {@code fallback} when absent
     */
    public String getString(String key, String fallback) {
        Object value = members.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    /**
     * @param key the member name
     * @return the string value, or empty when absent or not a string
     */
    public Optional<String> optString(String key) {
        Object value = members.get(key);
        return value instanceof String ? Optional.of((String) value) : Optional.<String>empty();
    }

    /**
     * @param key the member name
     * @return the value narrowed to {@code int}
     * @throws XsozContractException if the member is absent, is not a number, or does not
     *                               fit in an {@code int}
     */
    public int getInt(String key) {
        long value = requireLong(key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new XsozContractException(
                    "The value at \"" + key + "\" is " + value + ", which does not fit in a "
                            + "32-bit int. Narrowing it silently would change the number.");
        }
        return (int) value;
    }

    /**
     * @param key      the member name
     * @param fallback the value to return when the member is absent or is not a number
     * @return the value narrowed to {@code int}, or {@code fallback}
     */
    public int getInt(String key, int fallback) {
        Object value = members.get(key);
        if (value instanceof Number) {
            double asDouble = ((Number) value).doubleValue();
            if (asDouble == Math.rint(asDouble)
                    && asDouble >= Integer.MIN_VALUE && asDouble <= Integer.MAX_VALUE) {
                return (int) asDouble;
            }
        }
        return fallback;
    }

    /**
     * @param key the member name
     * @return the numeric value as a {@code double}, integral or not
     * @throws XsozContractException if the member is absent or is not a number
     */
    public double getDouble(String key) {
        Object value = require(key);
        if (!(value instanceof Number)) {
            throw new XsozContractException(
                    "Expected a number at \"" + key + "\", found " + describe(value) + ".");
        }
        return ((Number) value).doubleValue();
    }

    /**
     * @param key      the member name
     * @param fallback the value to return when the member is absent or is not a number
     * @return the numeric value, or {@code fallback}
     */
    public double getDouble(String key, double fallback) {
        Object value = members.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    /**
     * @param key the member name
     * @return the boolean value
     * @throws XsozContractException if the member is absent or is not a boolean
     */
    public boolean getBoolean(String key) {
        Object value = require(key);
        if (!(value instanceof Boolean)) {
            throw new XsozContractException(
                    "Expected a boolean at \"" + key + "\", found " + describe(value) + ".");
        }
        return (Boolean) value;
    }

    /**
     * @param key      the member name
     * @param fallback the value to return when the member is absent or is not a boolean
     * @return the boolean value, or {@code fallback}
     */
    public boolean getBoolean(String key, boolean fallback) {
        Object value = members.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    /**
     * @param key the member name
     * @return the nested object
     * @throws XsozContractException if the member is absent or is not an object
     */
    public JsonObject getObject(String key) {
        Object value = require(key);
        if (!(value instanceof JsonObject)) {
            throw new XsozContractException(
                    "Expected an object at \"" + key + "\", found " + describe(value) + ".");
        }
        return (JsonObject) value;
    }

    /**
     * @param key the member name
     * @return the nested object, or empty when absent or not an object
     */
    public Optional<JsonObject> optObject(String key) {
        Object value = members.get(key);
        return value instanceof JsonObject ? Optional.of((JsonObject) value) : Optional.<JsonObject>empty();
    }

    /**
     * @param key the member name
     * @return the array, possibly empty
     * @throws XsozContractException if the member is present and is not an array
     */
    public List<Object> getArray(String key) {
        Object value = require(key);
        if (!(value instanceof List)) {
            throw new XsozContractException(
                    "Expected an array at \"" + key + "\", found " + describe(value) + ".");
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) value;
        return list;
    }

    /**
     * @param key the member name
     * @return the array's elements as strings; empty when the member is absent
     * @throws XsozContractException if the member is present, is not an array, or holds a
     *                               non-string element
     */
    public List<String> getStringArray(String key) {
        if (!has(key) || raw(key) == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (Object element : getArray(key)) {
            if (!(element instanceof String)) {
                throw new XsozContractException(
                        "Expected strings in \"" + key + "\", found " + describe(element) + ".");
            }
            out.add((String) element);
        }
        return out;
    }

    /**
     * @param key   the member name
     * @param value the value
     * @return a copy with {@code key} set, replacing any existing member
     */
    public JsonObject with(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>(members);
        copy.put(key, value);
        return new JsonObject(copy);
    }

    /**
     * @param keys the member names to remove
     * @return a copy without those members
     */
    public JsonObject without(String... keys) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>(members);
        for (String key : keys) {
            copy.remove(key);
        }
        return new JsonObject(copy);
    }

    /**
     * The canonical serialisation: two-space indent, insertion order, and
     * {@link Double#toString(double)} for doubles so that a parsed value re-emits to the
     * shortest text that reads back to the same bits.
     *
     * <p>Ends with a newline, so a document written straight to a file ends the way a text
     * file should - and so two equal objects serialise to byte-identical output, which is
     * what a round-trip test compares.</p>
     *
     * @return the JSON text, ending in a newline
     */
    public String toJson() {
        StringBuilder out = new StringBuilder(256);
        writeMembers(out, this, 0);
        return out.append('\n').toString();
    }

    @Override
    public String toString() {
        return "JsonObject" + members.keySet();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JsonObject && members.equals(((JsonObject) other).members);
    }

    @Override
    public int hashCode() {
        return members.hashCode();
    }

    // ---- writing -------------------------------------------------------------------------------

    private static void writeMembers(StringBuilder out, JsonObject object, int depth) {
        if (object.members.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        int index = 0;
        int size = object.members.size();
        for (Map.Entry<String, Object> entry : object.members.entrySet()) {
            indent(out, depth + 1);
            quote(out, entry.getKey());
            out.append(": ");
            writeValue(out, entry.getValue(), depth + 1);
            out.append(index == size - 1 ? "\n" : ",\n");
            index++;
        }
        indent(out, depth);
        out.append('}');
    }

    private static void writeValue(StringBuilder out, Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof JsonObject) {
            writeMembers(out, (JsonObject) value, depth);
        } else if (value instanceof List) {
            writeArray(out, (List<?>) value, depth);
        } else if (value instanceof String) {
            quote(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value.toString());
        } else if (isIntegral(value)) {
            out.append(Long.toString(((Number) value).longValue()));
        } else if (value instanceof Number) {
            out.append(Double.toString(((Number) value).doubleValue()));
        } else {
            throw new XsozContractException(
                    "Cannot serialise a " + value.getClass().getName()
                            + " into JSON. JsonObject holds JSON types only.");
        }
    }

    private static void writeArray(StringBuilder out, List<?> values, int depth) {
        if (values.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int i = 0; i < values.size(); i++) {
            indent(out, depth + 1);
            writeValue(out, values.get(i), depth + 1);
            out.append(i == values.size() - 1 ? "\n" : ",\n");
        }
        indent(out, depth);
        out.append(']');
    }

    private static boolean isIntegral(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte;
    }

    private static void indent(StringBuilder out, int depth) {
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
    }

    private static void quote(StringBuilder out, String text) {
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
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
    }

    // ---- reading -------------------------------------------------------------------------------

    private Object require(String key) {
        if (!members.containsKey(key)) {
            throw new XsozContractException("No member \"" + key + "\". Present: " + members.keySet());
        }
        return members.get(key);
    }

    private long requireLong(String key) {
        Object value = require(key);
        if (!(value instanceof Number)) {
            throw new XsozContractException(
                    "Expected an integer at \"" + key + "\", found " + describe(value) + ".");
        }
        double asDouble = ((Number) value).doubleValue();
        if (asDouble != Math.rint(asDouble)
                || asDouble < Long.MIN_VALUE || asDouble > Long.MAX_VALUE) {
            throw new XsozContractException(
                    "The value at \"" + key + "\" is " + asDouble + ", which is not an integer.");
        }
        return (long) asDouble;
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "a string";
        }
        if (value instanceof Boolean) {
            return "a boolean";
        }
        if (value instanceof Number) {
            return "the number " + value;
        }
        if (value instanceof List) {
            return "an array";
        }
        if (value instanceof JsonObject) {
            return "an object";
        }
        return value.getClass().getSimpleName();
    }

    /** Builds a {@link JsonObject} one member at a time, preserving insertion order. */
    public static final class Builder {

        private final Map<String, Object> members = new LinkedHashMap<String, Object>();

        private Builder() {
        }

        /**
         * @param key   the member name
         * @param value a JSON scalar, a {@code JsonObject} or a {@code List} of them
         * @return this builder
         */
        public Builder put(String key, Object value) {
            members.put(key, value);
            return this;
        }

        /**
         * @param key   the member name
         * @param value the string value
         * @return this builder
         */
        public Builder put(String key, String value) {
            return put(key, (Object) value);
        }

        /**
         * @param key   the member name
         * @param value the integer value
         * @return this builder
         */
        public Builder put(String key, long value) {
            return put(key, (Object) Long.valueOf(value));
        }

        /**
         * @param key   the member name
         * @param value the real value
         * @return this builder
         */
        public Builder put(String key, double value) {
            return put(key, (Object) Double.valueOf(value));
        }

        /**
         * @param key   the member name
         * @param value the boolean value
         * @return this builder
         */
        public Builder put(String key, boolean value) {
            return put(key, (Object) Boolean.valueOf(value));
        }

        /**
         * @param key  the member name
         * @param rows the array elements
         * @return this builder
         */
        public Builder putArray(String key, List<?> rows) {
            return put(key, new ArrayList<Object>(rows));
        }

        /** @return the built, immutable object */
        public JsonObject build() {
            return new JsonObject(new LinkedHashMap<String, Object>(members));
        }
    }
}
