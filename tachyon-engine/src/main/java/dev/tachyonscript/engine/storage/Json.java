package dev.tachyonscript.engine.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON writer and reader for stored values. The tree uses {@code null},
 * {@link Boolean}, {@link Long} (integral numbers), {@link Double}, {@link String},
 * {@link List} and {@link Map} (string keys, insertion order).
 */
final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ writing

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case Boolean b -> out.append(b ? "true" : "false");
            case Long l -> out.append(l);
            case Integer i -> out.append(i);
            case Double d -> {
                if (Double.isFinite(d)) {
                    out.append(Double.toString(d));
                } else {
                    // JSON has no NaN or infinities: store them as strings.
                    string(Double.toString(d), out);
                }
            }
            case String s -> string(s, out);
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(list.get(i), out);
                }
                out.append(']');
            }
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    string(String.valueOf(entry.getKey()), out);
                    out.append(':');
                    write(entry.getValue(), out);
                }
                out.append('}');
            }
            default -> throw new IllegalArgumentException("Cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void string(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ------------------------------------------------------------------ reading

    static Object read(String text) {
        Reader reader = new Reader(text);
        Object value = reader.value();
        reader.skipSpace();
        if (reader.position != text.length()) {
            throw reader.error("unexpected text after the value");
        }
        return value;
    }

    private static final class Reader {
        private final String text;
        private int position;

        Reader(String text) {
            this.text = text;
        }

        Object value() {
            skipSpace();
            if (position >= text.length()) {
                throw error("unexpected end");
            }
            char c = text.charAt(position);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            position++;
            Map<String, Object> map = new LinkedHashMap<>();
            skipSpace();
            if (peek() == '}') {
                position++;
                return map;
            }
            while (true) {
                skipSpace();
                String key = string();
                skipSpace();
                expect(':');
                map.put(key, value());
                skipSpace();
                if (peek() == ',') {
                    position++;
                } else {
                    expect('}');
                    return map;
                }
            }
        }

        private List<Object> array() {
            position++;
            List<Object> list = new ArrayList<>();
            skipSpace();
            if (peek() == ']') {
                position++;
                return list;
            }
            while (true) {
                list.add(value());
                skipSpace();
                if (peek() == ',') {
                    position++;
                } else {
                    expect(']');
                    return list;
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (position >= text.length()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (position >= text.length()) {
                    throw error("unterminated escape");
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw error("bad unicode escape");
                        }
                        out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                    }
                    default -> throw error("bad escape \\" + escaped);
                }
            }
        }

        private Object number() {
            int start = position;
            boolean decimal = false;
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c == '.' || c == 'e' || c == 'E') {
                    decimal = true;
                } else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9'))) {
                    break;
                }
                position++;
            }
            if (start == position) {
                throw error("unexpected character '" + text.charAt(position) + "'");
            }
            String number = text.substring(start, position);
            try {
                return decimal ? (Object) Double.parseDouble(number) : (Object) Long.parseLong(number);
            } catch (NumberFormatException e) {
                throw error("bad number " + number);
            }
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, position)) {
                throw error("unexpected text");
            }
            position += word.length();
            return value;
        }

        private char peek() {
            return position < text.length() ? text.charAt(position) : '\0';
        }

        private void expect(char c) {
            if (peek() != c) {
                throw error("expected '" + c + "'");
            }
            position++;
        }

        void skipSpace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Invalid stored value at " + position + ": " + message);
        }
    }
}
