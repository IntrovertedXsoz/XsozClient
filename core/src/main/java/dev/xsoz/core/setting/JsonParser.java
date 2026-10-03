package dev.xsoz.core.setting;

import dev.xsoz.core.XsozContractException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict, allocation-light recursive-descent JSON reader.
 *
 * <p><strong>No trailing commas, no comments, no unquoted keys, no single quotes.</strong>
 * A hand-written lenient parser is how a config file ends up with two spellings of the
 * same key, and a file with two spellings of the same key has no defined meaning. If
 * this class cannot read a file exactly, the file goes down the recovery path of
 * contracts.md C5.4, which preserves the bytes and tells the player.</p>
 *
 * <p>Package-private: {@link JsonObject} is the only entry point.</p>
 */
final class JsonParser {

    private final String text;
    private int position;

    JsonParser(String text) {
        this.text = text;
    }

    /**
     * @return the decoded value: a {@link Map}, {@link List}, {@link String},
     *         {@link Long}, {@link Double}, {@link Boolean} or {@code null}
     * @throws XsozContractException on any malformed input, naming the offset
     */
    Object parseDocument() {
        skipWhitespace();
        Object value = parseValue();
        skipWhitespace();
        if (position < text.length()) {
            throw failure("trailing content after the top-level value");
        }
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (position >= text.length()) {
            throw failure("the document ends where a value was expected");
        }
        char c = text.charAt(position);
        switch (c) {
            case '{':
                return JsonObject.of(parseObject());
            case '[':
                return parseArray();
            case '"':
                return parseString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return parseNumber();
                }
                throw failure("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> parseObject() {
        expectRaw('{');
        Map<String, Object> members = new LinkedHashMap<String, Object>();
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return members;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw failure("expected a quoted member name");
            }
            String key = parseString();
            if (members.containsKey(key)) {
                throw failure("member \"" + key + "\" appears twice; a repeated key has no "
                        + "defined meaning");
            }
            skipWhitespace();
            expectRaw(':');
            members.put(key, parseValue());
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                position++;
                continue;
            }
            if (next == '}') {
                position++;
                return members;
            }
            throw failure("expected ',' or '}' after a member value");
        }
    }

    private List<Object> parseArray() {
        expectRaw('[');
        List<Object> elements = new ArrayList<Object>();
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return elements;
        }
        while (true) {
            elements.add(parseValue());
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                position++;
                continue;
            }
            if (next == ']') {
                position++;
                return elements;
            }
            throw failure("expected ',' or ']' after an array element");
        }
    }

    private String parseString() {
        expectRaw('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (position >= text.length()) {
                throw failure("a string is not terminated");
            }
            char c = text.charAt(position++);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                out.append(parseEscape());
            } else {
                out.append(c);
            }
        }
    }

    private char parseEscape() {
        if (position >= text.length()) {
            throw failure("a backslash at the end of the document");
        }
        char c = text.charAt(position++);
        switch (c) {
            case '"':
                return '"';
            case '\\':
                return '\\';
            case '/':
                return '/';
            case 'b':
                return '\b';
            case 'f':
                return '\f';
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case 'u':
                if (position + 4 > text.length()) {
                    throw failure("a truncated \\u escape");
                }
                String hex = text.substring(position, position + 4);
                position += 4;
                try {
                    return (char) Integer.parseInt(hex, 16);
                } catch (NumberFormatException e) {
                    throw failure("\\u" + hex + " is not a hex escape");
                }
            default:
                throw failure("unknown escape \\" + c);
        }
    }

    private Object parseNumber() {
        int start = position;
        if (peek() == '-') {
            position++;
        }
        while (position < text.length() && isDigit(text.charAt(position))) {
            position++;
        }
        boolean fractional = false;
        if (position < text.length() && text.charAt(position) == '.') {
            fractional = true;
            position++;
            while (position < text.length() && isDigit(text.charAt(position))) {
                position++;
            }
        }
        if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
            fractional = true;
            position++;
            if (position < text.length() && (text.charAt(position) == '+' || text.charAt(position) == '-')) {
                position++;
            }
            while (position < text.length() && isDigit(text.charAt(position))) {
                position++;
            }
        }
        String literal = text.substring(start, position);
        try {
            if (fractional) {
                return Double.valueOf(literal);
            }
            return Long.valueOf(literal);
        } catch (NumberFormatException e) {
            throw failure("'" + literal + "' is not a number");
        }
    }

    private void expect(String literal) {
        if (!text.startsWith(literal, position)) {
            throw failure("expected the literal " + literal);
        }
        position += literal.length();
    }

    private void expectRaw(char c) {
        if (peek() != c) {
            throw failure("expected '" + c + "'");
        }
        position++;
    }

    private char peek() {
        if (position >= text.length()) {
            throw failure("the document ends unexpectedly");
        }
        return text.charAt(position);
    }

    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private XsozContractException failure(String reason) {
        return new XsozContractException(
                "Malformed JSON at offset " + position + ": " + reason
                        + ". A config file that cannot be read exactly is a file the recovery "
                        + "path (C5.4) owns, not a file to guess at.");
    }
}
