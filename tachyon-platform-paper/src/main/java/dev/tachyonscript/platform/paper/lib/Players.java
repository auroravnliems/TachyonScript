package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.platform.paper.PaperContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Player helpers of the standard library bindings. */
public final class Players {

    /** Key of attachments made while no script runs (they live until the player leaves). */
    private static final Object NO_SCRIPT = new Object();

    /** Permission attachments per player and per script that gave them. */
    private static final Map<Player, Map<Object, PermissionAttachment>> ATTACHMENTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private Players() {
    }

    /** The player of an inventory event (human entities are players on Paper). */
    public static Player of(HumanEntity human) {
        if (human instanceof Player player) {
            return player;
        }
        throw new ScriptError(human.getName() + " is not a player.");
    }

    public static String stripSlash(String command) {
        String trimmed = command.strip();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    public static String withSlash(String command) {
        String trimmed = command.strip();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    /** The command name of a command line: {@code /Spawn now} gives {@code spawn}. */
    public static String label(String message) {
        String line = stripSlash(message);
        int space = line.indexOf(' ');
        return (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    public static void setFlying(Player player, boolean flying) {
        if (flying && !player.getAllowFlight()) {
            player.setAllowFlight(true);
        }
        player.setFlying(flying);
    }

    public static String address(Player player) {
        InetSocketAddress address = player.getAddress();
        return address == null || address.getAddress() == null ? null : address.getAddress().getHostAddress();
    }

    public static void title(Player player, Component title, Component subtitle, long fadeIn, long stay, long fadeOut) {
        player.showTitle(Title.title(title, subtitle, Title.Times.times(Duration.ofMillis(Math.max(0, fadeIn)),
                Duration.ofMillis(Math.max(0, stay)), Duration.ofMillis(Math.max(0, fadeOut)))));
    }

    /** Bans a player (for a duration, or forever when it is 0 or less) and kicks them if online. */
    public static void ban(PaperContext context, OfflinePlayer player, String reason, long durationMillis) {
        Duration duration = durationMillis > 0 ? Duration.ofMillis(durationMillis) : null;
        player.ban(reason, duration, "TachyonScript");
        Player online = player.getPlayer();
        if (online != null) {
            context.forEntity(online, () -> online.kick(Component.text(reason)));
        }
    }

    public static void pardon(OfflinePlayer player) {
        Bukkit.getBanList(io.papermc.paper.ban.BanListType.PROFILE).pardon(player.getPlayerProfile());
    }

    /** Gives a permission through an attachment owned by the running script (removed when it reloads). */
    public static void addPermission(PaperContext context, Player player, String permission) {
        Object script = ScriptEngine.runningScript();
        Object key = script == null ? NO_SCRIPT : script;
        PermissionAttachment attachment;
        synchronized (ATTACHMENTS) {
            Map<Object, PermissionAttachment> byScript = ATTACHMENTS.computeIfAbsent(player, p -> new ConcurrentHashMap<>());
            attachment = byScript.get(key);
            if (attachment == null) {
                attachment = player.addAttachment(context.plugin());
                byScript.put(key, attachment);
                context.own(attachment, owned -> ((PermissionAttachment) owned).remove());
            }
        }
        attachment.setPermission(permission, true);
    }

    public static void removePermission(Player player, String permission) {
        Map<Object, PermissionAttachment> byScript = ATTACHMENTS.get(player);
        if (byScript != null) {
            for (PermissionAttachment attachment : byScript.values()) {
                attachment.unsetPermission(permission);
            }
        }
    }
}
