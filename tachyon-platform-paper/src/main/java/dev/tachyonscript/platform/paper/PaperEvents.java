package dev.tachyonscript.platform.paper;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.platform.paper.generated.GeneratedBindings;
import dev.tachyonscript.stdlib.EventApi;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/** The Bukkit event class of every standard event. */
final class PaperEvents {

    private PaperEvents() {
    }

    /** Standard events and their Bukkit classes. */
    static Map<EventDeclaration, Class<? extends Event>> standard() {
        Map<EventDeclaration, Class<? extends Event>> classes = new LinkedHashMap<>();
        classes.put(EventApi.PLAYER_JOIN, PlayerJoinEvent.class);
        classes.put(EventApi.PLAYER_QUIT, PlayerQuitEvent.class);
        classes.put(EventApi.PLAYER_DEATH, PlayerDeathEvent.class);
        classes.put(EventApi.PLAYER_CHAT, AsyncChatEvent.class);
        classes.put(EventApi.PLAYER_MOVE, PlayerMoveEvent.class);
        classes.put(EventApi.BLOCK_BREAK, BlockBreakEvent.class);
        classes.put(EventApi.ENTITY_DAMAGE, EntityDamageEvent.class);
        GeneratedBindings.events(classes);
        return classes;
    }

    /** Standard events plus the events declared by addons. */
    static Map<EventDeclaration, Class<? extends Event>> all(Map<EventDeclaration, Class<?>> addonEvents) {
        Map<EventDeclaration, Class<? extends Event>> classes = standard();
        addonEvents.forEach((event, type) -> classes.put(event, type.asSubclass(Event.class)));
        return classes;
    }
}
