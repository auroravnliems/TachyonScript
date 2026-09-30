package dev.tachyonscript.runtime.value;

import dev.tachyonscript.api.type.ListType;
import dev.tachyonscript.api.type.MapType;
import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.value.Values;

import java.util.List;
import java.util.Map;

/**
 * Text of a value whose static type is known at run time only as a {@link Type}, such as a
 * record field: durations and instants (stored as milliseconds) read with units and dates, and
 * the elements of lists and maps read the same way, like templates show them.
 */
public final class ValueText {

    private ValueText() {
    }

    public static String of(Object value, Type type) {
        if (value == null) {
            return "null";
        }
        Type plain = type.nonNullable();
        if (plain == PrimitiveType.DURATION && value instanceof Long millis) {
            return Values.durationToString(millis);
        }
        if (plain == PrimitiveType.INSTANT && value instanceof Long millis) {
            return Values.instantToString(millis);
        }
        if (plain instanceof ListType list && value instanceof List<?> elements) {
            Object[] items = elements instanceof ScriptList script ? script.snapshot() : snapshot(elements);
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < items.length; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(items[i] == value ? "(this list)" : of(items[i], list.element()));
            }
            return out.append(']').toString();
        }
        if (plain instanceof MapType map && value instanceof Map<?, ?> entries) {
            Object[] flat = entries instanceof ScriptMap script ? script.flatSnapshot() : flat(entries);
            StringBuilder out = new StringBuilder("{");
            for (int i = 0; i < flat.length; i += 2) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(of(flat[i], map.key())).append(": ")
                        .append(flat[i + 1] == value ? "(this map)" : of(flat[i + 1], map.value()));
            }
            return out.append('}').toString();
        }
        return Values.toString(value);
    }

    private static Object[] snapshot(List<?> list) {
        synchronized (list) {
            return list.toArray();
        }
    }

    private static Object[] flat(Map<?, ?> map) {
        synchronized (map) {
            Object[] flat = new Object[map.size() * 2];
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                flat[i++] = entry.getKey();
                flat[i++] = entry.getValue();
            }
            return flat;
        }
    }
}
