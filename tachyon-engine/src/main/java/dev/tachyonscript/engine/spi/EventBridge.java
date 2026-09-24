package dev.tachyonscript.engine.spi;

import dev.tachyonscript.api.declaration.EventDeclaration;

import java.util.Set;

/**
 * Connects platform events to the engine. After every activation the engine tells the
 * bridge which events have handlers; the bridge listens to exactly those (so that, for
 * example, no move listener is registered when no script handles {@code player.move}) and
 * forwards each event to {@code ScriptEngine.dispatch}.
 */
public interface EventBridge {

    /** Called after a new generation became current. Must be thread-safe. */
    void activeEventsChanged(Set<EventDeclaration> events);
}
