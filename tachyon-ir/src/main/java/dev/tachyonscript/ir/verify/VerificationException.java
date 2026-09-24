package dev.tachyonscript.ir.verify;

import java.util.List;

/**
 * Thrown when IR fails verification. Invalid IR is always a compiler bug, never a script
 * error, so it is reported as an internal compiler error.
 */
public final class VerificationException extends RuntimeException {

    private final List<String> problems;

    public VerificationException(String module, List<String> problems) {
        super("Invalid IR in module " + module + ":\n  " + String.join("\n  ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
