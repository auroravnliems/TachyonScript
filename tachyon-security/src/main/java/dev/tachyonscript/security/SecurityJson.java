package dev.tachyonscript.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded strict JSON: duplicate keys, non-finite numbers and trailing content are rejected. */
public final class SecurityJson {
    private SecurityJson() { }

    public static Object parse(String text) {
        if (text.length() > 4_194_304) throw new IllegalArgumentException("JSON exceeds size limit");
        Reader reader = new Reader(text);
        Object result = reader.value(0);
        reader.space();
        if (reader.at != text.length()) throw reader.error();
        return result;
    }

    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected JSON object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Invalid JSON key");
            result.put(key, entry.getValue());
        }
        return result;
    }

    public static List<Object> array(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected JSON array");
        return new ArrayList<>(list);
    }

    public static String string(Map<String, Object> object, String key) {
        if (!(object.get(key) instanceof String result)) throw new IllegalArgumentException("Missing string: " + key);
        return result;
    }

    public static String optionalString(Map<String, Object> object, String key) {
        return object.containsKey(key) ? string(object, key) : "";
    }

    public static double number(Map<String, Object> object, String key) {
        if (!(object.get(key) instanceof Number value) || !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException("Missing finite number: " + key);
        return value.doubleValue();
    }

    public static int integer(Map<String, Object> object, String key) {
        double value = number(object, key);
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid integer: " + key);
        return (int) value;
    }

    public static boolean bool(Map<String, Object> object, String key) {
        if (!(object.get(key) instanceof Boolean result)) throw new IllegalArgumentException("Missing boolean: " + key);
        return result;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, int depth) {
        if (depth > 64) throw new IllegalArgumentException("JSON nesting limit");
        if (value == null) out.append("null");
        else if (value instanceof String string) {
            out.append('"');
            for (int i = 0; i < string.length(); i++) {
                char c = string.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            out.append('"');
        } else if (value instanceof Boolean) out.append(value);
        else if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("Non-finite JSON number");
            out.append(number);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean comma = false;
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("Invalid JSON key");
                if (comma) out.append(',');
                write(entry.getKey(), out, depth + 1);
                out.append(':');
                write(entry.getValue(), out, depth + 1);
                comma = true;
            }
            out.append('}');
        } else if (value instanceof Iterable<?> list) {
            out.append('[');
            boolean comma = false;
            for (Object item : list) {
                if (comma) out.append(',');
                write(item, out, depth + 1);
                comma = true;
            }
            out.append(']');
        } else throw new IllegalArgumentException("Unsupported JSON value");
    }

    private static final class Reader {
        private final String text;
        private int at;
        private int values;
        Reader(String text) { this.text = text; }
        IllegalArgumentException error() { return new IllegalArgumentException("Invalid JSON at offset " + at); }
        void space() {
            while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++;
        }
        boolean take(char c) {
            space();
            if (at < text.length() && text.charAt(at) == c) { at++; return true; }
            return false;
        }
        Object value(int depth) {
            if (depth > 64 || ++values > 100_000) throw error();
            space();
            if (at >= text.length()) throw error();
            char c = text.charAt(at);
            if (c == '"') return string();
            if (take('{')) {
                Map<String, Object> map = new LinkedHashMap<>();
                if (take('}')) return map;
                do {
                    space();
                    String key = string();
                    if (map.containsKey(key) || !take(':')) throw error();
                    map.put(key, value(depth + 1));
                    if (take('}')) return map;
                } while (take(','));
                throw error();
            }
            if (take('[')) {
                List<Object> list = new ArrayList<>();
                if (take(']')) return list;
                do {
                    list.add(value(depth + 1));
                    if (take(']')) return list;
                } while (take(','));
                throw error();
            }
            for (String keyword : List.of("true", "false", "null")) {
                if (text.startsWith(keyword, at)) {
                    at += keyword.length();
                    return switch (keyword) { case "true" -> true; case "false" -> false; default -> null; };
                }
            }
            int start = at;
            if (at < text.length() && text.charAt(at) == '-') at++;
            if (at < text.length() && text.charAt(at) == '0') at++;
            else digits();
            boolean decimal = false;
            if (at < text.length() && text.charAt(at) == '.') { at++; digits(); decimal = true; }
            if (at < text.length() && "eE".indexOf(text.charAt(at)) >= 0) {
                at++;
                if (at < text.length() && "+-".indexOf(text.charAt(at)) >= 0) at++;
                digits(); decimal = true;
            }
            try {
                String number = text.substring(start, at);
                if (!decimal) return Long.valueOf(number);
                double result = Double.parseDouble(number);
                if (!Double.isFinite(result)) throw error();
                return result;
            } catch (NumberFormatException e) { throw error(); }
        }
        void digits() {
            int start = at;
            while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
            if (at == start) throw error();
        }
        String string() {
            if (at >= text.length() || text.charAt(at++) != '"') throw error();
            StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return out.toString();
                if (c < 32) throw error();
                if (c == '\\') {
                    if (at >= text.length()) throw error();
                    char escape = text.charAt(at++);
                    c = switch (escape) {
                        case '"', '\\', '/' -> escape;
                        case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n';
                        case 'r' -> '\r'; case 't' -> '\t';
                        case 'u' -> unicode();
                        default -> throw error();
                    };
                }
                out.append(c);
            }
            throw error();
        }
        char unicode() {
            if (at + 4 > text.length()) throw error();
            int result = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(at++), 16);
                if (digit < 0) throw error();
                result = result * 16 + digit;
            }
            return (char) result;
        }
    }
}
