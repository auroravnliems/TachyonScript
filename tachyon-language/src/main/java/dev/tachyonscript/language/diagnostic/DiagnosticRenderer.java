package dev.tachyonscript.language.diagnostic;

import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.language.source.Span;

/**
 * Formats diagnostics for consoles and terminals:
 *
 * <pre>
 * ERROR scripts/shop.tys:42:12 [TYS0201]
 *
 *   42 |     player.giev("diamond", 1)
 *      |            ^^^^
 *
 * Unknown member 'giev' on Player.
 *
 * Did you mean:
 *     give
 * </pre>
 */
public final class DiagnosticRenderer {

    private static final int TAB_WIDTH = 4;
    private static final String RESET = "\u001B[0m";

    private final boolean color;
    private final String pathPrefix;

    /**
     * @param color      whether to emit ANSI colors
     * @param pathPrefix prefix prepended to file paths (e.g. {@code scripts/}); may be empty
     */
    public DiagnosticRenderer(boolean color, String pathPrefix) {
        this.color = color;
        this.pathPrefix = pathPrefix;
    }

    public static DiagnosticRenderer plain() {
        return new DiagnosticRenderer(false, "");
    }

    public String render(Diagnostic diagnostic) {
        StringBuilder out = new StringBuilder();
        SourceFile file = diagnostic.file();
        int start = Math.min(diagnostic.span().start(), file.length());
        int line = file.lineOf(start);
        int column = file.columnOf(start);

        out.append(paint(severityColor(diagnostic.severity()), diagnostic.severity().name()))
                .append(' ').append(pathPrefix).append(file.path()).append(':').append(line).append(':').append(column)
                .append(' ').append(paint("\u001B[2m", "[" + diagnostic.code().id() + "]"))
                .append("\n\n");

        int gutter = String.valueOf(maxLine(diagnostic)).length();
        appendSnippet(out, file, diagnostic.span(), "", gutter, severityColor(diagnostic.severity()));
        for (Label label : diagnostic.labels()) {
            appendSnippet(out, file, label.span(), label.message(), gutter, "\u001B[36m");
        }
        out.append('\n').append(paint("\u001B[1m", diagnostic.message())).append('\n');
        for (String note : diagnostic.notes()) {
            out.append('\n').append(note).append('\n');
        }
        return out.toString();
    }

    private int maxLine(Diagnostic diagnostic) {
        SourceFile file = diagnostic.file();
        int max = file.lineOf(Math.min(diagnostic.span().start(), file.length()));
        for (Label label : diagnostic.labels()) {
            max = Math.max(max, file.lineOf(Math.min(label.span().start(), file.length())));
        }
        return max;
    }

    private void appendSnippet(StringBuilder out, SourceFile file, Span span, String message, int gutter, String caretColor) {
        int start = Math.min(span.start(), file.length());
        int line = file.lineOf(start);
        String text = file.lineText(line);
        int lineStart = file.lineStart(line);
        int caretStart = start - lineStart;
        int caretEnd = Math.min(span.end(), lineStart + text.length()) - lineStart;
        if (caretEnd <= caretStart) {
            caretEnd = caretStart + 1;
        }
        String number = String.valueOf(line);
        out.append("  ").append(" ".repeat(gutter - number.length())).append(number).append(" | ")
                .append(expandTabs(text)).append('\n');
        int visualStart = visualWidth(text, caretStart);
        int visualEnd = visualWidth(text, Math.min(caretEnd, text.length())) + Math.max(0, caretEnd - text.length());
        out.append("  ").append(" ".repeat(gutter)).append(" | ").append(" ".repeat(visualStart))
                .append(paint(caretColor, "^".repeat(Math.max(1, visualEnd - visualStart))));
        if (!message.isEmpty()) {
            out.append(' ').append(message);
        }
        out.append('\n');
    }

    private static int visualWidth(String text, int upTo) {
        int width = 0;
        for (int i = 0; i < upTo && i < text.length(); i++) {
            width = text.charAt(i) == '\t' ? (width / TAB_WIDTH + 1) * TAB_WIDTH : width + 1;
        }
        return width;
    }

    private static String expandTabs(String text) {
        if (text.indexOf('\t') < 0) {
            return text;
        }
        StringBuilder expanded = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t') {
                int spaces = TAB_WIDTH - (expanded.length() % TAB_WIDTH);
                expanded.append(" ".repeat(spaces));
            } else {
                expanded.append(c);
            }
        }
        return expanded.toString();
    }

    private static String severityColor(Severity severity) {
        return switch (severity) {
            case ERROR -> "\u001B[31m";
            case WARNING -> "\u001B[33m";
            case INFO -> "\u001B[34m";
            case HINT -> "\u001B[32m";
        };
    }

    private String paint(String ansi, String text) {
        return color ? ansi + text + RESET : text;
    }
}
