package dev.tachyonscript.language.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * An immutable script source: its logical path, text, a line index and a content hash.
 *
 * <p>Diagnostics and runtime errors refer to a {@code SourceFile} and a {@link Span}; line
 * and column numbers are derived on demand through the line index (binary search), so the
 * rest of the compiler only handles integer offsets.
 */
public final class SourceFile {

    private final String path;
    private final String content;
    private final int[] lineStarts;
    private volatile String hash;

    /**
     * @param path    logical path relative to the scripts directory, with {@code /} separators
     *                (for example {@code events/join.tys})
     * @param content the complete text of the file
     */
    public SourceFile(String path, String content) {
        this.path = Objects.requireNonNull(path, "path").replace('\\', '/');
        this.content = Objects.requireNonNull(content, "content");
        this.lineStarts = computeLineStarts(content);
    }

    public String path() {
        return path;
    }

    public String content() {
        return content;
    }

    public int length() {
        return content.length();
    }

    public int lineCount() {
        return lineStarts.length;
    }

    /** 1-based line containing {@code offset} (offsets at the end of the file belong to the last line). */
    public int lineOf(int offset) {
        checkOffset(offset);
        int index = Arrays.binarySearch(lineStarts, offset);
        return index >= 0 ? index + 1 : -index - 1;
    }

    /** 1-based column of {@code offset} within its line. */
    public int columnOf(int offset) {
        return offset - lineStarts[lineOf(offset) - 1] + 1;
    }

    public SourceLocation location(int offset) {
        int line = lineOf(offset);
        return new SourceLocation(line, offset - lineStarts[line - 1] + 1);
    }

    /** Offset of the first character of a 1-based line. */
    public int lineStart(int line) {
        if (line < 1 || line > lineStarts.length) {
            throw new IndexOutOfBoundsException("Line " + line + " of " + path);
        }
        return lineStarts[line - 1];
    }

    /** Text of a 1-based line without its line terminator. */
    public String lineText(int line) {
        int start = lineStart(line);
        int end = line < lineStarts.length ? lineStarts[line] : content.length();
        while (end > start && (content.charAt(end - 1) == '\n' || content.charAt(end - 1) == '\r')) {
            end--;
        }
        return content.substring(start, end);
    }

    /** Source text covered by {@code span}. */
    public String text(Span span) {
        return content.substring(span.start(), span.end());
    }

    /** Hex-encoded SHA-256 of the UTF-8 content; used to detect changes on reload and to key caches. */
    public String hash() {
        String result = hash;
        if (result == null) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                result = HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                // Every Java platform is required to provide SHA-256.
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
            hash = result;
        }
        return result;
    }

    @Override
    public String toString() {
        return path;
    }

    private void checkOffset(int offset) {
        if (offset < 0 || offset > content.length()) {
            throw new IndexOutOfBoundsException("Offset " + offset + " outside " + path + " (length " + content.length() + ")");
        }
    }

    private static int[] computeLineStarts(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                count++;
            }
        }
        int[] starts = new int[count];
        int line = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                starts[line++] = i + 1;
            }
        }
        return starts;
    }
}
