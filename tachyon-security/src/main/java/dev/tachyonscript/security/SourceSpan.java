package dev.tachyonscript.security;

import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.language.source.Span;

import java.util.Map;

/** Authoritative, half-open UTF-16 source range. UNKNOWN is -1, never a guessed line. */
public record SourceSpan(String file, int startOffset, int endOffset, int startLine, int startColumn,
                         int endLine, int endColumn) {
    public SourceSpan {
        if (file == null || file.isBlank()) throw new IllegalArgumentException("A source file is required");
        boolean unknown = startOffset == -1 && endOffset == -1 && startLine == -1 && startColumn == -1
                && endLine == -1 && endColumn == -1;
        if (!unknown && (startOffset < 0 || endOffset < startOffset || startLine < 1 || startColumn < 1
                || endLine < startLine || endColumn < 1 || endLine == startLine && endColumn < startColumn)) {
            throw new IllegalArgumentException("Invalid security source span");
        }
    }

    public static SourceSpan from(SourceFile file, Span span) {
        if (span == null) return unknown(file.path());
        if (span.end() > file.length()) throw new IllegalArgumentException("Span exceeds its source revision");
        return new SourceSpan(file.path(), span.start(), span.end(), file.lineOf(span.start()),
                file.columnOf(span.start()), file.lineOf(span.end()), file.columnOf(span.end()));
    }

    public static SourceSpan unknown(String file) {
        return new SourceSpan(file, -1, -1, -1, -1, -1, -1);
    }

    public boolean known() { return startLine > 0; }

    public String display() {
        return known() ? file + ":" + startLine + ":" + startColumn + "-" + endLine + ":" + endColumn
                : file + ":UNKNOWN (Precise source location unavailable.)";
    }

    public Map<String, Object> toJson() {
        return Map.of("file", file, "startOffset", startOffset, "endOffset", endOffset,
                "startLine", startLine, "startColumn", startColumn, "endLine", endLine, "endColumn", endColumn);
    }

    static SourceSpan read(Map<String, Object> value) {
        return new SourceSpan(SecurityJson.string(value, "file"), SecurityJson.integer(value, "startOffset"),
                SecurityJson.integer(value, "endOffset"), SecurityJson.integer(value, "startLine"),
                SecurityJson.integer(value, "startColumn"), SecurityJson.integer(value, "endLine"),
                SecurityJson.integer(value, "endColumn"));
    }
}
