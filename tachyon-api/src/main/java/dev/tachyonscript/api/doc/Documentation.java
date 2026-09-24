package dev.tachyonscript.api.doc;

import java.util.List;
import java.util.Objects;

/**
 * Human-readable documentation attached to a declaration.
 *
 * <p>The same metadata feeds generated reference docs, CLI help and editor tooling, so
 * declarations are documented once, next to their signature.
 *
 * @param summary  one sentence shown in completion lists and hovers
 * @param details  optional longer explanation (may be empty)
 * @param examples TachyonScript snippets demonstrating use (may be empty)
 * @param since    runtime version that introduced the declaration (may be empty)
 */
public record Documentation(String summary, String details, List<String> examples, String since) {

    /** Documentation of an undocumented declaration. */
    public static final Documentation NONE = new Documentation("", "", List.of(), "");

    public Documentation {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(details, "details");
        examples = List.copyOf(examples);
        Objects.requireNonNull(since, "since");
    }

    /** Documentation consisting of a summary only. */
    public static Documentation of(String summary) {
        return new Documentation(summary, "", List.of(), "");
    }

    /** Returns a copy with the given example appended. */
    public Documentation withExample(String example) {
        var list = new java.util.ArrayList<>(examples);
        list.add(example);
        return new Documentation(summary, details, list, since);
    }

    /** Returns a copy with the given {@code since} version. */
    public Documentation withSince(String version) {
        return new Documentation(summary, details, examples, version);
    }
}
