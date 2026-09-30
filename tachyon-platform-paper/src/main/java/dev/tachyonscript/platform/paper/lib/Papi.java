package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptError;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** PlaceholderAPI without a compile-time dependency: {@code setPlaceholders} is found reflectively once. */
public final class Papi {

    private static volatile Method setPlaceholders;

    private Papi() {
    }

    public static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    /** Replaces placeholders; the text is returned unchanged without PlaceholderAPI. */
    public static String parse(OfflinePlayer player, String text) {
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) {
            return text;
        }
        try {
            Method method = setPlaceholders;
            if (method == null) {
                Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, papi.getClass().getClassLoader());
                method = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
                setPlaceholders = method;
            }
            return (String) method.invoke(null, player, text);
        } catch (InvocationTargetException e) {
            throw new ScriptError("PlaceholderAPI failed: " + e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new ScriptError("This PlaceholderAPI version is not supported: " + e.getMessage());
        }
    }
}
