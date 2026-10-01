package dev.tachyonscript.engine.profile;

import dev.tachyonscript.runtime.interpreter.CompiledFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects execution statistics while enabled: one entry per script function that ran from the
 * outside (event handlers, commands, tasks, scheduled blocks, placeholders, callbacks). When
 * disabled, the engine does not read the clock at all, so profiling has no cost unless someone
 * asked for it.
 */
public final class Profiler {

    /** Statistics of one handler, command, task, block or other script entry point. */
    public static final class Entry {
        private final String script;
        private final String handler;
        final LongAdder calls = new LongAdder();
        final LongAdder nanos = new LongAdder();
        private volatile long maxNanos;

        Entry(String script, String handler) {
            this.script = script;
            this.handler = handler;
        }

        public String script() {
            return script;
        }

        public String handler() {
            return handler;
        }

        public long calls() {
            return calls.sum();
        }

        public long totalNanos() {
            return nanos.sum();
        }

        public long maxNanos() {
            return maxNanos;
        }
    }

    private volatile boolean enabled;
    private volatile long startedAt;
    private final ConcurrentHashMap<CompiledFunction, Entry> entries = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    /** Starts a new profiling session, discarding previous data. */
    public void start() {
        entries.clear();
        startedAt = System.nanoTime();
        enabled = true;
    }

    public void stop() {
        enabled = false;
    }

    public void record(CompiledFunction function, long nanos) {
        Entry entry = entries.computeIfAbsent(function, f -> new Entry(f.source().path(), f.displayName()));
        entry.calls.increment();
        entry.nanos.add(nanos);
        if (nanos > entry.maxNanos) {
            entry.maxNanos = nanos;
        }
    }

    /** Snapshot of the collected data. */
    public ProfileReport report() {
        long duration = startedAt == 0 ? 0 : System.nanoTime() - startedAt;
        return new ProfileReport(new ArrayList<>(entries.values()), duration);
    }

    /** Entries of the current session (unsorted). */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }
}
