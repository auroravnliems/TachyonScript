package dev.tachyonscript.platform.paper;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.spi.EventBridge;
import dev.tachyonscript.stdlib.EventApi;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registers exactly one Bukkit listener per event that currently has script handlers, and
 * forwards each event to {@link ScriptEngine#dispatch(int, Object)} with the precomputed
 * event index. Listeners for events without handlers are unregistered, so for example a
 * server without {@code player.move} handlers pays nothing for movement.
 */
final class PaperEventBridge implements EventBridge {

    /** Bukkit event class of every standard event. */
    static final Map<EventDeclaration, Class<? extends Event>> EVENT_CLASSES = Map.of(
            EventApi.PLAYER_JOIN, PlayerJoinEvent.class,
            EventApi.PLAYER_QUIT, PlayerQuitEvent.class,
            EventApi.PLAYER_DEATH, PlayerDeathEvent.class,
            EventApi.PLAYER_CHAT, AsyncChatEvent.class,
            EventApi.PLAYER_MOVE, PlayerMoveEvent.class,
            EventApi.BLOCK_BREAK, BlockBreakEvent.class,
            EventApi.ENTITY_DAMAGE, EntityDamageEvent.class);

    private final Plugin plugin;
    private final SymbolRegistry registry;
    private final Map<EventDeclaration, Class<? extends Event>> classes;
    private final Map<EventDeclaration, Listener> registered = new HashMap<>();
    private volatile ScriptEngine engine;

    /** @param addonEvents Bukkit event classes of events declared by addons (checked by the caller) */
    PaperEventBridge(Plugin plugin, SymbolRegistry registry, Map<EventDeclaration, Class<?>> addonEvents) {
        this.plugin = plugin;
        this.registry = registry;
        Map<EventDeclaration, Class<? extends Event>> all = new HashMap<>(EVENT_CLASSES);
        addonEvents.forEach((event, type) -> all.put(event, type.asSubclass(Event.class)));
        this.classes = Map.copyOf(all);
    }

    void attach(ScriptEngine scriptEngine) {
        this.engine = scriptEngine;
    }

    @Override
    public synchronized void activeEventsChanged(Set<EventDeclaration> events) {
        registered.entrySet().removeIf(entry -> {
            if (events.contains(entry.getKey())) {
                return false;
            }
            HandlerList.unregisterAll(entry.getValue());
            return true;
        });
        for (EventDeclaration event : events) {
            if (registered.containsKey(event)) {
                continue;
            }
            Class<? extends Event> type = classes.get(event);
            if (type == null) {
                plugin.getLogger().warning("No Bukkit event is bound to '" + event.name() + "'; its handlers will not run.");
                continue;
            }
            int index = registry.eventIndex(event);
            Listener listener = new Listener() {
            };
            try {
                Bukkit.getPluginManager().registerEvent(type, listener, EventPriority.NORMAL, (ignored, fired) -> {
                    // Subclass events share the handler list of their parent; accept exact matches and subclasses only.
                    if (type.isInstance(fired)) {
                        ScriptEngine current = engine;
                        if (current != null) {
                            current.dispatch(index, fired);
                        }
                    }
                }, plugin, false);
                registered.put(event, listener);
            } catch (RuntimeException e) {
                // For example an addon event class without a handler list.
                plugin.getLogger().severe("Cannot listen to " + type.getName() + " for '" + event.name()
                        + "': " + e.getMessage() + ". Its handlers will not run.");
            }
        }
    }

    synchronized void unregisterAll() {
        registered.values().forEach(HandlerList::unregisterAll);
        registered.clear();
    }
}
