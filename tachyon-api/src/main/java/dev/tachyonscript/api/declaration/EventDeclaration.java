package dev.tachyonscript.api.declaration;

import dev.tachyonscript.api.doc.Deprecation;
import dev.tachyonscript.api.doc.Documentation;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An event scripts can handle with {@code event <name> { ... }}.
 *
 * <p>Inside the handler, {@code event} refers to the event object (of {@link #eventType()})
 * and each {@link EventVariable} is available by name. Event names are resolved at compile
 * time; the platform binds each declaration to its native event class.
 */
public final class EventDeclaration implements Declaration {

    /** Name of the implicit variable holding the event object. */
    public static final String EVENT_OBJECT_VARIABLE = "event";

    private final String name;
    private final ClassType eventType;
    private final List<EventVariable> variables;
    private final boolean cancellable;
    private final ThreadingRequirement threading;
    private final Documentation documentation;
    private final Deprecation deprecation;

    private EventDeclaration(Builder builder) {
        this.name = builder.name;
        this.eventType = builder.eventType;
        this.cancellable = builder.cancellable;
        this.threading = builder.threading;
        this.documentation = builder.documentation;
        this.deprecation = builder.deprecation;
        List<EventVariable> built = new ArrayList<>();
        for (PendingVariable pending : builder.variables) {
            NativeDeclaration getter = new NativeDeclaration("event " + name + ":" + pending.name,
                    NativeDeclaration.Kind.EVENT_VARIABLE, List.of(new Parameter("event", eventType)),
                    pending.type, java.util.Set.of(), ThreadingRequirement.ANY, -1);
            built.add(new EventVariable(pending.name, pending.type, getter, pending.documentation));
        }
        this.variables = List.copyOf(built);
    }

    /**
     * Starts declaring an event.
     *
     * @param name      dotted event name, e.g. {@code player.join}
     * @param eventType type of the {@code event} variable inside handlers
     */
    public static Builder builder(String name, ClassType eventType) {
        return new Builder(Names.requireQualifiedName(name, "event name"), Objects.requireNonNull(eventType, "eventType"));
    }

    @Override
    public String name() {
        return name;
    }

    public ClassType eventType() {
        return eventType;
    }

    public List<EventVariable> variables() {
        return variables;
    }

    public Optional<EventVariable> variable(String variableName) {
        for (EventVariable variable : variables) {
            if (variable.name().equals(variableName)) {
                return Optional.of(variable);
            }
        }
        return Optional.empty();
    }

    /** Whether handlers may cancel the event ({@code event.cancel()}). */
    public boolean isCancellable() {
        return cancellable;
    }

    /** The execution context handlers run in (where the platform fires the event). */
    public ThreadingRequirement threading() {
        return threading;
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
        return "event " + name;
    }

    private record PendingVariable(String name, Type type, Documentation documentation) {
    }

    /** Builder for {@link EventDeclaration}. */
    public static final class Builder {
        private final String name;
        private final ClassType eventType;
        private final List<PendingVariable> variables = new ArrayList<>();
        private boolean cancellable;
        private ThreadingRequirement threading = ThreadingRequirement.GLOBAL;
        private Documentation documentation = Documentation.NONE;
        private Deprecation deprecation;

        private Builder(String name, ClassType eventType) {
            this.name = name;
            this.eventType = eventType;
        }

        /** Adds a handler variable. */
        public Builder variable(String name, Type type, String summary) {
            Names.requireIdentifier(name, "event variable name");
            if (name.equals(EVENT_OBJECT_VARIABLE)) {
                throw new IllegalArgumentException("'" + EVENT_OBJECT_VARIABLE + "' is reserved for the event object");
            }
            for (PendingVariable existing : variables) {
                if (existing.name.equals(name)) {
                    throw new IllegalArgumentException("Duplicate event variable '" + name + "' in " + this.name);
                }
            }
            variables.add(new PendingVariable(name, Objects.requireNonNull(type, "type"), Documentation.of(summary)));
            return this;
        }

        public Builder cancellable() {
            this.cancellable = true;
            return this;
        }

        public Builder threading(ThreadingRequirement requirement) {
            this.threading = Objects.requireNonNull(requirement, "requirement");
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

        public EventDeclaration build() {
            return new EventDeclaration(this);
        }
    }
}
