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
 */
public final class ClassType implements Type {

    private final String name;
    private final List<ClassType> supertypes;
    private final Documentation documentation;
    private final List<ClassType> linearization;

    private ClassType(Builder builder) {
        this.name = builder.name;
        this.supertypes = List.copyOf(builder.supertypes);
        this.documentation = builder.documentation;
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
