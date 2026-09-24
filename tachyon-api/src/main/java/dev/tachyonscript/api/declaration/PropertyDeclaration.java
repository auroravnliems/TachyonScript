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
 * A readable (and optionally writable) property: either a member such as
 * {@code LivingEntity.health} or a global such as {@code server.players}.
 *
 * <p>Reads and writes compile to calls of the {@link #getter()} and {@link #setter()}
 * operations, which are bound independently by the platform.
 */
public final class PropertyDeclaration implements Declaration {

    private final String name;
    private final ClassType owner;
    private final Type type;
    private final NativeDeclaration getter;
    private final NativeDeclaration setter;
    private final Documentation documentation;
    private final Deprecation deprecation;

    private PropertyDeclaration(Builder builder) {
        this.name = builder.name;
        this.owner = builder.owner;
        this.type = builder.type;
        this.documentation = builder.documentation;
        this.deprecation = builder.deprecation;
        String keyBase = owner != null ? owner.name() + "." + name : name;
        List<Parameter> getterParameters = new ArrayList<>();
        if (owner != null) {
            getterParameters.add(new Parameter("self", owner));
        }
        this.getter = new NativeDeclaration(keyBase + ":get", NativeDeclaration.Kind.GETTER, getterParameters, type,
                builder.getterEffects, builder.getterThreading, threadingIndex(builder.getterThreading));
        if (builder.mutable) {
            List<Parameter> setterParameters = new ArrayList<>(getterParameters);
            setterParameters.add(new Parameter("value", type));
            this.setter = new NativeDeclaration(keyBase + ":set", NativeDeclaration.Kind.SETTER, setterParameters,
                    PrimitiveType.VOID, builder.setterEffects, builder.setterThreading,
                    threadingIndex(builder.setterThreading));
        } else {
            this.setter = null;
        }
    }

    private int threadingIndex(ThreadingRequirement requirement) {
        boolean needsTarget = requirement == ThreadingRequirement.ENTITY || requirement == ThreadingRequirement.REGION;
        if (needsTarget && owner == null) {
            throw new IllegalArgumentException("Global property " + name + " cannot require " + requirement + " threading");
        }
        return needsTarget ? 0 : -1;
    }

    /** Declares a property of {@code owner}. */
    public static Builder member(ClassType owner, String name, Type type) {
        return new Builder(Objects.requireNonNull(owner, "owner"), Names.requireIdentifier(name, "property name"), type);
    }

    /** Declares a global property such as {@code server.players}. */
    public static Builder global(String qualifiedName, Type type) {
        return new Builder(null, Names.requireQualifiedName(qualifiedName, "property name"), type);
    }

    @Override
    public String name() {
        return name;
    }

    public Optional<ClassType> owner() {
        return Optional.ofNullable(owner);
    }

    public boolean isGlobal() {
        return owner == null;
    }

    public Type type() {
        return type;
    }

    public NativeDeclaration getter() {
        return getter;
    }

    /** The setter, or empty for read-only properties. */
    public Optional<NativeDeclaration> setter() {
        return Optional.ofNullable(setter);
    }

    public boolean isMutable() {
        return setter != null;
    }

    @Override
    public Documentation documentation() {
        return documentation;
    }

    @Override
    public Optional<Deprecation> deprecation() {
        return Optional.ofNullable(deprecation);
    }

    @Override
    public String toString() {
        return (owner != null ? owner.name() + "." : "") + name + ": " + type.displayName();
    }

    /** Builder for {@link PropertyDeclaration}. */
    public static final class Builder {
        private final ClassType owner;
        private final String name;
        private final Type type;
        private boolean mutable;
        private final Set<Effect> getterEffects = EnumSet.noneOf(Effect.class);
        private final Set<Effect> setterEffects = EnumSet.noneOf(Effect.class);
        private ThreadingRequirement getterThreading = ThreadingRequirement.ANY;
        private ThreadingRequirement setterThreading = ThreadingRequirement.ANY;
        private Documentation documentation = Documentation.NONE;
        private Deprecation deprecation;

        private Builder(ClassType owner, String name, Type type) {
            this.owner = owner;
            this.name = name;
            this.type = Objects.requireNonNull(type, "type");
            if (type == PrimitiveType.VOID || type.isError()) {
                throw new IllegalArgumentException("Property '" + name + "' cannot have type " + type.displayName());
            }
        }

        /** Gives the property a setter. */
        public Builder mutable() {
            this.mutable = true;
            return this;
        }

        public Builder getterEffects(Effect... effects) {
            this.getterEffects.addAll(List.of(effects));
            return this;
        }

        public Builder setterEffects(Effect... effects) {
            this.setterEffects.addAll(List.of(effects));
            return this;
        }

        public Builder getterThreading(ThreadingRequirement requirement) {
            this.getterThreading = Objects.requireNonNull(requirement, "requirement");
            return this;
        }

        public Builder setterThreading(ThreadingRequirement requirement) {
            this.setterThreading = Objects.requireNonNull(requirement, "requirement");
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

        public PropertyDeclaration build() {
            return new PropertyDeclaration(this);
        }
    }
}
