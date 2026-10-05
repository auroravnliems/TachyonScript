package dev.tachyonscript.security;

import dev.tachyonscript.language.source.SourceFile;

public final class SourceSnippets {
    private SourceSnippets() { }

    public static String context(SourceFile source, SourceSpan span, SecretRedactor redactor) {
        if (!span.known()) return "Precise source location unavailable.";
        if (!source.path().equals(span.file()) || span.endOffset() > source.length()) {
            throw new IllegalArgumentException("Snippet source does not match the compiler span");
        }
        StringBuilder out = new StringBuilder();
        int end = Math.min(source.lineCount(), Math.min(span.endLine(), span.startLine() + 2) + 1);
        for (int line = Math.max(1, span.startLine() - 1); line <= end; line++) {
            String text = redactor.redact(source.lineText(line));
            int firstColumn = line == span.startLine() ? Math.max(0, span.startColumn() - 1 - 160) : 0;
            int lastColumn = Math.min(text.length(), firstColumn + 320);
            String prefix = firstColumn == 0 ? "" : "[from column " + (firstColumn + 1) + "] ";
            out.append(line).append(" | ").append(prefix).append(text, firstColumn, lastColumn);
            if (lastColumn < text.length()) out.append(" … [line truncated]");
            out.append('\n');
            if (line == span.startLine()) {
                int column = prefix.length() + span.startColumn() - 1 - firstColumn;
                int length = span.startLine() == span.endLine() ? span.endColumn() - span.startColumn() : 1;
                out.append(" ".repeat(String.valueOf(line).length())).append(" | ")
                        .append(" ".repeat(column)).append("^".repeat(Math.max(1, Math.min(80, length))))
                        .append('\n');
            }
        }
        return out.toString().stripTrailing();
    }
}
