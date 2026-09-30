package me.clip.placeholderapi;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Compile-time stand-in for PlaceholderAPI's class of the same name (only the members
 * TachyonScript uses). It is not packaged: on a server the real class is used.
 */
public abstract class PlaceholderHook {

    public String onRequest(OfflinePlayer player, String params) {
        return null;
    }

    public String onPlaceholderRequest(Player player, String params) {
        return null;
    }
}
