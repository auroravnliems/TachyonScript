package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.language.syntax.Declaration;

/** A {@code const} declaration. Its value is computed at compile time and inlined at every use. */
public final class ConstantSymbol {

    enum State {
        UNRESOLVED,
        RESOLVING,
        RESOLVED,
        FAILED
    }

    private final String name;
    private final Declaration.Const syntax;
    private State state = State.UNRESOLVED;
    private Type type;
    private Object value;

    ConstantSymbol(String name, Declaration.Const syntax) {
        this.name = name;
        this.syntax = syntax;
    }

    public String name() {
        return name;
    }

    public Declaration.Const syntax() {
        return syntax;
    }

    /** Resolved type; {@code null} before resolution. */
    public Type type() {
        return type;
    }

    /** Resolved value (boxed Java value matching {@link #type()}). */
    public Object value() {
        return value;
    }

    State state() {
        return state;
    }

    void state(State state) {
        this.state = state;
    }

    void resolve(Type resolvedType, Object resolvedValue) {
        this.type = resolvedType;
        this.value = resolvedValue;
        this.state = State.RESOLVED;
    }

    @Override
    public String toString() {
        return "const " + name + (type != null ? ": " + type.displayName() + " = " + value : "");
    }
}
