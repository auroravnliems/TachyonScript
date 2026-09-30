package dev.tachyonscript.engine.spi;

import dev.tachyonscript.api.declaration.EventDeclaration;

import java.util.Map;
import java.util.Set;

/**
 * Connects platform events to the engine. After every activation the engine tells the
 * bridge which events have handlers, and at which priorities; the bridge listens to exactly
 * those (so that, for example, no move listener is registered when no script handles
 * {@code player.move}) and forwards each event to {@code ScriptEngine.dispatch}.
 */
public interface EventBridge {

    /**
     * Called after a new generation became current. {@code priorities} holds, for each event
     * with handlers, the priorities used (0 lowest to 5 monitor). Must be thread-safe.
     */
    void activeEventsChanged(Map<EventDeclaration, Set<Integer>> priorities);

    /** Whether a platform event object has been cancelled (for handlers with {@code @ignoreCancelled}). */
    boolean isCancelled(Object event);
}
