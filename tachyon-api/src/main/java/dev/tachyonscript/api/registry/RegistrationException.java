package dev.tachyonscript.api.registry;

/**
 * Thrown when a declaration or binding cannot be registered, for example because of a
 * duplicate signature. The registry is left unchanged, so a failing addon cannot corrupt
 * the declarations registered before it.
 */
public final class RegistrationException extends RuntimeException {

    public RegistrationException(String message) {
        super(message);
    }
}
