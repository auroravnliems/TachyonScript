package dev.tachyonscript.engine;

import dev.tachyonscript.api.value.ScriptTask;
import dev.tachyonscript.engine.spi.Scheduler;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A repeating block started with {@code every} ({@code task} inside the block). Cancelling it
 * cancels the platform task and forgets it in its script.
 */
final class ScheduledScriptTask implements ScriptTask {

    private final LoadedScript script;
    private final String location;
    private final AtomicLong runs = new AtomicLong();
    private volatile Scheduler.Handle handle;
    private volatile boolean cancelled;

    ScheduledScriptTask(LoadedScript script, String location) {
        this.script = script;
        this.location = location;
    }

    void start(Scheduler.Handle platformHandle) {
        this.handle = platformHandle;
        if (cancelled) {
            platformHandle.cancel();
        } else {
            script.track(platformHandle);
        }
    }

    /** Counts a run; false if the task should not run anymore. */
    boolean beginRun() {
        if (cancelled || !script.isActive()) {
            cancel();
            return false;
        }
        runs.incrementAndGet();
        return true;
    }

    @Override
    public void cancel() {
        cancelled = true;
        Scheduler.Handle current = handle;
        if (current != null) {
            current.cancel();
            script.untrack(current);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public long runs() {
        return runs.get();
    }

    @Override
    public String location() {
        return location;
    }

    @Override
    public String toString() {
        return "task (" + location + ", " + runs.get() + " runs" + (cancelled ? ", cancelled" : "") + ")";
    }
}
