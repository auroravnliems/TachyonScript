package dev.tachyonscript.api.type;

/** The type of the {@code null} literal. It is assignable to every nullable type. */
public enum NullType implements Type {
    INSTANCE;

    @Override
    public String displayName() {
        return "null";
    }

    @Override
    public Representation representation() {
        return Representation.REF;
    }

    @Override
    public boolean isNullable() {
        return true;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
