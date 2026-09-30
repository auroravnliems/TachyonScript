package dev.tachyonscript.api.value;

/**
 * A repeating block started by a script ({@code every 5 seconds { task.cancel() }}), as seen
 * through the standard library type {@code Task}. Implemented by the engine; thread-safe.
 */
public interface ScriptTask {

    /** Stops the task; the current run (if any) completes. Cancelling twice has no effect. */
    void cancel();

    boolean isCancelled();

    /** Number of times the block has started running, the current run included. */
    long runs();

    /** Where the task was started, e.g. {@code shop.tys:12}. */
    String location();
}
