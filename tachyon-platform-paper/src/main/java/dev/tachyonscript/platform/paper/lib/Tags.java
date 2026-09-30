package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptError;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Tags: values stored in the persistent data container of an item, an entity, a chunk or a
 * world, which Minecraft saves with it. Blocks have no container of their own, so their tags
 * are kept in the container of their chunk under a key that includes the block position.
 *
 * <p>Keys are {@code name} (in the {@code tachyonscript} namespace) or {@code namespace:name};
 * they may contain lower case letters, digits, {@code . _ - /}.
 */
public final class Tags {

    private static final String NAMESPACE = "tachyonscript";

    private Tags() {
    }

    /** Where a tag lives: a readable view, a way to edit it, and the key inside. */
    private record Slot(PersistentDataContainerView view, Consumer<Consumer<PersistentDataContainer>> editor,
                        NamespacedKey key) {
    }

    static NamespacedKey key(String key) {
        String text = key.strip().toLowerCase(Locale.ROOT);
        NamespacedKey result = text.contains(":") ? NamespacedKey.fromString(text) : NamespacedKey.fromString(NAMESPACE + ":" + text);
        if (result == null || text.isEmpty()) {
            throw new ScriptError("Invalid tag key '" + key + "': use lower case letters, digits and . _ - / "
                    + "(optionally namespace:name).");
        }
        return result;
    }

    private static Slot slot(Object holder, String key) {
        NamespacedKey namespaced = key(key);
        return switch (holder) {
            case ItemStack item -> new Slot(item.getPersistentDataContainer(),
                    edit -> {
                        if (!item.editPersistentDataContainer(edit)) {
                            throw new ScriptError("Air cannot have tags.");
                        }
                    }, namespaced);
            case Block block -> {
                PersistentDataContainer chunk = block.getChunk().getPersistentDataContainer();
                yield new Slot(chunk, edit -> edit.accept(chunk), blockKey(block, namespaced));
            }
            case PersistentDataHolder data -> {
                PersistentDataContainer container = data.getPersistentDataContainer();
                yield new Slot(container, edit -> edit.accept(container), namespaced);
            }
            default -> throw new IllegalArgumentException("Cannot tag " + holder);
        };
    }

    private static String blockPrefix(Block block) {
        return "block/" + block.getX() + "/" + block.getY() + "/" + block.getZ() + "/";
    }

    private static NamespacedKey blockKey(Block block, NamespacedKey key) {
        return new NamespacedKey(NAMESPACE, blockPrefix(block) + key.getNamespace() + "/" + key.getKey());
    }

    // ------------------------------------------------------------------ reading

    public static String getString(Object holder, String key) {
        Slot slot = slot(holder, key);
        PersistentDataContainerView view = slot.view();
        if (view.has(slot.key(), PersistentDataType.STRING)) {
            return view.get(slot.key(), PersistentDataType.STRING);
        }
        if (view.has(slot.key(), PersistentDataType.INTEGER)) {
            return String.valueOf(view.get(slot.key(), PersistentDataType.INTEGER));
        }
        if (view.has(slot.key(), PersistentDataType.DOUBLE)) {
            return String.valueOf(view.get(slot.key(), PersistentDataType.DOUBLE));
        }
        return null;
    }

    public static Integer getInt(Object holder, String key) {
        Slot slot = slot(holder, key);
        PersistentDataContainerView view = slot.view();
        if (view.has(slot.key(), PersistentDataType.INTEGER)) {
            return view.get(slot.key(), PersistentDataType.INTEGER);
        }
        if (view.has(slot.key(), PersistentDataType.LONG)) {
            return view.get(slot.key(), PersistentDataType.LONG).intValue();
        }
        return null;
    }

    public static Double getDouble(Object holder, String key) {
        Slot slot = slot(holder, key);
        PersistentDataContainerView view = slot.view();
        if (view.has(slot.key(), PersistentDataType.DOUBLE)) {
            return view.get(slot.key(), PersistentDataType.DOUBLE);
        }
        if (view.has(slot.key(), PersistentDataType.INTEGER)) {
            return view.get(slot.key(), PersistentDataType.INTEGER).doubleValue();
        }
        if (view.has(slot.key(), PersistentDataType.LONG)) {
            return view.get(slot.key(), PersistentDataType.LONG).doubleValue();
        }
        return null;
    }

    public static boolean has(Object holder, String key) {
        Slot slot = slot(holder, key);
        return slot.view().has(slot.key());
    }

    public static List<Object> keys(Object holder) {
        PersistentDataContainerView view = switch (holder) {
            case ItemStack item -> item.getPersistentDataContainer();
            case PersistentDataHolder data -> data.getPersistentDataContainer();
            default -> throw new IllegalArgumentException("Cannot tag " + holder);
        };
        List<Object> keys = new ArrayList<>();
        for (NamespacedKey key : view.getKeys()) {
            keys.add(NAMESPACE.equals(key.getNamespace()) ? key.getKey() : key.asString());
        }
        return keys;
    }

    // ------------------------------------------------------------------ writing

    public static void set(Object holder, String key, String value) {
        Slot slot = slot(holder, key);
        slot.editor().accept(container -> container.set(slot.key(), PersistentDataType.STRING, value));
    }

    public static void set(Object holder, String key, int value) {
        Slot slot = slot(holder, key);
        slot.editor().accept(container -> container.set(slot.key(), PersistentDataType.INTEGER, value));
    }

    public static void set(Object holder, String key, double value) {
        Slot slot = slot(holder, key);
        slot.editor().accept(container -> container.set(slot.key(), PersistentDataType.DOUBLE, value));
    }

    public static void remove(Object holder, String key) {
        Slot slot = slot(holder, key);
        slot.editor().accept(container -> container.remove(slot.key()));
    }

    /** Removes every tag of a block. */
    public static void clear(Block block) {
        PersistentDataContainer chunk = block.getChunk().getPersistentDataContainer();
        String prefix = blockPrefix(block);
        for (NamespacedKey key : List.copyOf(chunk.getKeys())) {
            if (NAMESPACE.equals(key.getNamespace()) && key.getKey().startsWith(prefix)) {
                chunk.remove(key);
            }
        }
    }
}
