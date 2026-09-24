package dev.tachyonscript.runtime.interpreter;

import java.util.Arrays;
import java.util.Objects;

/**
 * Per-thread storage for script frames.
 *
 * <p>All frames of a thread live in two growable arrays: {@code long} slots for primitive
 * registers and {@code Object} slots for references. A frame is a window starting at a base
 * offset, so calls allocate nothing. Reference slots are cleared when a frame is left so
 * that the stack never keeps players, worlds or events alive.
 *
 * <p>Obtained through {@link #current()}; never shared between threads.
 */
public final class ExecutionStack {

    private static final ThreadLocal<ExecutionStack> CURRENT = ThreadLocal.withInitial(ExecutionStack::new);
    private static volatile RuntimeLimits limits = RuntimeLimits.DEFAULT;

    long[] primitives = new long[256];
    Object[] references = new Object[256];
    /** First free primitive slot (end of the innermost active frame). */
    int primitiveTop;
    /** First free reference slot. */
    int referenceTop;
    /** Highest reference slot used since the outermost invocation began (for cleanup after errors). */
    int referenceHighWater;
    /** Number of active script frames on this thread. */
    int depth;
    int maxDepth = RuntimeLimits.DEFAULT.maxCallDepth();
    long returnPrimitive;
    Object returnReference;
    private CallArguments[] views = new CallArguments[16];
    int loopBudget;
    private int loopInterval;
    private long maxExecutionNanos;
    private long deadline;
    private boolean deadlineArmed;

    private ExecutionStack() {
    }

    /** The stack of the calling thread. */
    public static ExecutionStack current() {
        return CURRENT.get();
    }

    /** Sets the limits used by executions that start after this call (all threads). */
    public static void configure(RuntimeLimits newLimits) {
        limits = Objects.requireNonNull(newLimits, "limits");
    }

    public static RuntimeLimits limits() {
        return limits;
    }

    /**
     * Called when an outermost execution starts: resets the watchdog with the current limits.
     * The clock is not read here; see {@link #checkDeadline()}.
     */
    void startExecution() {
        RuntimeLimits current = limits;
        maxDepth = current.maxCallDepth();
        loopInterval = current.loopCheckInterval();
        loopBudget = loopInterval;
        maxExecutionNanos = current.maxExecutionNanos();
        deadlineArmed = false;
    }

    /**
     * Called on a loop back edge when the budget counter runs out. The first call reads the
     * clock and sets the deadline; later calls compare against it. Reading the clock when an
     * execution starts would cost more than a typical short handler, and only executions
     * that loop long enough to get here can run away. The effective limit is therefore the
     * configured time plus the time of the first {@code loopCheckInterval} iterations.
     */
    void checkDeadline() {
        loopBudget = loopInterval;
        long now = System.nanoTime();
        if (!deadlineArmed) {
            deadline = now + maxExecutionNanos;
            deadlineArmed = true;
        } else if (now - deadline > 0) {
            throw Interpreter.timeout(maxExecutionNanos);
        }
    }

    void ensure(int primitiveEnd, int referenceEnd) {
        if (primitiveEnd > primitives.length) {
            primitives = Arrays.copyOf(primitives, Math.max(primitiveEnd, primitives.length * 2));
        }
        if (referenceEnd > references.length) {
            references = Arrays.copyOf(references, Math.max(referenceEnd, references.length * 2));
        }
        if (referenceEnd > referenceHighWater) {
            referenceHighWater = referenceEnd;
        }
    }

    CallArguments arguments(int[] code, int position, int count, int primitiveBase, int referenceBase) {
        if (depth >= views.length) {
            views = Arrays.copyOf(views, Math.max(views.length * 2, depth + 1));
        }
        CallArguments view = views[depth];
        if (view == null) {
            view = new CallArguments(this);
            views[depth] = view;
        }
        return view.reset(code, position, count, primitiveBase, referenceBase);
    }

    void clearReferences(int from, int to) {
        Arrays.fill(references, from, Math.min(to, references.length), null);
    }

    /** Number of active script frames on this thread (0 when no script is running). */
    public int depth() {
        return depth;
    }
}
