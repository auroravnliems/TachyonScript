package dev.tachyonscript.api.registry;

import dev.tachyonscript.api.declaration.EventVariable;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.Representation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable map from declarations to their runtime implementations.
 *
 * <p>Declarations are what the compiler sees; bindings are what the linker installs. A
 * platform (Paper, the test platform), the standard library and addons each contribute
 * bindings, which are merged with {@link Builder#include(Bindings)}.
 */
public final class Bindings {

    private record Entry(NativeDeclaration declaration, NativeFunction function) {
    }

    private final Map<String, Entry> functions;
    private final Map<ClassType, Class<?>> typeClasses;

    private Bindings(Builder builder) {
        this.functions = Collections.unmodifiableMap(new LinkedHashMap<>(builder.functions));
        this.typeClasses = Collections.unmodifiableMap(new LinkedHashMap<>(builder.typeClasses));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** An empty set of bindings. */
    public static Bindings empty() {
        return new Builder().build();
    }

    /**
     * Returns the implementation of {@code declaration}, or empty when it is unbound or was
     * bound for a different declaration with the same key.
     */
    public Optional<NativeFunction> lookup(NativeDeclaration declaration) {
        Entry entry = functions.get(declaration.key());
        if (entry == null || !sameSignature(entry.declaration(), declaration)) {
            return Optional.empty();
        }
        return Optional.of(entry.function());
    }

    /** The Java class values of {@code type} are instances of, used for {@code is} tests. */
    public Optional<Class<?>> typeClass(ClassType type) {
        return Optional.ofNullable(typeClasses.get(type));
    }

    public int size() {
        return functions.size();
    }

    /** Keys of every bound declaration, in binding order. */
    public Set<String> boundKeys() {
        return functions.keySet();
    }

    /** Every type with a Java class, in binding order. */
    public Set<ClassType> boundTypes() {
        return typeClasses.keySet();
    }

    private static boolean sameSignature(NativeDeclaration a, NativeDeclaration b) {
        if (a == b) {
            return true;
        }
        if (!a.key().equals(b.key()) || !a.returnType().equals(b.returnType()) || a.arity() != b.arity()) {
            return false;
        }
        for (int i = 0; i < a.arity(); i++) {
            if (!a.parameters().get(i).type().equals(b.parameters().get(i).type())) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code function} has the shape required for results of {@code representation}. */
    public static boolean shapeMatches(Representation representation, NativeFunction function) {
        return switch (function) {
            case NativeFunction.OfVoid ignored -> representation == Representation.VOID;
            case NativeFunction.OfInt ignored -> representation == Representation.INT;
            case NativeFunction.OfLong ignored -> representation == Representation.LONG;
            case NativeFunction.OfFloat ignored -> representation == Representation.FLOAT;
            case NativeFunction.OfDouble ignored -> representation == Representation.DOUBLE;
            case NativeFunction.OfBool ignored -> representation == Representation.BOOL;
            case NativeFunction.OfRef ignored -> representation == Representation.REF;
        };
    }

    /** Builder for {@link Bindings}. Rejected registrations leave the builder unchanged. */
    public static final class Builder {
        private final Map<String, Entry> functions = new LinkedHashMap<>();
        private final Map<ClassType, Class<?>> typeClasses = new LinkedHashMap<>();

        private Builder() {
        }

        /** Binds an operation. The function's shape must match the declared result. */
        public Builder bind(NativeDeclaration declaration, NativeFunction function) {
            Objects.requireNonNull(declaration, "declaration");
            Objects.requireNonNull(function, "function");
            if (!shapeMatches(declaration.returnRepresentation(), function)) {
                throw new RegistrationException("Binding for " + declaration.key() + " returns the wrong shape: "
                        + "declared result " + declaration.returnType().displayName() + " requires NativeFunction."
                        + shapeName(declaration.returnRepresentation()));
            }
            if (functions.containsKey(declaration.key())) {
                throw new RegistrationException("Duplicate binding for " + declaration.key());
            }
            functions.put(declaration.key(), new Entry(declaration, function));
            return this;
        }

        public Builder bind(FunctionDeclaration declaration, NativeFunction function) {
            return bind(declaration.invocable(), function);
        }

        public Builder bindGetter(PropertyDeclaration property, NativeFunction function) {
            return bind(property.getter(), function);
        }

        public Builder bindSetter(PropertyDeclaration property, NativeFunction.OfVoid function) {
            return bind(property.setter().orElseThrow(
                    () -> new RegistrationException("Property " + property + " is read-only")), function);
        }

        public Builder bind(EventVariable variable, NativeFunction function) {
            return bind(variable.getter(), function);
        }

        /** Declares the Java class of values of {@code type} (for {@code is} tests and casts). */
        public Builder bindType(ClassType type, Class<?> javaClass) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(javaClass, "javaClass");
            if (typeClasses.containsKey(type)) {
                throw new RegistrationException("Duplicate class binding for type " + type.name());
            }
            typeClasses.put(type, javaClass);
            return this;
        }

        /** Adds all bindings of {@code other}; fails without changes on any duplicate. */
        public Builder include(Bindings other) {
            for (String key : other.functions.keySet()) {
                if (functions.containsKey(key)) {
                    throw new RegistrationException("Duplicate binding for " + key);
                }
            }
            for (ClassType type : other.typeClasses.keySet()) {
                if (typeClasses.containsKey(type)) {
                    throw new RegistrationException("Duplicate class binding for type " + type.name());
                }
            }
            functions.putAll(other.functions);
            typeClasses.putAll(other.typeClasses);
            return this;
        }

        public Bindings build() {
            return new Bindings(this);
        }

        private static String shapeName(Representation representation) {
            return switch (representation) {
                case INT -> "OfInt";
                case LONG -> "OfLong";
                case FLOAT -> "OfFloat";
                case DOUBLE -> "OfDouble";
                case BOOL -> "OfBool";
                case REF -> "OfRef";
                case VOID -> "OfVoid";
            };
        }
    }
}
