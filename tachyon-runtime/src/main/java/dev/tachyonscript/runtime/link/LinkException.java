package dev.tachyonscript.runtime.link;

import java.util.List;

/**
 * A module could not be linked, typically because the platform does not provide an
 * operation the script uses. Linking happens before activation, so a failed link never
 * replaces working scripts.
 */
public final class LinkException extends Exception {

    private final List<String> problems;

    public LinkException(String module, List<String> problems) {
        super("Cannot load module " + module + ":\n  " + String.join("\n  ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
