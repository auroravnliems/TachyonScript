package dev.tachyonscript.platform.paper;

import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.KeyTable;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.storage.KeyedValues;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.engine.spi.ArgumentTypes;
import dev.tachyonscript.stdlib.MinecraftTypes;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command arguments of Minecraft types: online and offline players by name, worlds, game
 * modes, and every keyed type ({@code Material}, {@code Sound}, ...) by key or constant name
 * ({@code diamond}, {@code minecraft:diamond} or {@code DIAMOND}).
 */
final class PaperArgumentTypes implements ArgumentTypes {

    private static final int MAX_SUGGESTIONS = 100;

    private final SymbolRegistry registry;
    private final Bindings bindings;

    PaperArgumentTypes(SymbolRegistry registry, Bindings bindings) {
        this.registry = registry;
        this.bindings = bindings;
    }

    @Override
    public boolean supports(ClassType type) {
        return type == MinecraftTypes.PLAYER || type == MinecraftTypes.OFFLINE_PLAYER || type == MinecraftTypes.WORLD
                || type == MinecraftTypes.GAME_MODE || (type.isKeyed() && bindings.keyedValues(type).isPresent());
    }

    @Override
    public Object parse(ClassType type, String text, Object sender) throws InvalidArgument {
        if (type == MinecraftTypes.PLAYER) {
            Player player = Bukkit.getPlayerExact(text);
            if (player == null) {
                player = Bukkit.getPlayer(text);
            }
            if (player == null) {
                throw new InvalidArgument("Player '" + text + "' is not online.");
            }
            return player;
        }
        if (type == MinecraftTypes.OFFLINE_PLAYER) {
            OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(text);
            if (player == null) {
                throw new InvalidArgument("No player named '" + text + "' has played on this server.");
            }
            return player;
        }
        if (type == MinecraftTypes.WORLD) {
            World world = Bukkit.getWorld(text);
            if (world == null) {
                throw new InvalidArgument("World '" + text + "' is not loaded.");
            }
            return world;
        }
        if (type == MinecraftTypes.GAME_MODE) {
            for (GameMode mode : GameMode.values()) {
                if (mode.name().equalsIgnoreCase(text)) {
                    return mode;
                }
            }
            throw new InvalidArgument("'" + text + "' is not a game mode (survival, creative, adventure or spectator).");
        }
        KeyedValues values = bindings.keyedValues(type)
                .orElseThrow(() -> new InvalidArgument("Commands cannot take " + type.name() + " values here."));
        String key = key(type, text);
        Object value = key == null ? null : values.resolve(key);
        if (value == null) {
            throw new InvalidArgument("Unknown " + type.name() + " '" + text + "'.");
        }
        return value;
    }

    /** {@code diamond}, {@code DIAMOND} or {@code minecraft:diamond} to a key of the type. */
    private String key(ClassType type, String text) {
        KeyTable table = registry.keys(type);
        String upper = text.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
        String byName = table.key(upper).orElse(null);
        if (byName != null) {
            return byName;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "minecraft:" + lower;
    }

    @Override
    public List<String> suggest(ClassType type, String prefix, Object sender) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        if (type == MinecraftTypes.PLAYER || type == MinecraftTypes.OFFLINE_PLAYER) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                    result.add(player.getName());
                }
            }
            return result;
        }
        if (type == MinecraftTypes.WORLD) {
            for (World world : Bukkit.getWorlds()) {
                if (world.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                    result.add(world.getName());
                }
            }
            return result;
        }
        if (type == MinecraftTypes.GAME_MODE) {
            for (GameMode mode : GameMode.values()) {
                String name = mode.name().toLowerCase(Locale.ROOT);
                if (name.startsWith(lower)) {
                    result.add(name);
                }
            }
            return result;
        }
        for (String name : registry.keys(type).names()) {
            String suggestion = name.toLowerCase(Locale.ROOT);
            if (suggestion.startsWith(lower)) {
                result.add(suggestion);
                if (result.size() >= MAX_SUGGESTIONS) {
                    break;
                }
            }
        }
        return result;
    }
}
