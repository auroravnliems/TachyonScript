package dev.tachyonscript.platform.paper.lib;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Opening or closing a player's inventory screen is not allowed while the server is handling
 * an inventory event of that player (a click, a drag, an inventory opening or closing): the
 * event would continue on the new screen, and items could be lost or duplicated. Scripts do it
 * all the time — a menu button opens the next page, closing a menu opens another one — so a
 * screen change requested during such an event is made right after it, on the player's next
 * tick; at any other time it happens at once.
 */
public final class Screens {

    /** The player whose inventory event the current thread is handling, or null. */
    private static final ThreadLocal<Object> HANDLING = new ThreadLocal<>();

    private Screens() {
    }

    /** Runs {@code action} (usually script code) while an inventory event of {@code player} is handled. */
    public static void during(Object player, Runnable action) {
        Object previous = HANDLING.get();
        HANDLING.set(player);
        try {
            action.run();
        } finally {
            if (previous == null) {
                HANDLING.remove();
            } else {
                HANDLING.set(previous);
            }
        }
    }

    /** Opens or closes a screen of {@code player}: now, or right after the inventory event being handled. */
    public static void change(Plugin plugin, Player player, Runnable action) {
        if (HANDLING.get() == player) {
            player.getScheduler().run(plugin, task -> action.run(), null);
        } else {
            action.run();
        }
    }
}
