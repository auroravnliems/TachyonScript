package dev.tachyonscript.engine;

import dev.tachyonscript.engine.spi.EngineLogger;
import dev.tachyonscript.runtime.error.ScriptFrame;
import dev.tachyonscript.runtime.error.ScriptRuntimeException;
import dev.tachyonscript.runtime.event.CompiledHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logs runtime errors without flooding the console: the first occurrence of an error at a
 * given script location is logged in full; repetitions are counted and summarized at most
 * once per interval. Keys are code locations, so the number of tracked errors is bounded
 * by the size of the scripts.
 */
public final class ErrorReporter {

    private static final long SUMMARY_INTERVAL_NANOS = 60_000_000_000L;
    private static final int MAX_TRACKED = 10_000;

    /** Aggregated occurrences of one error site. */
    public static final class Site {
        private final String location;
        private final String message;
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong reported = new AtomicLong(1);
        private volatile long lastLog;

        Site(String location, String message) {
            this.location = location;
            this.message = message;
        }

        public String location() {
            return location;
        }

        public String message() {
            return message;
        }

        public long count() {
            return count.get();
        }
    }

    private final EngineLogger logger;
    private final boolean debug;
    private final ConcurrentHashMap<String, Site> sites = new ConcurrentHashMap<>();

    public ErrorReporter(EngineLogger logger, boolean debug) {
        this.logger = logger;
        this.debug = debug;
    }

    public void report(CompiledHandler handler, ScriptRuntimeException error) {
        String location = location(error, handler);
        String key = location + "|" + error.kind();
        Site site = sites.get(key);
        if (site == null) {
            if (sites.size() >= MAX_TRACKED) {
                logger.error(error.render(debug));
                return;
            }
            site = sites.computeIfAbsent(key, k -> new Site(location, error.getMessage()));
        }
        long count = site.count.incrementAndGet();
        long now = System.nanoTime();
        if (count == 1) {
            site.lastLog = now;
            logger.error(error.render(debug));
            return;
        }
        if (now - site.lastLog > SUMMARY_INTERVAL_NANOS) {
            site.lastLog = now;
            long unreported = count - site.reported.getAndSet(count);
            logger.error("TachyonRuntimeError at " + location + " occurred " + unreported
                    + " more times: " + error.getMessage());
        }
    }

    /** Distinct error sites with their counts, most frequent first (for {@code /tys errors}). */
    public List<Site> sites() {
        List<Site> list = new ArrayList<>(sites.values());
        list.sort(Comparator.comparingLong(Site::count).reversed());
        return list;
    }

    /** Forgets all sites (after a reload, locations refer to new code). */
    public void clear() {
        sites.clear();
    }

    private static String location(ScriptRuntimeException error, CompiledHandler handler) {
        if (!error.frames().isEmpty()) {
            ScriptFrame frame = error.frames().getFirst();
            return frame.path() + ":" + frame.line();
        }
        return handler.module();
    }
}
