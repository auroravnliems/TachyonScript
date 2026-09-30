package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.value.Values;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Map;

/** World and location helpers of the standard library bindings. */
@SuppressWarnings({"deprecation", "removal", "unchecked"})
public final class Worlds {

    /** Game rule names before Minecraft 1.21.11 that did not simply become snake case. */
    private static final Map<String, String> RENAMED_RULES = Map.ofEntries(
            Map.entry("announceadvancements", "show_advancement_messages"),
            Map.entry("dodaylightcycle", "advance_time"),
            Map.entry("doentitydrops", "entity_drops"),
            Map.entry("dolimitedcrafting", "limited_crafting"),
            Map.entry("domobloot", "mob_drops"),
            Map.entry("domobspawning", "spawn_mobs"),
            Map.entry("dotiledrops", "block_drops"),
            Map.entry("doweathercycle", "advance_weather"),
            Map.entry("naturalregeneration", "natural_health_regeneration"),
            Map.entry("doinsomnia", "spawn_phantoms"),
            Map.entry("doimmediaterespawn", "immediate_respawn"),
            Map.entry("dopatrolspawning", "spawn_patrols"),
            Map.entry("dotraderspawning", "spawn_wandering_traders"),
            Map.entry("dowardenspawning", "spawn_wardens"),
            Map.entry("dovinesspread", "spread_vines"),
            Map.entry("commandblocksenabled", "command_blocks_work"),
            Map.entry("spawnerblocksenabled", "spawner_blocks_work"),
            Map.entry("spawnradius", "respawn_radius"),
            Map.entry("maxcommandchainlength", "max_command_sequence_length"),
            Map.entry("maxcommandforkcount", "max_command_forks"),
            Map.entry("commandmodificationblocklimit", "max_block_modifications"),
            Map.entry("snowaccumulationheight", "max_snow_accumulation_height"),
            Map.entry("minecartmaxspeed", "max_minecart_speed"));

    private Worlds() {
    }

    /** The world of a location; an error if it has none or it was unloaded. */
    public static World world(Location location) {
        World world = location.getWorld();
        if (world == null) {
            throw new ScriptError("The location " + location.getBlockX() + ", " + location.getBlockY() + ", "
                    + location.getBlockZ() + " is not in a loaded world.");
        }
        return world;
    }

    public static boolean isDay(World world) {
        long time = world.getTime();
        return time < 12300 || time > 23850;
    }

    public static void clearWeather(World world, long millis) {
        world.setStorm(false);
        world.setThundering(false);
        world.setClearWeatherDuration(Ticks.of(millis));
    }

    private static GameRule<?> rule(String name) {
        String trimmed = name.strip();
        String key;
        if (trimmed.contains(":")) {
            key = trimmed.toLowerCase(Locale.ROOT);
        } else {
            String renamed = RENAMED_RULES.get(trimmed.toLowerCase(Locale.ROOT));
            key = "minecraft:" + (renamed != null ? renamed
                    : trimmed.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT));
        }
        NamespacedKey namespaced = NamespacedKey.fromString(key);
        return namespaced == null ? null : Registry.GAME_RULE.get(namespaced);
    }

    public static String gameRule(World world, String name) {
        GameRule<?> rule = rule(name);
        if (rule == null) {
            return null;
        }
        Object value = world.getGameRuleValue(rule);
        return value == null ? null : value.toString();
    }

    public static boolean setGameRule(World world, String name, String value) {
        GameRule<?> rule = rule(name);
        if (rule == null) {
            return false;
        }
        Object parsed;
        if (rule.getType() == Boolean.class) {
            String text = value.strip().toLowerCase(Locale.ROOT);
            if (!text.equals("true") && !text.equals("false")) {
                return false;
            }
            parsed = Boolean.valueOf(text);
        } else if (rule.getType() == Integer.class) {
            try {
                parsed = Integer.valueOf(value.strip());
            } catch (NumberFormatException e) {
                return false;
            }
        } else {
            return false;
        }
        return world.setGameRule((GameRule<Object>) rule, parsed);
    }

    public static Entity spawn(World world, EntityType type, Location location) {
        if (!type.isSpawnable() || type.getEntityClass() == null) {
            throw new ScriptError(Texts.keyText(type) + " cannot be spawned.");
        }
        Location target = location.getWorld() == null ? location.clone() : location;
        if (target.getWorld() == null) {
            target.setWorld(world);
        }
        return world.spawnEntity(target, type);
    }

    public static TextDisplay spawnText(Location location, Component text) {
        World world = world(location);
        return world.spawn(location, TextDisplay.class, display -> {
            display.text(text);
            display.setBillboard(Display.Billboard.CENTER);
        });
    }

    public static World create(String name, World.Environment environment) {
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            return existing;
        }
        if (!name.matches("[A-Za-z0-9_\\-/]+")) {
            throw new ScriptError("Invalid world name '" + name + "' (use letters, digits, _ and -).");
        }
        try {
            World world = Bukkit.createWorld(new WorldCreator(name).environment(environment));
            if (world == null) {
                throw new ScriptError("The world '" + name + "' could not be created.");
            }
            return world;
        } catch (UnsupportedOperationException e) {
            throw new ScriptError("This server cannot create worlds while running (Folia).");
        }
    }

    public static Location with(Location base, double x, double y, double z, float yaw, float pitch) {
        return new Location(base.getWorld(), x, y, z, yaw, pitch);
    }

    public static Location lookAt(Location from, Location target) {
        Location result = from.clone();
        Vector direction = target.toVector().subtract(from.toVector());
        if (direction.lengthSquared() > 0) {
            result.setDirection(direction);
        }
        return result;
    }

    public static double distanceSquared(Location from, Location to) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) {
            return Double.MAX_VALUE;
        }
        return from.distanceSquared(to);
    }

    /** Whether a player fits at the location: free feet and head blocks and something solid below. */
    public static boolean isSafe(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        return feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid()
                && ground.getType().isSolid();
    }

    public static Color color(String hex) {
        String text = hex.strip();
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        if (!text.matches("[0-9a-fA-F]{6}")) {
            return null;
        }
        return Color.fromRGB(Integer.parseInt(text, 16));
    }

    public static String vectorText(Vector vector) {
        return "(" + Values.toString(vector.getX()) + ", " + Values.toString(vector.getY()) + ", "
                + Values.toString(vector.getZ()) + ")";
    }
}
