package dev.tachyonscript.testkit;

import dev.tachyonscript.engine.spi.Scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A deterministic scheduler for tests: nothing runs until the test advances time with
 * {@link #tick(long)}. Asynchronous work runs immediately on the calling thread (and is
 * recorded as asynchronous while it runs); "global" work runs inline. Asynchronous delays in
 * milliseconds are converted to ticks.
 *
 * <p>The thread that created the scheduler is the "server thread". Work handed to the server
 * from real background threads (database results, for example) waits until the next tick.
 */
public final class TestScheduler implements Scheduler {

    private final class Task implements Handle {
        final long id;
        long due;
        final long period;
        final Runnable body;
        final Object entity;
        boolean cancelled;

        Task(long id, long due, long period, Runnable body, Object entity) {
            this.id = id;
            this.due = due;
            this.period = period;
            this.body = body;
            this.entity = entity;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private final PriorityQueue<Task> queue = new PriorityQueue<>(
            Comparator.<Task>comparingLong(task -> task.due).thenComparingLong(task -> task.id));
    private final List<Runnable> asyncLog = new ArrayList<>();
    private final Thread serverThread = Thread.currentThread();
    private final java.util.Queue<Runnable> fromOtherThreads = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private long now;
    private long nextId;
    private boolean inAsync;

    /** The current tick. */
    public long now() {
        return now;
    }

    /** Advances time by {@code ticks} ticks, running every task that becomes due, in order. */
    public void tick(long ticks) {
        Runnable handedOver;
        while ((handedOver = fromOtherThreads.poll()) != null) {
            handedOver.run();
        }
        long end = now + ticks;
        while (true) {
            Task next = queue.peek();
            if (next == null || next.due > end) {
                break;
            }
            queue.poll();
            now = next.due;
            if (next.cancelled || (next.entity instanceof Fakes.Entity entity && !entity.valid())) {
                continue;
            }
            next.body.run();
            if (next.period > 0 && !next.cancelled) {
                next.due = now + next.period;
                queue.add(next);
            }
        }
        now = end;
    }

    /** Number of tasks waiting (cancelled ones excluded). */
    public int pending() {
        return (int) queue.stream().filter(task -> !task.cancelled).count();
    }

    private Task schedule(long delay, long period, Runnable body, Object entity) {
        Task task = new Task(nextId++, now + Math.max(1, delay), period, body, entity);
        queue.add(task);
        return task;
    }

    @Override
    public Handle runLater(long delayTicks, Runnable task) {
        return schedule(delayTicks, 0, task, null);
    }

    @Override
    public Handle runRepeating(long delayTicks, long periodTicks, Runnable task) {
        return schedule(delayTicks, Math.max(1, periodTicks), task, null);
    }

    @Override
    public Handle runLaterFor(Object entity, long delayTicks, Runnable task) {
        return schedule(delayTicks, 0, task, entity);
    }

    @Override
    public Handle runRepeatingFor(Object entity, long delayTicks, long periodTicks, Runnable task) {
        return schedule(delayTicks, Math.max(1, periodTicks), task, entity);
    }

    @Override
    public Handle runAsyncLater(long delayMillis, Runnable task) {
        return schedule(Math.max(1, (delayMillis + 49) / 50), 0, () -> async(task), null);
    }

    @Override
    public Handle runAsyncRepeating(long delayMillis, long periodMillis, Runnable task) {
        return schedule(Math.max(1, (delayMillis + 49) / 50), Math.max(1, (periodMillis + 49) / 50), () -> async(task), null);
    }

    @Override
    public void runAsync(Runnable task) {
        asyncLog.add(task);
        async(task);
    }

    private void async(Runnable task) {
        boolean outer = inAsync;
        inAsync = true;
        try {
            task.run();
        } finally {
            inAsync = outer;
        }
    }

    @Override
    public void runGlobal(Runnable task) {
        if (Thread.currentThread() != serverThread) {
            fromOtherThreads.add(task);
        } else if (inAsync) {
            // Hopping back to the server thread happens on the next tick.
            schedule(1, 0, task, null);
        } else {
            task.run();
        }
    }

    @Override
    public boolean isGlobalThread() {
        return Thread.currentThread() == serverThread && !inAsync;
    }

    @Override
    public boolean isTickThread() {
        return Thread.currentThread() == serverThread && !inAsync;
    }

    /** Number of asynchronous blocks started so far. */
    public int asyncRuns() {
        return asyncLog.size();
    }
}
