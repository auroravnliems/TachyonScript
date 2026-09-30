package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptError;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Economy, chat and permission groups through Vault, without depending on Vault at compile
 * time: the providers are found through Bukkit's services manager and their methods are
 * called through Vault's public service types (looked up once). Without Vault or a provider,
 * reads return neutral values (a balance of 0, an empty prefix) and payments fail.
 */
public final class Vault {

    private static final String ECONOMY = "net.milkbowl.vault.economy.Economy";
    private static final String CHAT = "net.milkbowl.vault.chat.Chat";
    private static final String PERMISSION = "net.milkbowl.vault.permission.Permission";
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    /** A Vault service type and the plugin providing it. */
    private record Service(Class<?> type, Object provider) {
    }

    private Vault() {
    }

    private static Service service(String name) {
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            return null;
        }
        try {
            Class<?> type = Class.forName(name, true, vault.getClass().getClassLoader());
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(type);
            return registration == null ? null : new Service(type, registration.getProvider());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Object call(Service service, String name, Class<?>[] parameters, Object... arguments) {
        String key = service.type().getName() + "#" + name + "/" + parameters.length;
        try {
            Method method = METHODS.get(key);
            if (method == null) {
                method = service.type().getMethod(name, parameters);
                METHODS.put(key, method);
            }
            return method.invoke(service.provider(), arguments);
        } catch (InvocationTargetException e) {
            throw new ScriptError("The Vault provider failed: " + e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new ScriptError("This Vault version does not support " + name + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ economy

    public static boolean economyAvailable() {
        return service(ECONOMY) != null;
    }

    public static double balance(OfflinePlayer player) {
        Service economy = service(ECONOMY);
        return economy == null ? 0
                : ((Number) call(economy, "getBalance", new Class<?>[] {OfflinePlayer.class}, player)).doubleValue();
    }

    public static boolean has(OfflinePlayer player, double amount) {
        Service economy = service(ECONOMY);
        return economy != null
                && (Boolean) call(economy, "has", new Class<?>[] {OfflinePlayer.class, double.class}, player, amount);
    }

    public static boolean deposit(OfflinePlayer player, double amount) {
        Service economy = service(ECONOMY);
        if (economy == null || amount < 0) {
            return false;
        }
        return success(call(economy, "depositPlayer", new Class<?>[] {OfflinePlayer.class, double.class}, player, amount));
    }

    public static boolean withdraw(OfflinePlayer player, double amount) {
        Service economy = service(ECONOMY);
        if (economy == null || amount < 0 || !has(player, amount)) {
            return false;
        }
        return success(call(economy, "withdrawPlayer", new Class<?>[] {OfflinePlayer.class, double.class}, player, amount));
    }

    private static boolean success(Object response) {
        try {
            return (Boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public static String format(double amount) {
        Service economy = service(ECONOMY);
        return economy == null ? String.format(Locale.ROOT, "%,.2f", amount)
                : String.valueOf(call(economy, "format", new Class<?>[] {double.class}, amount));
    }

    public static String currencyName() {
        Service economy = service(ECONOMY);
        return economy == null ? "" : String.valueOf(call(economy, "currencyNamePlural", new Class<?>[0]));
    }

    // ------------------------------------------------------------------ chat and permissions

    public static String prefix(Player player) {
        Service chat = service(CHAT);
        Object value = chat == null ? null : call(chat, "getPlayerPrefix", new Class<?>[] {Player.class}, player);
        return value == null ? "" : value.toString();
    }

    public static String suffix(Player player) {
        Service chat = service(CHAT);
        Object value = chat == null ? null : call(chat, "getPlayerSuffix", new Class<?>[] {Player.class}, player);
        return value == null ? "" : value.toString();
    }

    public static String primaryGroup(Player player) {
        Service permission = service(PERMISSION);
        Object value = permission == null ? null
                : call(permission, "getPrimaryGroup", new Class<?>[] {Player.class}, player);
        return value == null ? null : value.toString();
    }

    public static List<Object> groups(Player player) {
        Service permission = service(PERMISSION);
        List<Object> groups = new ArrayList<>();
        if (permission != null
                && call(permission, "getPlayerGroups", new Class<?>[] {Player.class}, player) instanceof String[] names) {
            groups.addAll(List.of(names));
        }
        return groups;
    }
}
