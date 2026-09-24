package dev.tachyonscript.api.value;

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
     * same canonical forms as their primitive counterparts.
     */
    public static String toString(Object value) {
        return switch (value) {
            case null -> "null";
            case Double d -> toString((double) d);
            case Float f -> toString((float) f);
            default -> value.toString();
        };
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
