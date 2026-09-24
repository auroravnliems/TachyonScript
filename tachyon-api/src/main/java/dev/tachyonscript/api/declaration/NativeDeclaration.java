package dev.tachyonscript.api.declaration;

import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.api.type.Type;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * One bindable host operation: a global function, a method, a property accessor or an
 * event variable.
 *
 * <p>IR refers to native declarations, and the linker resolves each one to its platform
 * {@code NativeFunction} exactly once per load. The {@link #key()} is stable across reloads
 * and server restarts (it is derived from names and parameter types, never from
 * registration order), so it can be used for caching and diagnostics.
 *
 * <p>For members, parameter 0 is the receiver.
 */
public final class NativeDeclaration {

    /** What kind of source construct invokes the operation. */
    public enum Kind {
        FUNCTION,
        METHOD,
        GETTER,
        SETTER,
        EVENT_VARIABLE
    }

    private final String key;
    private final Kind kind;
    private final List<Parameter> parameters;
    private final Type returnType;
    private final Set<Effect> effects;
    private final ThreadingRequirement threading;
    private final int threadingParameter;

    NativeDeclaration(String key, Kind kind, List<Parameter> parameters, Type returnType,
                      Set<Effect> effects, ThreadingRequirement threading, int threadingParameter) {
        this.key = Objects.requireNonNull(key, "key");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.parameters = List.copyOf(parameters);
        this.returnType = Objects.requireNonNull(returnType, "returnType");
        this.effects = effects.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(effects));
        this.threading = Objects.requireNonNull(threading, "threading");
        if ((threading == ThreadingRequirement.ENTITY || threading == ThreadingRequirement.REGION)
                && (threadingParameter < 0 || threadingParameter >= this.parameters.size())) {
            throw new IllegalArgumentException(key + ": " + threading
                    + " threading requires a valid threading parameter index, got " + threadingParameter);
        }
        this.threadingParameter = threadingParameter;
    }

    /** Stable unique key, for example {@code Player.send(Component)} or {@code LivingEntity.health:get}. */
    public String key() {
        return key;
    }

    public Kind kind() {
        return kind;
    }

    /** All parameters, including the receiver (index 0) for members. */
    public List<Parameter> parameters() {
        return parameters;
    }

    public int arity() {
        return parameters.size();
    }

    public Type returnType() {
        return returnType;
    }

    /** Runtime representation of the result; decides which {@code NativeFunction} shape binds it. */
    public Representation returnRepresentation() {
        return returnType.representation();
    }

    public Set<Effect> effects() {
        return effects;
    }

    public boolean isPure() {
        return effects.contains(Effect.PURE);
    }

    public ThreadingRequirement threading() {
        return threading;
    }

    /**
     * Index of the parameter whose owner the operation must run on, for {@link
     * ThreadingRequirement#ENTITY} and {@link ThreadingRequirement#REGION}; {@code -1} otherwise.
     */
    public int threadingParameter() {
        return threadingParameter;
    }

    @Override
    public String toString() {
        return key;
    }

    static String signature(String name, List<Parameter> parameters) {
        StringJoiner joiner = new StringJoiner(", ", name + "(", ")");
        for (Parameter parameter : parameters) {
            joiner.add(parameter.type().displayName());
        }
        return joiner.toString();
    }
}
