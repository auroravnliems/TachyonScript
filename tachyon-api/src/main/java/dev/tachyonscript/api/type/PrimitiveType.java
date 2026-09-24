package dev.tachyonscript.api.type;

/**
 * Built-in value types. None of them admits {@code null}; their nullable forms are boxed.
 */
public enum PrimitiveType implements Type {
    INT("int", Representation.INT, true),
    LONG("long", Representation.LONG, true),
    FLOAT("float", Representation.FLOAT, true),
    DOUBLE("double", Representation.DOUBLE, true),
    BOOL("bool", Representation.BOOL, false),
    VOID("void", Representation.VOID, false),
    /** A span of time, stored as a {@code long} number of milliseconds. */
    DURATION("Duration", Representation.LONG, false);

    private final String displayName;
    private final Representation representation;
    private final boolean numeric;

    PrimitiveType(String displayName, Representation representation, boolean numeric) {
        this.displayName = displayName;
        this.representation = representation;
        this.numeric = numeric;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public Representation representation() {
        return representation;
    }

    /** Whether arithmetic operators apply to this type. */
    public boolean isNumeric() {
        return numeric;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
