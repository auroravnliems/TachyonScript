package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.language.syntax.Declaration;

import java.util.List;
import java.util.StringJoiner;

/** A function declared in a script. Compared by identity. */
public final class FunctionSymbol {

    private final String name;
    private final List<Type> parameterTypes;
    private final List<String> parameterNames;
    private final Type returnType;
    private final Declaration.Function syntax;

    FunctionSymbol(String name, List<String> parameterNames, List<Type> parameterTypes, Type returnType,
                   Declaration.Function syntax) {
        this.name = name;
        this.parameterNames = List.copyOf(parameterNames);
        this.parameterTypes = List.copyOf(parameterTypes);
        this.returnType = returnType;
        this.syntax = syntax;
    }

    public String name() {
        return name;
    }

    public List<Type> parameterTypes() {
        return parameterTypes;
    }

    public List<String> parameterNames() {
        return parameterNames;
    }

    public Type returnType() {
        return returnType;
    }

    public Declaration.Function syntax() {
        return syntax;
    }

    /** Signature key unique within a module, e.g. {@code reward(Player, int)}. */
    public String key() {
        StringJoiner joiner = new StringJoiner(", ", name + "(", ")");
        for (Type type : parameterTypes) {
            joiner.add(type.displayName());
        }
        return joiner.toString();
    }

    @Override
    public String toString() {
        return key() + ": " + returnType.displayName();
    }
}
