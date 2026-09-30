package dev.tachyonscript.ir;

import dev.tachyonscript.api.type.Type;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * A script function referred to by IR: a function of the same module or of an imported one.
 * The signature lets the verifier check calls without the callee's IR; the linker checks that
 * the function it resolves has exactly this signature.
 *
 * @param module     name of the declaring module
 * @param key        function key within that module, e.g. {@code reward(Player, int)} or {@code lambda#2}
 * @param parameters parameter types (for lambdas: captured values first)
 * @param returnType result type ({@code void} allowed)
 */
public record FunctionRef(String module, String key, List<Type> parameters, Type returnType) {

    public FunctionRef {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(key, "key");
        parameters = List.copyOf(parameters);
        Objects.requireNonNull(returnType, "returnType");
    }

    /** Unique key across modules, {@code module::key}. */
    public String qualifiedKey() {
        return module + "::" + key;
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", module + "::" + key + " [", "]");
        parameters.forEach(parameter -> joiner.add(parameter.displayName()));
        return joiner + ": " + returnType.displayName();
    }
}
