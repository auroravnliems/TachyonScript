package dev.tachyonscript.api.type;

import dev.tachyonscript.api.doc.Documentation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A nominal reference type such as {@code Player}, {@code Location} or {@code string}.
 *
 * <p>Class types are canonical: exactly one instance exists per registered name, so they
 * are compared by identity. Subtyping is declared explicitly through supertypes (a type may
 * have several, e.g. {@code Player <: LivingEntity, CommandSender}); every class type is
 * implicitly a subtype of {@code any}.
 *
 * <p>Members are not stored here. They are registered separately in the symbol registry so
 * that addons can add members to types they do not own.
 *
 * <p>Three optional traits change how the compiler treats a type:
 * <ul>
 *   <li><b>keyed</b>: the type has a table of named constants (for example
 *       {@code Material.DIAMOND}) registered with
 *       {@link dev.tachyonscript.api.registry.SymbolRegistry.Builder#keys}. The compiler checks
 *       the name when the script loads and the linker resolves it to the platform object, so
 *       using a constant costs nothing at run time.</li>
 *   <li><b>storable</b>: values can be saved in {@code persistent} and {@code playerdata}
 *       variables; the platform binds a codec for the type.</li>
 *   <li><b>constant text</b>: a string converts implicitly to the type only when it is known
 *       when the script loads (used for SQL, so that values are always passed as parameters
 *       and can never be spliced into the statement text).</li>
 * </ul>
 */
public final class ClassType implements Type {

    private final String name;
    private final List<ClassType> supertypes;
    private final Documentation documentation;
    private final List<ClassType> linearization;
    private final boolean keyed;
    private final boolean storable;
    private final boolean constantText;
    private final boolean scriptDefined;
    private final String origin;

    private ClassType(Builder builder) {
        this.name = builder.name;
        this.supertypes = List.copyOf(builder.supertypes);
        this.documentation = builder.documentation;
        this.keyed = builder.keyed;
        this.storable = builder.storable;
        this.constantText = builder.constantText;
        this.scriptDefined = builder.scriptDefined;
        this.origin = builder.origin;
        this.linearization = computeLinearization();
    }

    /** Starts declaring a class type with the given name. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** The type name, e.g. {@code Player}. */
    public String name() {
        return name;
    }

    /** Directly declared supertypes, in declaration order. */
    public List<ClassType> supertypes() {
        return supertypes;
    }

    public Documentation documentation() {
        return documentation;
    }

    /** Whether the type has named constants ({@code Material.DIAMOND}). */
    public boolean isKeyed() {
        return keyed;
    }

    /** Whether values of the type can be stored in persistent variables. */
    public boolean isStorable() {
        return storable;
    }

    /** Whether only compile-time constant strings convert implicitly to this type. */
    public boolean isConstantText() {
        return constantText;
    }

    /** Whether the type was declared by a script (a {@code record}) rather than registered by a library. */
    public boolean isScriptDefined() {
        return scriptDefined;
    }

    /** For a type declared by a script: the name of the declaring module; otherwise an empty string. */
    public String origin() {
        return origin;
    }

    /** For a type declared by a script: {@code module::Name}; otherwise the name. */
    public String qualifiedName() {
        return scriptDefined ? origin + "::" + name : name;
    }

    /**
     * This type followed by all of its supertypes, depth-first in declaration order,
     * without duplicates. Member lookup searches types in this order, so a member declared
     * on a more specific type hides one declared on a supertype.
     */
    public List<ClassType> linearization() {
        return linearization;
    }

    /** Whether this type is {@code other} or one of its (transitive) subtypes. */
    public boolean isSubtypeOf(ClassType other) {
        return other == this || other == Types.ANY || linearization.contains(other);
    }

    @Override
    public String displayName() {
        return name;
    }

    @Override
    public Representation representation() {
        return Representation.REF;
    }

    @Override
    public String toString() {
        return name;
    }

    private List<ClassType> computeLinearization() {
        Set<ClassType> ordered = new LinkedHashSet<>();
        ordered.add(this);
        for (ClassType supertype : supertypes) {
            ordered.addAll(supertype.linearization());
        }
        return List.copyOf(new ArrayList<>(ordered));
    }

    /** Builder for {@link ClassType}. */
    public static final class Builder {
        private final String name;
        private final List<ClassType> supertypes = new ArrayList<>();
        private Documentation documentation = Documentation.NONE;
        private boolean keyed;
        private boolean storable;
        private boolean constantText;
        private boolean scriptDefined;
        private String origin = "";

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
            if (!isValidTypeName(name)) {
                throw new IllegalArgumentException("Invalid type name '" + name + "'");
            }
        }

        /** Adds direct supertypes. */
        public Builder supertypes(ClassType... types) {
            for (ClassType type : types) {
                Objects.requireNonNull(type, "supertype");
                if (supertypes.contains(type)) {
                    throw new IllegalArgumentException("Duplicate supertype " + type + " of " + name);
                }
                supertypes.add(type);
            }
            return this;
        }

        public Builder documentation(Documentation documentation) {
            this.documentation = Objects.requireNonNull(documentation, "documentation");
            return this;
        }

        public Builder doc(String summary) {
            return documentation(Documentation.of(summary));
        }

        /** The type has named constants, registered with {@code SymbolRegistry.Builder.keys}. */
        public Builder keyed() {
            this.keyed = true;
            return this;
        }

        /** Values can be saved in persistent variables; the platform must bind a codec. */
        public Builder storable() {
            this.storable = true;
            return this;
        }

        /** Only compile-time constant strings convert implicitly to the type. */
        public Builder constantText() {
            this.constantText = true;
            return this;
        }

        /** Marks a type declared by a script ({@code record}); used by the compiler only. */
        public Builder scriptDefined() {
            this.scriptDefined = true;
            return this;
        }

        /** Marks a type declared by the script module {@code module} ({@code record}); used by the compiler only. */
        public Builder scriptDefined(String module) {
            this.scriptDefined = true;
            this.origin = java.util.Objects.requireNonNull(module, "module");
            return this;
        }

        public ClassType build() {
            return new ClassType(this);
        }
    }

    static boolean isValidTypeName(String name) {
        if (name.isEmpty() || !(Character.isLetter(name.charAt(0)) && name.charAt(0) < 128)) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 128 || !(Character.isLetterOrDigit(c) || c == '_')) {
                return false;
            }
        }
        return true;
    }
}
