package dev.tachyonscript.api.declaration;

import dev.tachyonscript.api.doc.Deprecation;
import dev.tachyonscript.api.doc.Documentation;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Type;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A callable host function: either a global function such as {@code math.abs} or
 * {@code broadcast}, or a method of a class type such as {@code Player.send}.
 *
 * <p>Several declarations may share a name as long as their parameter types differ
 * (overloads); the compiler selects one at compile time.
 */
public final class FunctionDeclaration implements Declaration {

    private final String name;
    private final ClassType owner;
    private final List<Parameter> parameters;
    private final NativeDeclaration invocable;
    private final Documentation documentation;
    private final Deprecation deprecation;

    private FunctionDeclaration(Builder builder) {
        this.name = builder.name;
        this.owner = builder.owner;
        this.parameters = List.copyOf(builder.parameters);
        this.documentation = builder.documentation;
        this.deprecation = builder.deprecation;
        List<Parameter> invocableParameters = new ArrayList<>();
        if (owner != null) {
            invocableParameters.add(new Parameter("self", owner));
        }
        invocableParameters.addAll(parameters);
        String key = (owner != null ? owner.name() + "." : "") + NativeDeclaration.signature(name, parameters);
        int threadingParameter = builder.threadingParameter;
        if (threadingParameter < 0 && owner != null
                && (builder.threading == ThreadingRequirement.ENTITY || builder.threading == ThreadingRequirement.REGION)) {
            threadingParameter = 0;
        }
        this.invocable = new NativeDeclaration(key,
                owner != null ? NativeDeclaration.Kind.METHOD : NativeDeclaration.Kind.FUNCTION,
                invocableParameters, builder.returnType, builder.effects, builder.threading, threadingParameter);
    }

    /** Declares a global function; {@code qualifiedName} may contain a namespace, e.g. {@code math.abs}. */
    public static Builder global(String qualifiedName) {
        return new Builder(Names.requireQualifiedName(qualifiedName, "function name"), null);
    }

    /** Declares a method callable on values of {@code owner} (and its subtypes). */
    public static Builder method(ClassType owner, String name) {
        return new Builder(Names.requireIdentifier(name, "method name"), Objects.requireNonNull(owner, "owner"));
    }

    /** Qualified name for globals ({@code math.abs}), simple name for methods ({@code send}). */
    @Override
    public String name() {
        return name;
    }

    /** The last segment of the name. */
    public String simpleName() {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /** The namespace of a global function ({@code math} for {@code math.abs}); empty if none. */
    public String namespace() {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(0, dot);
    }

    /** Owning type for methods; empty for global functions. */
    public Optional<ClassType> owner() {
        return Optional.ofNullable(owner);
    }

    public boolean isMethod() {
        return owner != null;
    }

    /** Declared parameters, excluding the receiver. */
    public List<Parameter> parameters() {
        return parameters;
    }

    public Type returnType() {
        return invocable.returnType();
    }

    /** The bindable operation (includes the receiver as parameter 0 for methods). */
    public NativeDeclaration invocable() {
        return invocable;
    }

    @Override
    public Documentation documentation() {
        return documentation;
    }

    @Override
    public Optional<Deprecation> deprecation() {
        return Optional.ofNullable(deprecation);
    }

    /** Signature as shown to users, for example {@code Player.teleport(Location): void}. */
    public String signature() {
        return invocable.key() + ": " + returnType().displayName();
    }

    @Override
    public String toString() {
        return signature();
    }

    /** Builder for {@link FunctionDeclaration}. */
    public static final class Builder {
        private final String name;
        private final ClassType owner;
        private final List<Parameter> parameters = new ArrayList<>();
        private Type returnType = PrimitiveType.VOID;
        private final Set<Effect> effects = EnumSet.noneOf(Effect.class);
        private ThreadingRequirement threading = ThreadingRequirement.ANY;
        private int threadingParameter = -1;
        private Documentation documentation = Documentation.NONE;
        private Deprecation deprecation;

        private Builder(String name, ClassType owner) {
            this.name = name;
            this.owner = owner;
        }

        public Builder parameter(String name, Type type) {
            for (Parameter existing : parameters) {
                if (existing.name().equals(name)) {
                    throw new IllegalArgumentException("Duplicate parameter '" + name + "' in " + this.name);
                }
            }
            parameters.add(new Parameter(name, type));
            return this;
        }

        public Builder returns(Type type) {
            this.returnType = Objects.requireNonNull(type, "returnType");
            return this;
        }

        public Builder effects(Effect... effects) {
            this.effects.addAll(List.of(effects));
            return this;
        }

        /** Threading requirement; for methods, ENTITY/REGION default to the receiver. */
        public Builder threading(ThreadingRequirement requirement) {
            this.threading = Objects.requireNonNull(requirement, "threading");
            return this;
        }

        /**
         * Threading requirement bound to a specific invocable parameter index (the receiver of
         * a method is index 0, its first declared parameter index 1).
         */
        public Builder threading(ThreadingRequirement requirement, int invocableParameterIndex) {
            this.threading = Objects.requireNonNull(requirement, "threading");
            this.threadingParameter = invocableParameterIndex;
            return this;
        }

        public Builder documentation(Documentation documentation) {
            this.documentation = Objects.requireNonNull(documentation, "documentation");
            return this;
        }

        public Builder doc(String summary) {
            return documentation(Documentation.of(summary));
        }

        public Builder deprecated(Deprecation deprecation) {
            this.deprecation = Objects.requireNonNull(deprecation, "deprecation");
            return this;
        }

        public FunctionDeclaration build() {
            return new FunctionDeclaration(this);
        }
    }
}
