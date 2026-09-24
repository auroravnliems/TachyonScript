package dev.tachyonscript.api.type;

/**
 * The type given to expressions that failed to type-check.
 *
 * <p>It is compatible with every other type so that one mistake produces one diagnostic,
 * not a cascade. Code containing it is never executed: compilation fails first.
 */
public enum ErrorType implements Type {
    INSTANCE;

    @Override
    public String displayName() {
        return "<error>";
    }

    @Override
    public Representation representation() {
        return Representation.REF;
    }

    @Override
    public boolean isError() {
        return true;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
