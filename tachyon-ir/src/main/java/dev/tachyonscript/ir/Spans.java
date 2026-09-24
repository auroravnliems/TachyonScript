package dev.tachyonscript.ir;

/**
 * Source spans packed into a {@code long} (start offset in the high 32 bits, end in the low
 * 32 bits). IR instructions carry one so that every runtime error maps back to source.
 */
public final class Spans {

    /** No source position. */
    public static final long NONE = -1L;

    private Spans() {
    }

    public static long of(int start, int end) {
        return ((long) start << 32) | (end & 0xFFFFFFFFL);
    }

    public static int start(long span) {
        return (int) (span >>> 32);
    }

    public static int end(long span) {
        return (int) span;
    }

    public static boolean isNone(long span) {
        return span == NONE;
    }
}
