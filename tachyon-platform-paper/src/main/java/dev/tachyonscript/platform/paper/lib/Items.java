package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptError;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Item and inventory helpers of the standard library bindings.
 *
 * <p>Names and lore lines are shown without the italic style Minecraft gives custom names by
 * default (write {@code <italic>} to get it back), which is what server owners expect.
 */
@SuppressWarnings({"deprecation", "removal"})
public final class Items {

    private Items() {
    }

    // ------------------------------------------------------------------ creating

    public static ItemStack of(Material material, int amount) {
        if (!material.isItem() || material.isAir()) {
            throw new ScriptError(Texts.keyText(material.getKey()) + " is not an item.");
        }
        if (amount < 1 || amount > 99) {
            throw new ScriptError("An item stack holds 1 to 99 items, not " + amount + ".");
        }
        return new ItemStack(material, amount);
    }

    public static ItemStack named(ItemStack item, Component name, List<Object> lore) {
        item.editMeta(meta -> {
            meta.displayName(plain(name));
            if (lore != null) {
                meta.lore(components(lore));
            }
        });
        return item;
    }

    /** Null for an empty slot (air or no items), the stack otherwise. */
    public static ItemStack orNull(ItemStack item) {
        return item == null || item.isEmpty() ? null : item;
    }

    public static Material material(String name) {
        Material material = Material.matchMaterial(name.strip());
        return material != null && !material.isLegacy() ? material : null;
    }

    public static List<Object> materials() {
        List<Object> materials = new ArrayList<>();
        Registry.MATERIAL.forEach(materials::add);
        return materials;
    }

    public static String describe(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        String name = meta != null && meta.hasDisplayName()
                ? PlainTextComponentSerializer.plainText().serialize(meta.displayName())
                : Texts.keyText(item.getType().getKey());
        return item.getAmount() + "x " + name;
    }

    // ------------------------------------------------------------------ meta

    private static void edit(ItemStack item, Consumer<ItemMeta> action) {
        if (!item.editMeta(action::accept)) {
            throw new ScriptError(Texts.keyText(item.getType().getKey()) + " has no item data (it is air).");
        }
    }

    private static Component plain(Component text) {
        return text == null ? null : text.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static List<Component> components(List<Object> lines) {
        List<Component> result = new ArrayList<>(lines.size());
        for (Object line : lines) {
            result.add(line == null ? Component.empty() : plain((Component) line));
        }
        return result;
    }

    public static Component name(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() ? meta.displayName() : null;
    }

    public static void setName(ItemStack item, Component name) {
        edit(item, meta -> meta.displayName(plain(name)));
    }

    public static List<Object> lore(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta == null ? null : meta.lore();
        return lore == null ? new ArrayList<>() : new ArrayList<>(lore);
    }

    public static void setLore(ItemStack item, List<Object> lore) {
        edit(item, meta -> meta.lore(lore == null || lore.isEmpty() ? null : components(lore)));
    }

    public static void addLore(ItemStack item, Component line) {
        edit(item, meta -> {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(plain(line));
            meta.lore(lore);
        });
    }

    public static Map<Object, Object> enchantments(ItemStack item) {
        Map<Object, Object> result = new LinkedHashMap<>();
        if (item.getItemMeta() instanceof EnchantmentStorageMeta book) {
            book.getStoredEnchants().forEach(result::put);
        }
        item.getEnchantments().forEach(result::put);
        return result;
    }

    public static void enchant(ItemStack item, Enchantment enchantment, int level) {
        if (level <= 0) {
            removeEnchant(item, enchantment);
            return;
        }
        if (level > 255) {
            throw new ScriptError("Enchantment levels go up to 255, not " + level + ".");
        }
        if (item.getItemMeta() instanceof EnchantmentStorageMeta) {
            edit(item, meta -> ((EnchantmentStorageMeta) meta).addStoredEnchant(enchantment, level, true));
        } else {
            edit(item, meta -> meta.addEnchant(enchantment, level, true));
        }
    }

    public static void removeEnchant(ItemStack item, Enchantment enchantment) {
        edit(item, meta -> {
            meta.removeEnchant(enchantment);
            if (meta instanceof EnchantmentStorageMeta book) {
                book.removeStoredEnchant(enchantment);
            }
        });
    }

    public static boolean unbreakable(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.isUnbreakable();
    }

    public static void setUnbreakable(ItemStack item, boolean unbreakable) {
        edit(item, meta -> meta.setUnbreakable(unbreakable));
    }

    public static int damage(ItemStack item) {
        return item.getItemMeta() instanceof Damageable damageable ? damageable.getDamage() : 0;
    }

    public static void setDamage(ItemStack item, int damage) {
        edit(item, meta -> {
            if (!(meta instanceof Damageable damageable)) {
                throw new ScriptError(Texts.keyText(item.getType().getKey()) + " has no durability.");
            }
            damageable.setDamage(Math.max(0, damage));
        });
    }

    public static boolean glowing(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && (meta.hasEnchantmentGlintOverride() ? meta.getEnchantmentGlintOverride() : meta.hasEnchants());
    }

    public static void setGlowing(ItemStack item, boolean glowing) {
        edit(item, meta -> meta.setEnchantmentGlintOverride(glowing));
    }

    public static int customModelData(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasCustomModelData() ? meta.getCustomModelData() : 0;
    }

    public static void setCustomModelData(ItemStack item, int value) {
        edit(item, meta -> meta.setCustomModelData(value == 0 ? null : value));
    }

    public static String itemModel(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasItemModel() ? meta.getItemModel().asString() : null;
    }

    public static void setItemModel(ItemStack item, String model) {
        NamespacedKey key = model == null ? null : NamespacedKey.fromString(model);
        if (model != null && key == null) {
            throw new ScriptError("Invalid item model '" + model + "' (expected namespace:path).");
        }
        edit(item, meta -> meta.setItemModel(key));
    }

    public static boolean hideTooltip(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.isHideTooltip();
    }

    public static void setHideTooltip(ItemStack item, boolean hide) {
        edit(item, meta -> meta.setHideTooltip(hide));
    }

    public static Color color(ItemStack item) {
        return switch (item.getItemMeta()) {
            case LeatherArmorMeta leather -> leather.getColor();
            case PotionMeta potion -> potion.getColor();
            case null, default -> null;
        };
    }

    public static void setColor(ItemStack item, Color color) {
        edit(item, meta -> {
            switch (meta) {
                case LeatherArmorMeta leather -> leather.setColor(color);
                case PotionMeta potion -> potion.setColor(color);
                default -> throw new ScriptError(Texts.keyText(item.getType().getKey()) + " cannot be dyed.");
            }
        });
    }

    public static OfflinePlayer skullOwner(ItemStack item) {
        return item.getItemMeta() instanceof SkullMeta skull ? skull.getOwningPlayer() : null;
    }

    public static void setSkullOwner(ItemStack item, OfflinePlayer owner) {
        edit(item, meta -> {
            if (!(meta instanceof SkullMeta skull)) {
                throw new ScriptError("Only player heads have an owner.");
            }
            skull.setOwningPlayer(owner);
        });
    }

    public static void addFlag(ItemStack item, ItemFlag flag) {
        edit(item, meta -> meta.addItemFlags(flag));
    }

    public static void removeFlag(ItemStack item, ItemFlag flag) {
        edit(item, meta -> meta.removeItemFlags(flag));
    }

    public static boolean hasFlag(ItemStack item, ItemFlag flag) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasItemFlag(flag);
    }

    // ------------------------------------------------------------------ inventories

    public static Inventory inventory(int rows, Component title) {
        if (rows < 1 || rows > 6) {
            throw new ScriptError("A chest inventory has 1 to 6 rows, not " + rows + ".");
        }
        return org.bukkit.Bukkit.createInventory(null, rows * 9, title);
    }

    public static List<Object> contents(Inventory inventory) {
        ItemStack[] items = inventory.getContents();
        List<Object> result = new ArrayList<>(items.length);
        for (ItemStack item : items) {
            result.add(orNull(item));
        }
        return result;
    }

    public static void setContents(Inventory inventory, List<Object> items) {
        ItemStack[] contents = new ItemStack[inventory.getSize()];
        for (int i = 0; i < contents.length && i < items.size(); i++) {
            contents[i] = (ItemStack) items.get(i);
        }
        inventory.setContents(contents);
    }

    public static List<Object> viewers(Inventory inventory) {
        List<Object> players = new ArrayList<>();
        for (HumanEntity viewer : inventory.getViewers()) {
            if (viewer instanceof Player player) {
                players.add(player);
            }
        }
        return players;
    }

    private static void checkSlot(Inventory inventory, int slot) {
        if (slot < 0 || slot >= inventory.getSize()) {
            throw new ScriptError("Slot " + slot + " does not exist (the inventory has slots 0 to "
                    + (inventory.getSize() - 1) + ").");
        }
    }

    public static ItemStack get(Inventory inventory, int slot) {
        checkSlot(inventory, slot);
        return orNull(inventory.getItem(slot));
    }

    public static void set(Inventory inventory, int slot, ItemStack item) {
        checkSlot(inventory, slot);
        inventory.setItem(slot, item);
    }

    /** Adds items where they fit; returns how many did not fit. */
    public static int add(Inventory inventory, ItemStack item) {
        int left = 0;
        for (ItemStack rest : inventory.addItem(item.clone()).values()) {
            left += rest.getAmount();
        }
        return left;
    }

    /** Removes up to {@code amount} items of a material; returns how many were removed. */
    public static int remove(Inventory inventory, Material material, int amount) {
        int left = Math.max(0, amount);
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack item = contents[slot];
            if (item != null && item.getType() == material) {
                int taken = Math.min(left, item.getAmount());
                left -= taken;
                if (taken == item.getAmount()) {
                    inventory.clear(slot);
                } else {
                    item.setAmount(item.getAmount() - taken);
                    inventory.setItem(slot, item);
                }
            }
        }
        return Math.max(0, amount) - left;
    }

    /** Removes items similar to a stack, up to its amount; returns how many were removed. */
    public static int removeSimilar(Inventory inventory, ItemStack wanted) {
        int left = wanted.getAmount();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack item = contents[slot];
            if (item != null && item.isSimilar(wanted)) {
                int taken = Math.min(left, item.getAmount());
                left -= taken;
                if (taken == item.getAmount()) {
                    inventory.clear(slot);
                } else {
                    item.setAmount(item.getAmount() - taken);
                    inventory.setItem(slot, item);
                }
            }
        }
        return wanted.getAmount() - left;
    }

    public static int count(Inventory inventory, Material material) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /** Whether all of the items would fit. */
    public static boolean fits(Inventory inventory, ItemStack item) {
        int space = 0;
        int max = Math.min(item.getMaxStackSize(), inventory.getMaxStackSize());
        for (ItemStack slot : inventory.getStorageContents()) {
            if (slot == null || slot.isEmpty()) {
                space += max;
            } else if (slot.isSimilar(item)) {
                space += Math.max(0, max - slot.getAmount());
            }
            if (space >= item.getAmount()) {
                return true;
            }
        }
        return space >= item.getAmount();
    }

    /** Gives items to a player; what does not fit drops at their feet. */
    public static void give(Player player, ItemStack item) {
        Location location = player.getLocation();
        for (ItemStack rest : player.getInventory().addItem(item.clone()).values()) {
            player.getWorld().dropItemNaturally(location, rest);
        }
    }
}
