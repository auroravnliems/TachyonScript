package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.language.syntax.Declaration;
import dev.tachyonscript.language.syntax.Expression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/**
 * A function declared in a script, or a method of a script record (called with the record as
 * receiver, {@code this}). Compared by identity.
 */
public final class FunctionSymbol {

    private final String module;
    private final String name;
    private final List<Type> parameterTypes;
    private final List<String> parameterNames;
    private final List<Expression> defaults;
    private final Type returnType;
    private final Declaration.Function syntax;
    private final RecordSymbol receiver;

    FunctionSymbol(String module, String name, List<String> parameterNames, List<Type> parameterTypes,
                   List<Expression> defaults, Type returnType, Declaration.Function syntax, RecordSymbol receiver) {
        this.module = module;
        this.name = name;
        this.parameterNames = List.copyOf(parameterNames);
        this.parameterTypes = List.copyOf(parameterTypes);
        this.defaults = Collections.unmodifiableList(new ArrayList<>(defaults));
        this.returnType = returnType;
        this.syntax = syntax;
        this.receiver = receiver;
    }

    /** Name of the module declaring the function. */
    public String module() {
        return module;
    }

    public String name() {
        return name;
    }

    /** Declared parameter types, without the receiver of a method. */
    public List<Type> parameterTypes() {
        return parameterTypes;
    }

    public List<String> parameterNames() {
        return parameterNames;
    }

    /** Default value of each parameter ({@code null} entries for required parameters). */
    public List<Expression> defaults() {
        return defaults;
    }

    /** Number of parameters a call must give (the others have defaults). */
    public int requiredParameters() {
        int required = 0;
        for (int i = 0; i < defaults.size(); i++) {
            if (defaults.get(i) == null) {
                required = i + 1;
            }
        }
        return required;
    }

    public Type returnType() {
        return returnType;
    }

    public Declaration.Function syntax() {
        return syntax;
    }

    /** The record owning this method, or {@code null} for a top-level function. */
    public RecordSymbol receiver() {
        return receiver;
    }

    public boolean isMethod() {
        return receiver != null;
    }

    /** Signature key unique within a module, e.g. {@code reward(Player, int)} or {@code Warp.label()}. */
    public String key() {
        StringJoiner joiner = new StringJoiner(", ", (receiver != null ? receiver.name() + "." : "") + name + "(", ")");
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
