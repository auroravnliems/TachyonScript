package dev.tachyonscript.platform.paper;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.spi.EventBridge;
import dev.tachyonscript.platform.paper.lib.Screens;
import dev.tachyonscript.platform.paper.lib.ScriptMenu;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers one Bukkit listener per event and priority that currently has script handlers,
 * and forwards each event to {@link ScriptEngine#dispatch(int, int, Object)} with the
 * precomputed event index. Listeners for events without handlers are unregistered, so for
 * example a server without {@code player.move} handlers pays nothing for movement.
 *
 * <p>It also loads the saved {@code playerdata} of players before they finish joining and
 * writes it after they leave.
 */
final class PaperEventBridge implements EventBridge {

    /** Bukkit priorities in script priority order (0 lowest to 5 monitor). */
    private static final List<EventPriority> PRIORITIES = List.of(EventPriority.LOWEST, EventPriority.LOW,
            EventPriority.NORMAL, EventPriority.HIGH, EventPriority.HIGHEST, EventPriority.MONITOR);

    private record Key(EventDeclaration event, int priority) {
    }

    private final Plugin plugin;
    private final SymbolRegistry registry;
    private final Map<EventDeclaration, Class<? extends Event>> classes;
    private final Map<Key, Listener> registered = new HashMap<>();
    private final Listener players = new Listener() {
    };
    private volatile ScriptEngine engine;

    /** @param eventClasses Bukkit event class of every event scripts can handle (standard and addon events) */
    PaperEventBridge(Plugin plugin, SymbolRegistry registry, Map<EventDeclaration, Class<? extends Event>> eventClasses) {
        this.plugin = plugin;
        this.registry = registry;
        this.classes = Map.copyOf(eventClasses);
    }

    void attach(ScriptEngine scriptEngine) {
        this.engine = scriptEngine;
        Bukkit.getPluginManager().registerEvent(AsyncPlayerPreLoginEvent.class, players, EventPriority.MONITOR,
                (ignored, fired) -> {
                    if (fired instanceof AsyncPlayerPreLoginEvent login
                            && login.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                        ScriptEngine current = engine;
                        if (current != null) {
                            current.playerJoining(login.getUniqueId());
                        }
                    }
                }, plugin, false);
        Bukkit.getPluginManager().registerEvent(PlayerQuitEvent.class, players, EventPriority.MONITOR, (ignored, fired) -> {
            if (fired instanceof PlayerQuitEvent quit) {
                ScriptEngine current = engine;
                if (current != null) {
                    current.playerQuit(quit.getPlayer().getUniqueId());
                }
            }
        }, plugin, false);
    }

    @Override
    public synchronized void activeEventsChanged(Map<EventDeclaration, Set<Integer>> priorities) {
        registered.entrySet().removeIf(entry -> {
            Set<Integer> levels = priorities.get(entry.getKey().event());
            if (levels != null && levels.contains(entry.getKey().priority())) {
                return false;
            }
            HandlerList.unregisterAll(entry.getValue());
            return true;
        });
        for (Map.Entry<EventDeclaration, Set<Integer>> entry : priorities.entrySet()) {
            EventDeclaration event = entry.getKey();
            Class<? extends Event> type = classes.get(event);
            if (type == null) {
                plugin.getLogger().warning("No Bukkit event is bound to '" + event.name() + "'; its handlers will not run.");
                continue;
            }
            int index = registry.eventIndex(event);
            for (int priority : entry.getValue()) {
                Key key = new Key(event, priority);
                if (registered.containsKey(key)) {
                    continue;
                }
                Listener listener = new Listener() {
                };
                try {
                    Bukkit.getPluginManager().registerEvent(type, listener, PRIORITIES.get(priority), (ignored, fired) -> {
                        // MenuListener owns every click/drag in a ScriptMenu view, including
                        // bottom slots and outside clicks. Generic handlers must not undo its
                        // cancellation or manipulate its cursor/items, at any priority.
                        if ((fired instanceof InventoryClickEvent || fired instanceof InventoryDragEvent)
                                && ((InventoryEvent) fired).getView().getTopInventory().getHolder(false) instanceof ScriptMenu) {
                            return;
                        }
                        // Subclass events share the handler list of their parent; accept exact matches and subclasses only.
                        if (type.isInstance(fired)) {
                            ScriptEngine current = engine;
                            if (current != null) {
                                if (fired instanceof InventoryEvent inventoryEvent) {
                                    // Screens cannot change while the server handles this event; see Screens.
                                    Screens.during(inventoryEvent.getView().getPlayer(),
                                            () -> current.dispatch(index, priority, fired));
                                } else {
                                    current.dispatch(index, priority, fired);
                                }
                            }
                        }
                    }, plugin, false);
                    registered.put(key, listener);
                } catch (RuntimeException e) {
                    // For example an addon event class without a handler list.
                    plugin.getLogger().severe("Cannot listen to " + type.getName() + " for '" + event.name()
                            + "': " + e.getMessage() + ". Its handlers will not run.");
                }
            }
        }
    }

    @Override
    public boolean isCancelled(Object event) {
        return event instanceof Cancellable cancellable && cancellable.isCancelled();
    }

    synchronized void unregisterAll() {
        registered.values().forEach(HandlerList::unregisterAll);
        registered.clear();
        HandlerList.unregisterAll(players);
    }
}
