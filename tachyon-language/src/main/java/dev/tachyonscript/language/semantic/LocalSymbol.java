package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.declaration.EventVariable;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.language.source.Span;

/**
 * A local value: variable, parameter, loop variable, event variable, the event object, or
 * a compiler-generated temporary. Compared by identity.
 */
public final class LocalSymbol {

    /** Where the local comes from. */
    public enum Kind {
        VARIABLE,
        PARAMETER,
        LOOP_VARIABLE,
        /** A variable provided by an event, such as {@code player} in {@code event player.join}. */
        EVENT_VARIABLE,
        /** The {@code event} object of a handler. */
        EVENT_OBJECT,
        /** A compiler-generated temporary. */
        TEMPORARY
    }

    private final String name;
    private final Type type;
    private final boolean mutable;
    private final Kind kind;
    private final Span declaration;
    private final EventVariable eventVariable;
    private boolean read;
    private String knownString;

    LocalSymbol(String name, Type type, boolean mutable, Kind kind, Span declaration, EventVariable eventVariable) {
        this.name = name;
        this.type = type;
        this.mutable = mutable;
        this.kind = kind;
        this.declaration = declaration;
        this.eventVariable = eventVariable;
    }

    public String name() {
        return name;
    }

    /** Declared type (narrowing never changes it; it only changes the type of individual loads). */
    public Type type() {
        return type;
    }

    public boolean isMutable() {
        return mutable;
    }

    public Kind kind() {
        return kind;
    }

    public Span declaration() {
        return declaration;
    }

    /** The event variable whose getter initializes this local, or {@code null}. */
    public EventVariable eventVariable() {
        return eventVariable;
    }

    /** Whether the local is read anywhere. */
    public boolean isRead() {
        return read;
    }

    void markRead() {
        read = true;
    }

    /** For {@code let} locals initialized with a string literal: that string (used for hints). */
    String knownString() {
        return knownString;
    }

    void knownString(String value) {
        this.knownString = value;
    }

    @Override
    public String toString() {
        return name + ": " + type.displayName();
    }
}
