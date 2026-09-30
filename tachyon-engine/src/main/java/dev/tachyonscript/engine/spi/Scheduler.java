package dev.tachyonscript.engine.spi;

/**
 * Runs work later or elsewhere, on the platform's threads. On Paper the "global" thread is the
 * main thread; on Folia it is the global region thread, and entity work runs on the thread of
 * the region that owns the entity.
 *
 * <p>Delays are in ticks (50 ms) for work on server threads and in milliseconds for
 * asynchronous work. Implementations must be thread-safe.
 */
public interface Scheduler {

    /** A scheduled piece of work that can be cancelled. */
    interface Handle {
        /** Cancels the work; a run in progress completes. */
        void cancel();
    }

    /** Runs {@code task} on the global thread after {@code delayTicks} ticks (at least 1). */
    Handle runLater(long delayTicks, Runnable task);

    /** Runs {@code task} on the global thread every {@code periodTicks} ticks, first after {@code delayTicks}. */
    Handle runRepeating(long delayTicks, long periodTicks, Runnable task);

    /**
     * Runs {@code task} on the thread owning {@code entity} after {@code delayTicks} ticks; the
     * task is dropped if the entity is removed first.
     */
    Handle runLaterFor(Object entity, long delayTicks, Runnable task);

    /** Runs {@code task} on the thread owning {@code entity} repeatedly, until cancelled or the entity is removed. */
    Handle runRepeatingFor(Object entity, long delayTicks, long periodTicks, Runnable task);

    /** Runs {@code task} on a background thread after {@code delayMillis} milliseconds. */
    Handle runAsyncLater(long delayMillis, Runnable task);

    /** Runs {@code task} on a background thread every {@code periodMillis} milliseconds. */
    Handle runAsyncRepeating(long delayMillis, long periodMillis, Runnable task);

    /** Runs {@code task} on a background thread now. */
    void runAsync(Runnable task);

    /** Runs {@code task} on the global thread: immediately when called on it, otherwise as soon as possible. */
    void runGlobal(Runnable task);

    /** Whether the calling thread is the global thread. */
    boolean isGlobalThread();

    /** Whether the calling thread is a server thread that must not block (main thread or any region thread). */
    boolean isTickThread();
}
