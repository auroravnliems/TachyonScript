package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.value.Values;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Text conversions used by the standard library bindings. */
public final class Texts {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    private Texts() {
    }

    /** An enum constant in lower case: {@code SHIFT_LEFT} becomes {@code shift_left}. */
    public static String enumText(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** The key of a keyed value, such as {@code minecraft:diamond}. */
    public static String key(Object value) {
        if (value instanceof Keyed keyed) {
            return keyed.getKey().asString();
        }
        if (value instanceof net.kyori.adventure.key.Keyed keyed) {
            return keyed.key().asString();
        }
        return Values.toString(value);
    }

    /** The key of a keyed value without the {@code minecraft:} namespace. */
    public static String keyText(Object value) {
        if (value instanceof Keyed keyed) {
            return keyText(keyed.getKey());
        }
        if (value instanceof net.kyori.adventure.key.Keyed keyed) {
            return keyText(keyed.key());
        }
        return Values.toString(value);
    }

    public static String keyText(NamespacedKey key) {
        return NamespacedKey.MINECRAFT.equals(key.getNamespace()) ? key.getKey() : key.asString();
    }

    public static String keyText(Key key) {
        return Key.MINECRAFT_NAMESPACE.equals(key.namespace()) ? key.value() : key.asString();
    }

    /** A key path as words: {@code diamond_sword} becomes {@code Diamond Sword}. */
    public static String pretty(String path) {
        StringBuilder out = new StringBuilder();
        for (String word : path.replace('.', '_').split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** Text with {@code &} or {@code §} color codes (and {@code &#rrggbb}). */
    public static Component legacy(String text) {
        return LEGACY.deserialize(text.replace('§', '&'));
    }

    public static String toLegacy(Component component) {
        return LEGACY.serialize(component);
    }

    public static Component fromJson(String json) {
        try {
            return GsonComponentSerializer.gson().deserialize(json);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    public static Component join(List<Object> parts, Component separator) {
        List<Component> components = new ArrayList<>(parts.size());
        for (Object part : parts) {
            components.add(part == null ? Component.empty() : (Component) part);
        }
        return Component.join(JoinConfiguration.separator(separator), components);
    }
}
