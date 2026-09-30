package dev.tachyonscript.engine.spi;

import dev.tachyonscript.api.type.ClassType;

import java.util.List;

/**
 * Converts command arguments of platform types (players, worlds, game modes, and keyed types
 * such as {@code Material}) and suggests values for tab completion. Numbers, text, booleans
 * and durations are handled by the engine.
 */
public interface ArgumentTypes {

    /** Thrown when an argument cannot be converted; the message is shown to the sender. */
    final class InvalidArgument extends Exception {
        private static final long serialVersionUID = 1L;

        public InvalidArgument(String message) {
            super(message, null, false, false);
        }
    }

    /** Whether commands can take parameters of {@code type}. */
    boolean supports(ClassType type);

    /** Converts {@code text} to a value of {@code type}. */
    Object parse(ClassType type, String text, Object sender) throws InvalidArgument;

    /** Values of {@code type} starting with {@code prefix} (case-insensitive), for tab completion. */
    List<String> suggest(ClassType type, String prefix, Object sender);
}
