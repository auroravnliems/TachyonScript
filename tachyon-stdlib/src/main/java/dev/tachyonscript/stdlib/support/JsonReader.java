package dev.tachyonscript.stdlib.support;

import dev.tachyonscript.api.natives.ScriptError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small strict JSON reader for {@code json.parse}: objects become insertion-ordered maps,
 * arrays lists, whole numbers {@code Long}, other numbers {@code Double}.
 */
final class JsonReader {

    private static final int MAX_DEPTH = 512;

    private final String text;
    private int position;
    private int depth;

    JsonReader(String text) {
        this.text = text;
    }

    Object document() {
        skipSpace();
        Object value = value();
        skipSpace();
        if (position < text.length()) {
            throw error("Unexpected '" + text.charAt(position) + "' after the JSON value");
        }
        return value;
    }

    private Object value() {
        if (position >= text.length()) {
            throw error("Unexpected end of the JSON text");
        }
        char c = text.charAt(position);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield number();
                }
                throw error("Unexpected '" + c + "'");
            }
        };
    }

    private Map<Object, Object> object() {
        enter();
        position++;
        Map<Object, Object> map = new LinkedHashMap<>();
        skipSpace();
        if (peek() == '}') {
            position++;
            depth--;
            return map;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw error("Expected a key in quotes");
            }
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            map.put(key, value());
            skipSpace();
            char next = peek();
            position++;
            if (next == '}') {
                depth--;
                return map;
            }
            if (next != ',') {
                throw error("Expected ',' or '}' in an object");
            }
        }
    }

    private List<Object> array() {
        enter();
        position++;
        List<Object> list = new ArrayList<>();
        skipSpace();
        if (peek() == ']') {
            position++;
            depth--;
            return list;
        }
        while (true) {
            skipSpace();
            list.add(value());
            skipSpace();
            char next = peek();
            position++;
            if (next == ']') {
                depth--;
                return list;
            }
            if (next != ',') {
                throw error("Expected ',' or ']' in an array");
            }
        }
    }

    private String string() {
        position++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (position >= text.length()) {
                throw error("Unterminated string");
            }
            char c = text.charAt(position++);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                if (position >= text.length()) {
                    throw error("Unterminated string");
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw error("Invalid \\u escape");
                        }
                        try {
                            out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("Invalid \\u escape");
                        }
                        position += 4;
                    }
                    default -> throw error("Invalid escape \\" + escaped);
                }
            } else if (c < 0x20) {
                throw error("Control character in a string");
            } else {
                out.append(c);
            }
        }
    }

    private Object number() {
        int start = position;
        if (peek() == '-') {
            position++;
        }
        boolean decimal = false;
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c >= '0' && c <= '9') {
                position++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                decimal = true;
                position++;
            } else {
                break;
            }
        }
        String number = text.substring(start, position);
        try {
            if (!decimal) {
                try {
                    return Long.parseLong(number);
                } catch (NumberFormatException tooLarge) {
                    return Double.parseDouble(number);
                }
            }
            return Double.parseDouble(number);
        } catch (NumberFormatException e) {
            throw error("Invalid number '" + number + "'");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, position)) {
            throw error("Unexpected '" + text.charAt(position) + "'");
        }
        position += word.length();
        return value;
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw error("The JSON is nested too deeply");
        }
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("Expected '" + c + "'");
        }
        position++;
    }

    private char peek() {
        return position < text.length() ? text.charAt(position) : '\0';
    }

    private void skipSpace() {
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                return;
            }
            position++;
        }
    }

    private ScriptError error(String message) {
        return new ScriptError("Invalid JSON: " + message + " at character " + (position + 1) + ".");
    }
}
