package dev.tachyonscript.stdlib.support;

import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.value.Values;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Helpers of the core standard library area (time, formatting, random numbers, text and
 * JSON), used by the generated bindings. Everything here needs only the JDK and is safe to
 * call from any thread.
 */
public final class Core {

    /** Parsed patterns are cached: scripts usually use a handful of constant patterns. */
    private static final Map<String, DateTimeFormatter> FORMATTERS = new ConcurrentHashMap<>();
    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 512;
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private Core() {
    }

    // ------------------------------------------------------------------ time

    /** The current date and time in the server's time zone. */
    public static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.systemDefault());
    }

    public static String format(long instant, String pattern, String zone) {
        ZoneId zoneId = zone == null ? ZoneId.systemDefault() : zone(zone);
        try {
            return formatter(pattern).format(Instant.ofEpochMilli(instant).atZone(zoneId));
        } catch (DateTimeException e) {
            throw new ScriptError("Cannot format a time with the pattern '" + pattern + "': " + e.getMessage());
        }
    }

    /** Reads a time; missing fields default to the start of the day / the current year. Null if it does not match. */
    public static Long parse(String text, String pattern) {
        TemporalAccessor parsed;
        try {
            parsed = formatter(pattern).parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        int year = field(parsed, ChronoField.YEAR, today.getYear());
        int month = field(parsed, ChronoField.MONTH_OF_YEAR, parsed.isSupported(ChronoField.YEAR) ? 1 : today.getMonthValue());
        int day = field(parsed, ChronoField.DAY_OF_MONTH, parsed.isSupported(ChronoField.MONTH_OF_YEAR) ? 1 : today.getDayOfMonth());
        int hour = field(parsed, ChronoField.HOUR_OF_DAY, 0);
        int minute = field(parsed, ChronoField.MINUTE_OF_HOUR, 0);
        int second = field(parsed, ChronoField.SECOND_OF_MINUTE, 0);
        try {
            return LocalDateTime.of(year, month, day, hour, minute, second).atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static int field(TemporalAccessor parsed, ChronoField field, int fallback) {
        return parsed.isSupported(field) ? parsed.get(field) : fallback;
    }

    public static long of(int year, int month, int day, int hour, int minute) {
        try {
            return LocalDateTime.of(year, month, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeException e) {
            throw new ScriptError("Invalid date: " + e.getMessage());
        }
    }

    public static long startOfDay(long instant) {
        ZonedDateTime time = Instant.ofEpochMilli(instant).atZone(ZoneId.systemDefault());
        return time.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public static long nextDaily(int hour, int minute) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new ScriptError("Invalid time of day " + hour + ":" + minute + " (hours go from 0 to 23, minutes from 0 to 59).");
        }
        ZonedDateTime now = now();
        ZonedDateTime next = now.toLocalDate().atTime(LocalTime.of(hour, minute)).atZone(ZoneId.systemDefault());
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return next.toInstant().toEpochMilli();
    }

    private static ZoneId zone(String zone) {
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new ScriptError("Unknown time zone '" + zone + "' (use names such as 'Europe/Paris' or 'UTC').");
        }
    }

    private static DateTimeFormatter formatter(String pattern) {
        DateTimeFormatter cached = FORMATTERS.get(pattern);
        if (cached != null) {
            return cached;
        }
        DateTimeFormatter created;
        try {
            created = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new ScriptError("Invalid time pattern '" + pattern + "': " + e.getMessage());
        }
        if (FORMATTERS.size() < CACHE_LIMIT) {
            FORMATTERS.put(pattern, created);
        }
        return created;
    }

    // ------------------------------------------------------------------ formatting

    public static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    public static String number(double value, int decimals) {
        return String.format(Locale.ROOT, "%,." + Math.max(0, Math.min(10, decimals)) + "f", value);
    }

    public static String decimal(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + Math.max(0, Math.min(10, decimals)) + "f", value);
    }

    public static String compact(double value) {
        double magnitude = Math.abs(value);
        String[] suffixes = {"", "K", "M", "B", "T", "Q"};
        int index = 0;
        while (magnitude >= 1000 && index < suffixes.length - 1) {
            magnitude /= 1000;
            index++;
        }
        double rounded = Math.round(magnitude * 10) / 10.0;
        if (rounded >= 1000 && index < suffixes.length - 1) {
            rounded = Math.round(rounded / 100) / 10.0;
            index++;
        }
        return (value < 0 ? "-" : "") + Values.toString(rounded) + suffixes[index];
    }

    public static String percent(double value) {
        return Values.toString(Math.round(value * 1000) / 10.0) + "%";
    }

    public static String roman(int value) {
        if (value <= 0 || value >= 4000) {
            return Integer.toString(value);
        }
        int[] numbers = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] letters = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        int rest = value;
        for (int i = 0; i < numbers.length; i++) {
            while (rest >= numbers[i]) {
                out.append(letters[i]);
                rest -= numbers[i];
            }
        }
        return out.toString();
    }

    public static String ordinal(int value) {
        int lastTwo = Math.abs(value % 100);
        int last = Math.abs(value % 10);
        String suffix = lastTwo >= 11 && lastTwo <= 13 ? "th" : switch (last) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return value + suffix;
    }

    /** "1 day, 2 hours, 5 seconds"; milliseconds only below one second. */
    public static String durationWords(long millis) {
        if (millis == 0) {
            return "0 seconds";
        }
        String sign = millis < 0 ? "-" : "";
        long rest = millis == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(millis);
        if (rest < 1000) {
            return sign + rest + (rest == 1 ? " millisecond" : " milliseconds");
        }
        long[] units = {86_400_000L, 3_600_000L, 60_000L, 1_000L};
        String[] names = {"day", "hour", "minute", "second"};
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < units.length; i++) {
            long amount = rest / units[i];
            if (amount > 0) {
                parts.add(amount + " " + names[i] + (amount == 1 ? "" : "s"));
                rest -= amount * units[i];
            }
        }
        return sign + String.join(", ", parts);
    }

    /** "05:09", "1:02:03" (hours only when needed). */
    public static String timer(long millis) {
        String sign = millis < 0 ? "-" : "";
        long seconds = Math.abs(millis / 1000);
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%s%d:%02d:%02d", sign, hours, minutes, secs);
        }
        return String.format(Locale.ROOT, "%s%02d:%02d", sign, minutes, secs);
    }

    public static String bytes(long value) {
        if (Math.abs(value) < 1024) {
            return value + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB", "EB"};
        double size = value;
        int index = -1;
        while (Math.abs(size) >= 1024 && index < units.length - 1) {
            size /= 1024;
            index++;
        }
        return Values.toString(Math.round(size * 10) / 10.0) + " " + units[index];
    }

    // ------------------------------------------------------------------ random numbers

    public static int randomInt(int min, int max) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        return (int) ThreadLocalRandom.current().nextLong(low, (long) high + 1);
    }

    public static long randomLong(long min, long max) {
        long low = Math.min(min, max);
        long high = Math.max(min, max);
        if (high == Long.MAX_VALUE) {
            return low == Long.MIN_VALUE ? ThreadLocalRandom.current().nextLong()
                    : ThreadLocalRandom.current().nextLong(low - 1, high) + 1;
        }
        return ThreadLocalRandom.current().nextLong(low, high + 1);
    }

    public static double randomDouble(double min, double max) {
        if (min == max) {
            return min;
        }
        return ThreadLocalRandom.current().nextDouble(Math.min(min, max), Math.max(min, max));
    }

    public static String randomString(int length) {
        if (length < 0 || length > 4096) {
            throw new ScriptError("random.string length must be between 0 and 4096, not " + length + ".");
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length()));
        }
        return new String(out);
    }

    public static int weighted(List<Object> weights) {
        double total = 0;
        for (Object weight : weights) {
            double value = weight == null ? 0 : ((Number) weight).doubleValue();
            if (value > 0) {
                total += value;
            }
        }
        if (total <= 0) {
            throw new ScriptError("random.weighted needs at least one positive weight.");
        }
        double pick = ThreadLocalRandom.current().nextDouble(total);
        for (int i = 0; i < weights.size(); i++) {
            Object weight = weights.get(i);
            double value = weight == null ? 0 : ((Number) weight).doubleValue();
            if (value > 0) {
                pick -= value;
                if (pick < 0) {
                    return i;
                }
            }
        }
        return weights.size() - 1;
    }

    // ------------------------------------------------------------------ numbers

    public static UUID uuid(String text) {
        try {
            return text.length() == 36 ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static double roundTo(double value, int decimals) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        return BigDecimal.valueOf(value).setScale(Math.max(0, Math.min(15, decimals)), RoundingMode.HALF_UP).doubleValue();
    }

    public static int floorMod(int value, int divisor) {
        if (divisor == 0) {
            throw new ArithmeticException("/ by zero");
        }
        return Math.floorMod(value, divisor);
    }

    public static long floorMod(long value, long divisor) {
        if (divisor == 0) {
            throw new ArithmeticException("/ by zero");
        }
        return Math.floorMod(value, divisor);
    }

    // ------------------------------------------------------------------ text

    public static List<Object> split(String text, String separator, int limit) {
        List<Object> parts = new ArrayList<>();
        if (separator.isEmpty()) {
            text.codePoints().forEach(c -> parts.add(new String(Character.toChars(c))));
            return parts;
        }
        int start = 0;
        while (true) {
            if (limit > 0 && parts.size() == limit - 1) {
                break;
            }
            int found = text.indexOf(separator, start);
            if (found < 0) {
                break;
            }
            parts.add(text.substring(start, found));
            start = found + separator.length();
        }
        parts.add(text.substring(start));
        return parts;
    }

    public static String substring(String text, int start, int end) {
        if (start < 0 || end > text.length() || start > end) {
            throw new ScriptError("substring(" + start + ", " + end + ") is out of range for a text of length "
                    + text.length() + ".");
        }
        return text.substring(start, end);
    }

    public static String charAt(String text, int index) {
        if (index < 0 || index >= text.length()) {
            throw new ScriptError("Index " + index + " is out of range for a text of length " + text.length() + ".");
        }
        return String.valueOf(text.charAt(index));
    }

    public static List<Object> chars(String text) {
        List<Object> result = new ArrayList<>(text.length());
        text.codePoints().forEach(c -> result.add(new String(Character.toChars(c))));
        return result;
    }

    public static String repeat(String text, int count) {
        if (count < 0) {
            throw new ScriptError("Cannot repeat a text " + count + " times.");
        }
        if ((long) text.length() * count > 1_000_000) {
            throw new ScriptError("The repeated text would be longer than 1,000,000 characters.");
        }
        return text.repeat(count);
    }

    public static String pad(String text, int length, String fill, boolean left) {
        if (fill.isEmpty() || text.length() >= length) {
            return text;
        }
        if (length > 1_000_000) {
            throw new ScriptError("Cannot pad a text to more than 1,000,000 characters.");
        }
        StringBuilder padding = new StringBuilder();
        while (padding.length() < length - text.length()) {
            padding.append(fill);
        }
        padding.setLength(length - text.length());
        return left ? padding + text : text + padding;
    }

    public static String capitalize(String text) {
        if (text.isEmpty()) {
            return text;
        }
        int first = text.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(first))) + text.substring(Character.charCount(first));
    }

    public static String titleCase(String text) {
        String[] words = text.replace('_', ' ').trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(capitalize(word.toLowerCase(Locale.ROOT)));
        }
        return out.toString();
    }

    public static Long toLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Boolean toBool(String text) {
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> Boolean.TRUE;
            case "false", "no", "off", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    public static boolean containsIgnoreCase(String text, String part) {
        return text.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    public static int count(String text, String part) {
        if (part.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = text.indexOf(part);
        while (index >= 0) {
            count++;
            index = text.indexOf(part, index + part.length());
        }
        return count;
    }

    public static List<Object> lines(String text) {
        return new ArrayList<>(text.lines().toList());
    }

    public static String truncate(String text, int length) {
        if (length < 0) {
            throw new ScriptError("Cannot truncate a text to " + length + " characters.");
        }
        if (text.length() <= length) {
            return text;
        }
        return length <= 3 ? text.substring(0, length) : text.substring(0, length - 3) + "...";
    }

    /** A compiled regular expression (cached); invalid expressions are script errors. */
    public static Pattern pattern(String regex) {
        Pattern cached = PATTERNS.get(regex);
        if (cached != null) {
            return cached;
        }
        Pattern created;
        try {
            created = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new ScriptError("Invalid regular expression '" + regex + "': " + e.getDescription());
        }
        if (PATTERNS.size() < CACHE_LIMIT) {
            PATTERNS.put(regex, created);
        }
        return created;
    }

    public static String find(String text, String regex) {
        Matcher matcher = pattern(regex).matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    public static List<Object> findAll(String text, String regex) {
        Matcher matcher = pattern(regex).matcher(text);
        List<Object> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    // ------------------------------------------------------------------ JSON

    public static Object parseJson(String text) {
        return new JsonReader(text).document();
    }

    /** A JSON value as an object; null (or an error when {@code required}) if it is not one. */
    @SuppressWarnings("unchecked")
    public static Map<Object, Object> jsonMap(Object value, boolean required) {
        if (value instanceof Map<?, ?> map) {
            return (Map<Object, Object>) map;
        }
        if (required) {
            throw new ScriptError("The JSON is not an object (it does not start with '{').");
        }
        return null;
    }

    /** A JSON value as an array; null (or an error when {@code required}) if it is not one. */
    @SuppressWarnings("unchecked")
    public static List<Object> jsonList(Object value, boolean required) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        if (required) {
            throw new ScriptError("The JSON is not an array (it does not start with '[').");
        }
        return null;
    }

    public static String writeJson(Object value, boolean pretty) {
        StringBuilder out = new StringBuilder();
        JsonWriter.write(value, out, pretty, 0);
        return out.toString();
    }
}
