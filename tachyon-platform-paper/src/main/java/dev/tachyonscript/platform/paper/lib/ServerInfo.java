package dev.tachyonscript.platform.paper.lib;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Server performance and information helpers of the standard library bindings. */
public final class ServerInfo {

    private ServerInfo() {
    }

    /** Ticks per second over the last minute (20 when the server cannot tell, e.g. on Folia). */
    public static double tps() {
        try {
            return Math.min(20.0, Bukkit.getTPS()[0]);
        } catch (UnsupportedOperationException notSupported) {
            return 20.0;
        }
    }

    public static double mspt() {
        try {
            return Bukkit.getAverageTickTime();
        } catch (UnsupportedOperationException notSupported) {
            return 0;
        }
    }

    /** How long the server has been running, in milliseconds. */
    public static long uptime() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }

    /**
     * Sends a message to every online player with a permission, and to the console.
     *
     * <p>{@code Bukkit.broadcast(message, permission)} only reaches the senders <em>subscribed</em>
     * to the permission. Bukkit subscribes a sender to the permissions plugins registered and to
     * those given to it explicitly, so a permission a script simply checks ({@code staff.alerts})
     * reached nobody — not even operators or the console — unless a permissions plugin granted it
     * by name. Asking every player instead follows {@code hasPermission}, like the rest of the
     * scripts do. The {@link BroadcastMessageEvent} is still fired, so chat bridges see it.
     */
    public static void broadcast(Component message, String permission) {
        Set<CommandSender> recipients = new LinkedHashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(permission)) {
                recipients.add(player);
            }
        }
        recipients.add(Bukkit.getConsoleSender());
        BroadcastMessageEvent event = new BroadcastMessageEvent(!Bukkit.isPrimaryThread(), message, recipients);
        if (!event.callEvent()) {
            return;
        }
        for (CommandSender recipient : event.getRecipients()) {
            recipient.sendMessage(event.message());
        }
    }

    public static List<Object> plugins() {
        List<Object> names = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            names.add(plugin.getName());
        }
        return names;
    }
}
