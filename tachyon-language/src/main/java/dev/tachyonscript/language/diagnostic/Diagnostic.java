package dev.tachyonscript.language.diagnostic;

import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.language.source.SourceLocation;
import dev.tachyonscript.language.source.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A message about a source location.
 *
 * @param code     stable diagnostic identifier
 * @param severity severity (usually the code's default)
 * @param message  one-sentence description of the problem
 * @param file     file the diagnostic refers to
 * @param span     primary span
 * @param labels   secondary spans in the same file
 * @param notes    additional paragraphs (expected/received types, suggestions, help)
 */
public record Diagnostic(DiagnosticCode code, Severity severity, String message, SourceFile file, Span span,
                         List<Label> labels, List<String> notes) {

    public Diagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(span, "span");
        labels = List.copyOf(labels);
        notes = List.copyOf(notes);
    }

    public static Builder builder(DiagnosticCode code, SourceFile file, Span span, String message) {
        return new Builder(code, file, span, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    public SourceLocation location() {
        return file.location(Math.min(span.start(), file.length()));
    }

    /** {@code path:line:column} of the primary span. */
    public String position() {
        return file.path() + ":" + location();
    }

    @Override
    public String toString() {
        return severity + " " + position() + " [" + code.id() + "] " + message;
    }

    /** Builder for diagnostics with labels and notes. */
    public static final class Builder {
        private final DiagnosticCode code;
        private final SourceFile file;
        private final Span span;
        private final String message;
        private Severity severity;
        private final List<Label> labels = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();

        private Builder(DiagnosticCode code, SourceFile file, Span span, String message) {
            this.code = code;
            this.file = file;
            this.span = span;
            this.message = message;
            this.severity = code.defaultSeverity();
        }

        public Builder severity(Severity severity) {
            this.severity = severity;
            return this;
        }

        public Builder label(Span labelSpan, String labelMessage) {
            labels.add(new Label(labelSpan, labelMessage));
            return this;
        }

        public Builder note(String note) {
            notes.add(note);
            return this;
        }

        /** Adds the standard "Expected: ... Received: ..." note. */
        public Builder expectedReceived(String expected, String received) {
            notes.add("Expected:\n    " + expected + "\n\nReceived:\n    " + received);
            return this;
        }

        /** Adds a "Did you mean" note when there are suggestions. */
        public Builder suggestions(List<String> candidates) {
            if (!candidates.isEmpty()) {
                notes.add("Did you mean:\n    " + String.join("\n    ", candidates));
            }
            return this;
        }

        public Diagnostic build() {
            return new Diagnostic(code, severity, message, file, span, labels, notes);
        }
    }
}
