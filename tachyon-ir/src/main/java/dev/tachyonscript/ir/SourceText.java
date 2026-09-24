package dev.tachyonscript.ir;

import java.util.Arrays;
import java.util.Objects;

/**
 * Source text attached to IR as debug information, so that runtime errors can show the
 * script location and the offending line without depending on the language frontend.
 */
public final class SourceText {

    private final String path;
    private final String content;
    private final int[] lineStarts;

    public SourceText(String path, String content) {
        this.path = Objects.requireNonNull(path, "path");
        this.content = Objects.requireNonNull(content, "content");
        int count = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                count++;
            }
        }
        this.lineStarts = new int[count];
        int line = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lineStarts[line++] = i + 1;
            }
        }
    }

    public String path() {
        return path;
    }

    public String content() {
        return content;
    }

    /** 1-based line of {@code offset}. */
    public int line(int offset) {
        int clamped = Math.max(0, Math.min(offset, content.length()));
        int index = Arrays.binarySearch(lineStarts, clamped);
        return index >= 0 ? index + 1 : -index - 1;
    }

    /** 1-based column of {@code offset}. */
    public int column(int offset) {
        int clamped = Math.max(0, Math.min(offset, content.length()));
        return clamped - lineStarts[line(clamped) - 1] + 1;
    }

    /** Text of a 1-based line without its terminator. */
    public String lineText(int line) {
        int start = lineStarts[line - 1];
        int end = line < lineStarts.length ? lineStarts[line] : content.length();
        while (end > start && (content.charAt(end - 1) == '\n' || content.charAt(end - 1) == '\r')) {
            end--;
        }
        return content.substring(start, end);
    }

    @Override
    public String toString() {
        return path;
    }
}
