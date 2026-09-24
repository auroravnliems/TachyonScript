package dev.tachyonscript.engine.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Profiling results, printable as the table shown by {@code /tys profile report}:
 *
 * <pre>
 * Script                Calls      Total       Avg
 * join.tys              12,481     22.4 ms     1.79 µs
 * </pre>
 */
public record ProfileReport(List<Profiler.Entry> entries, long durationNanos) {

    /** Aggregated row. */
    public record Row(String name, long calls, long totalNanos, long maxNanos) {
        public double averageNanos() {
            return calls == 0 ? 0 : (double) totalNanos / calls;
        }
    }

    public ProfileReport {
        entries = List.copyOf(entries);
    }

    /** One row per script, slowest first. */
    public List<Row> byScript() {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (Profiler.Entry entry : entries) {
            long[] sum = totals.computeIfAbsent(entry.script(), k -> new long[3]);
            sum[0] += entry.calls();
            sum[1] += entry.totalNanos();
            sum[2] = Math.max(sum[2], entry.maxNanos());
        }
        List<Row> rows = new ArrayList<>();
        totals.forEach((script, sum) -> rows.add(new Row(script, sum[0], sum[1], sum[2])));
        rows.sort(Comparator.comparingLong(Row::totalNanos).reversed());
        return rows;
    }

    /** One row per handler, slowest first. */
    public List<Row> byHandler() {
        List<Row> rows = new ArrayList<>();
        for (Profiler.Entry entry : entries) {
            rows.add(new Row(entry.script() + " " + entry.handler(), entry.calls(), entry.totalNanos(), entry.maxNanos()));
        }
        rows.sort(Comparator.comparingLong(Row::totalNanos).reversed());
        return rows;
    }

    public String format() {
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "%-32s %10s %12s %12s%n", "Script", "Calls", "Total", "Avg"));
        for (Row row : byScript()) {
            out.append(String.format(Locale.ROOT, "%-32s %,10d %12s %12s%n", row.name(), row.calls(),
                    duration(row.totalNanos()), duration(Math.round(row.averageNanos()))));
        }
        return out.toString();
    }

    /** Human-readable duration: µs below 1 ms, ms below 10 s, otherwise s. */
    public static String duration(long nanos) {
        if (nanos < 1_000_000) {
            return String.format(Locale.ROOT, "%.2f µs", nanos / 1e3);
        }
        if (nanos < 10_000_000_000L) {
            return String.format(Locale.ROOT, "%.1f ms", nanos / 1e6);
        }
        return String.format(Locale.ROOT, "%.1f s", nanos / 1e9);
    }
}
