package dev.tachyonscript.platform.paper.lib;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

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

    public static List<Object> plugins() {
        List<Object> names = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            names.add(plugin.getName());
        }
        return names;
    }
}
