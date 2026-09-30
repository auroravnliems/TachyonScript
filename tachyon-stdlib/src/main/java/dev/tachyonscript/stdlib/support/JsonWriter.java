package dev.tachyonscript.stdlib.support;

import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.value.Values;

import java.util.Collection;
import java.util.Map;

/** Writes script values as JSON for {@code json.stringify} and {@code json.pretty}. */
final class JsonWriter {

    private static final int MAX_DEPTH = 512;

    private JsonWriter() {
    }

    static void write(Object value, StringBuilder out, boolean pretty, int depth) {
        if (depth > MAX_DEPTH) {
            throw new ScriptError("json.stringify: the value is nested too deeply (or contains itself).");
        }
        switch (value) {
            case null -> out.append("null");
            case Boolean bool -> out.append(bool);
            case Double number -> number(number, out);
            case Float number -> number(number.doubleValue(), out);
            case Number number -> out.append(number);
            case Map<?, ?> map -> object(map, out, pretty, depth);
            case Collection<?> list -> array(list, out, pretty, depth);
            default -> string(value instanceof String text ? text : Values.toString(value), out);
        }
    }

    private static void number(double number, StringBuilder out) {
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            out.append("null");
        } else if (number == Math.rint(number) && Math.abs(number) < 1e15) {
            out.append((long) number);
        } else {
            out.append(number);
        }
    }

    private static void object(Map<?, ?> map, StringBuilder out, boolean pretty, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, pretty, depth + 1);
            Object key = entry.getKey();
            string(key instanceof String text ? text : Values.toString(key), out);
            out.append(pretty ? ": " : ":");
            write(entry.getValue(), out, pretty, depth + 1);
        }
        newline(out, pretty, depth);
        out.append('}');
    }

    private static void array(Collection<?> list, StringBuilder out, boolean pretty, int depth) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        boolean first = true;
        for (Object element : list) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, pretty, depth + 1);
            write(element, out, pretty, depth + 1);
        }
        newline(out, pretty, depth);
        out.append(']');
    }

    private static void newline(StringBuilder out, boolean pretty, int depth) {
        if (pretty) {
            out.append('\n');
            out.append("  ".repeat(depth));
        }
    }

    static void string(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
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
}
