package dev.tachyonscript.api.value;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Canonical text forms of TachyonScript values.
 *
 * <p>String interpolation and {@code +} concatenation use these conversions both when the
 * compiler folds constants and when the runtime converts values, so a folded constant and a
 * computed value always print identically.
 *
 * <ul>
 *   <li>Integral floating-point values print without a fraction ({@code 20.0} prints as
 *       {@code 20}); other values use the shortest representation that round-trips.</li>
 *   <li>Durations print with units, e.g. {@code 1h 30m}, {@code 5s}, {@code 250ms}.</li>
 *   <li>{@code null} prints as {@code null}.</li>
 * </ul>
 */
public final class Values {

    /** Magnitude below which integral doubles are printed as plain integers. */
    private static final double INTEGRAL_LIMIT = 1e15;

    private Values() {
    }

    public static String toString(int value) {
        return Integer.toString(value);
    }

    public static String toString(long value) {
        return Long.toString(value);
    }

    public static String toString(boolean value) {
        return value ? "true" : "false";
    }

    public static String toString(double value) {
        if (value == Math.rint(value) && Math.abs(value) < INTEGRAL_LIMIT) {
            // -0.0 prints as 0: users never mean negative zero in messages.
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    public static String toString(float value) {
        if (value == Math.rint(value) && Math.abs(value) < INTEGRAL_LIMIT) {
            return Long.toString((long) value);
        }
        return Float.toString(value);
    }

    /**
     * Text form of a reference value ({@code null} → {@code "null"}). Boxed numbers use the
     * same canonical forms as their primitive counterparts; host objects (players, locations,
     * materials, ...) use their registered display (see {@link #registerDisplay}).
     */
    public static String toString(Object value) {
        return switch (value) {
            case null -> "null";
            case Double d -> toString((double) d);
            case Float f -> toString((float) f);
            case String s -> s;
            case java.util.List<?> list -> listToString(list);
            case Map<?, ?> map -> mapToString(map);
            default -> display(value);
        };
    }

    /** {@code [a, b, c]} with each element in its own text form (lists made by natives included). */
    private static String listToString(java.util.List<?> list) {
        Object[] items;
        synchronized (list) {
            items = list.toArray();
        }
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(items[i] == list ? "(this list)" : toString(items[i]));
        }
        return out.append(']').toString();
    }

    /** {@code {key: value, ...}} with keys and values in their own text forms. */
    private static String mapToString(Map<?, ?> map) {
        java.util.List<Map.Entry<?, ?>> entries = new java.util.ArrayList<>();
        synchronized (map) {
            entries.addAll(map.entrySet());
        }
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            Map.Entry<?, ?> entry = entries.get(i);
            out.append(toString(entry.getKey())).append(": ")
                    .append(entry.getValue() == map ? "(this map)" : toString(entry.getValue()));
        }
        return out.append('}').toString();
    }

    // ------------------------------------------------------------------ displays of host objects

    /** Displays registered per Java class, and the display resolved for each concrete class. */
    private static final Map<Class<?>, Function<Object, String>> DISPLAYS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Function<Object, String>>> RESOLVED = new ConcurrentHashMap<>();

    /**
     * Registers how values of a host class read in text, e.g. a player as its name. The engine
     * installs one per bound type that declares {@code toString()}, so a player inside a list
     * or a record reads like a player inserted on its own. The most specific registered class
     * wins; a display that fails falls back to {@code toString()}.
     */
    public static void registerDisplay(Class<?> type, Function<Object, String> display) {
        DISPLAYS.put(type, display);
        RESOLVED.clear();
    }

    private static String display(Object value) {
        Function<Object, String> display = RESOLVED.computeIfAbsent(value.getClass(), Values::resolveDisplay).orElse(null);
        if (display != null) {
            try {
                String text = display.apply(value);
                if (text != null) {
                    return text;
                }
            } catch (RuntimeException ignored) {
                // A display that cannot run here (e.g. not available on this platform): use the plain form.
            }
        }
        return value.toString();
    }

    private static Optional<Function<Object, String>> resolveDisplay(Class<?> type) {
        Class<?> best = null;
        for (Class<?> candidate : DISPLAYS.keySet()) {
            if (candidate.isAssignableFrom(type) && (best == null || best.isAssignableFrom(candidate))) {
                best = candidate;
            }
        }
        return best == null ? Optional.empty() : Optional.ofNullable(DISPLAYS.get(best));
    }

    private static final java.time.format.DateTimeFormatter INSTANT_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Formats an instant given in milliseconds since the epoch as local date and time of the
     * server, e.g. {@code 2026-09-25 18:30:00}.
     */
    public static String instantToString(long epochMillis) {
        return INSTANT_FORMAT.format(java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()));
    }

    /** Formats a duration given in milliseconds, e.g. {@code 1d 2h}, {@code 1m 30s}, {@code 250ms}. */
    public static String durationToString(long millis) {
        if (millis == 0) {
            return "0ms";
        }
        StringBuilder out = new StringBuilder();
        long remaining = millis;
        if (remaining < 0) {
            out.append('-');
            // Long.MIN_VALUE cannot be negated; it is far outside any meaningful duration.
            remaining = remaining == Long.MIN_VALUE ? Long.MAX_VALUE : -remaining;
        }
        long[] units = {86_400_000L, 3_600_000L, 60_000L, 1_000L, 1L};
        String[] names = {"d", "h", "m", "s", "ms"};
        boolean first = true;
        for (int i = 0; i < units.length; i++) {
            long amount = remaining / units[i];
            if (amount > 0) {
                if (!first) {
                    out.append(' ');
                }
                out.append(amount).append(names[i]);
                remaining -= amount * units[i];
                first = false;
            }
        }
        return out.toString();
    }
}
