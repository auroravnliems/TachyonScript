package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.storage.Codec;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * How values of storable Minecraft types are saved in {@code persistent} and
 * {@code playerdata} variables. Encodings are plain text and stay readable by later versions.
 */
public final class Codecs {

    /** {@code x,y,z}. */
    public static final Codec VECTOR = new Codec() {
        @Override
        public String encode(Object value) {
            Vector vector = (Vector) value;
            return vector.getX() + "," + vector.getY() + "," + vector.getZ();
        }

        @Override
        public Object decode(String text) {
            String[] parts = split(text, 3);
            return new Vector(number(parts[0]), number(parts[1]), number(parts[2]));
        }
    };

    /** {@code #rrggbb}. */
    public static final Codec COLOR = new Codec() {
        @Override
        public String encode(Object value) {
            return String.format(Locale.ROOT, "#%06x", ((Color) value).asRGB());
        }

        @Override
        public Object decode(String text) {
            Color color = Worlds.color(text);
            if (color == null) {
                throw new IllegalArgumentException("Not a color: " + text);
            }
            return color;
        }
    };

    /** Base64 of Minecraft's own item format (keeps every component; upgraded by the server across versions). */
    public static final Codec ITEM_STACK = new Codec() {
        @Override
        public String encode(Object value) {
            ItemStack item = (ItemStack) value;
            return item.isEmpty() ? "air" : Base64.getEncoder().encodeToString(item.serializeAsBytes());
        }

        @Override
        public Object decode(String text) {
            if (text.equals("air")) {
                return ItemStack.empty();
            }
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(text));
        }
    };

    /** {@code type;ticks;amplifier;ambient;particles;icon}. */
    public static final Codec POTION_EFFECT = new Codec() {
        @Override
        public String encode(Object value) {
            PotionEffect effect = (PotionEffect) value;
            return effect.getType().getKey().asString() + ";" + effect.getDuration() + ";" + effect.getAmplifier() + ";"
                    + effect.isAmbient() + ";" + effect.hasParticles() + ";" + effect.hasIcon();
        }

        @Override
        public Object decode(String text) {
            String[] parts = split(text, 6);
            NamespacedKey key = NamespacedKey.fromString(parts[0]);
            PotionEffectType type = key == null ? null
                    : RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).get(key);
            if (type == null) {
                throw new IllegalArgumentException("Unknown potion effect " + parts[0]);
            }
            return new PotionEffect(type, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Boolean.parseBoolean(parts[3]), Boolean.parseBoolean(parts[4]), Boolean.parseBoolean(parts[5]));
        }
    };

    /** {@code world;x;y;z;yaw;pitch}; a world that is not loaded any more gives a location without world. */
    public static final Codec LOCATION = new Codec() {
        @Override
        public String encode(Object value) {
            Location location = (Location) value;
            World world = location.getWorld();
            return (world == null ? "" : world.getName()) + ";" + location.getX() + ";" + location.getY() + ";"
                    + location.getZ() + ";" + location.getYaw() + ";" + location.getPitch();
        }

        @Override
        public Object decode(String text) {
            String[] parts = split(text, 6);
            World world = parts[0].isEmpty() ? null : Bukkit.getWorld(parts[0]);
            return new Location(world, number(parts[1]), number(parts[2]), number(parts[3]),
                    (float) number(parts[4]), (float) number(parts[5]));
        }
    };

    /** The world's name. */
    public static final Codec WORLD = new Codec() {
        @Override
        public String encode(Object value) {
            return ((World) value).getName();
        }

        @Override
        public Object decode(String text) {
            World world = Bukkit.getWorld(text);
            if (world == null) {
                throw new IllegalArgumentException("The world '" + text + "' is not loaded");
            }
            return world;
        }
    };

    /** The player's UUID. */
    public static final Codec OFFLINE_PLAYER = new Codec() {
        @Override
        public String encode(Object value) {
            return ((OfflinePlayer) value).getUniqueId().toString();
        }

        @Override
        public Object decode(String text) {
            return Bukkit.getOfflinePlayer(UUID.fromString(text));
        }
    };

    private Codecs() {
    }

    private static String[] split(String text, int parts) {
        String[] split = text.split(parts == 3 ? "," : ";", -1);
        if (split.length != parts) {
            throw new IllegalArgumentException("Expected " + parts + " parts: " + text);
        }
        return split;
    }

    private static double number(String text) {
        return Double.parseDouble(text);
    }
}
