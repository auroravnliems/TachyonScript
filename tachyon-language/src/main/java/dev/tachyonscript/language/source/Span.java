package dev.tachyonscript.language.source;

/**
 * A half-open range {@code [start, end)} of UTF-16 offsets into a {@link SourceFile}.
 *
 * @param start first offset (inclusive)
 * @param end   last offset (exclusive); never smaller than {@code start}
 */
public record Span(int start, int end) {

    public Span {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Invalid span [" + start + ", " + end + ")");
        }
    }

    /** A zero-length span at {@code offset}. */
    public static Span at(int offset) {
        return new Span(offset, offset);
    }

    public int length() {
        return end - start;
    }

    /** The smallest span covering both this span and {@code other}. */
    public Span to(Span other) {
        return new Span(Math.min(start, other.start), Math.max(end, other.end));
    }

    public boolean contains(int offset) {
        return offset >= start && offset < end;
    }

    /** Packs this span into a {@code long} (start in the high bits), as used by IR instructions. */
    public long pack() {
        return ((long) start << 32) | (end & 0xFFFFFFFFL);
    }

    public static Span unpack(long packed) {
        return new Span((int) (packed >>> 32), (int) packed);
    }

    @Override
    public String toString() {
        return "[" + start + ", " + end + ")";
    }
}
