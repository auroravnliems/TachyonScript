package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.natives.ScriptFunction;
import dev.tachyonscript.platform.paper.PaperContext;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * A menu (GUI) created by a script: an inventory whose slots can run script functions when
 * clicked. {@link MenuListener} routes the clicks. A menu belongs to the script that created it
 * and is closed for every viewer when that script reloads.
 */
public final class ScriptMenu implements InventoryHolder {

    private final PaperContext context;
    private final Inventory inventory;
    private final Component title;
    private final ScriptFunction[] handlers;
    private volatile ScriptFunction openHandler;
    private volatile ScriptFunction closeHandler;
    private volatile boolean allowTaking;

    private ScriptMenu(PaperContext context, Component title, int rows, InventoryType type) {
        this.context = context;
        this.title = title;
        this.inventory = type == null ? Bukkit.createInventory(this, rows * 9, title) : Bukkit.createInventory(this, type, title);
        this.handlers = new ScriptFunction[inventory.getSize()];
    }

    public static ScriptMenu create(PaperContext context, int rows, Component title) {
        if (rows < 1 || rows > 6) {
            throw new ScriptError("A menu has 1 to 6 rows, not " + rows + ".");
        }
        return context.own(new ScriptMenu(context, title, rows, null), menu -> ((ScriptMenu) menu).closeAll());
    }

    public static ScriptMenu create(PaperContext context, InventoryType type, Component title) {
        if (!type.isCreatable()) {
            throw new ScriptError("A menu cannot be a " + Texts.enumText(type) + " inventory.");
        }
        return context.own(new ScriptMenu(context, title, 0, type), menu -> ((ScriptMenu) menu).closeAll());
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Inventory inventory() {
        return inventory;
    }

    public Component title() {
        return title;
    }

    public boolean allowTaking() {
        return allowTaking;
    }

    public void allowTaking(boolean allow) {
        this.allowTaking = allow;
    }

    PaperContext context() {
        return context;
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= handlers.length) {
            throw new ScriptError("Slot " + slot + " does not exist (the menu has slots 0 to " + (handlers.length - 1) + ").");
        }
    }

    public void set(int slot, ItemStack item, ScriptFunction handler) {
        checkSlot(slot);
        inventory.setItem(slot, item);
        handlers[slot] = handler;
    }

    public void handler(int slot, ScriptFunction handler) {
        checkSlot(slot);
        handlers[slot] = handler;
    }

    ScriptFunction handler(int slot) {
        return slot >= 0 && slot < handlers.length ? handlers[slot] : null;
    }

    public void fill(ItemStack item) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (Items.orNull(inventory.getItem(slot)) == null) {
                inventory.setItem(slot, item);
            }
        }
    }

    public void fillBorder(ItemStack item) {
        int size = inventory.getSize();
        if (inventory.getType() != InventoryType.CHEST || size < 9) {
            fill(item);
            return;
        }
        int rows = size / 9;
        for (int slot = 0; slot < size; slot++) {
            int row = slot / 9;
            int column = slot % 9;
            boolean border = row == 0 || row == rows - 1 || column == 0 || column == 8;
            if (border && Items.orNull(inventory.getItem(slot)) == null) {
                inventory.setItem(slot, item);
            }
        }
    }

    public void clear() {
        inventory.clear();
        java.util.Arrays.fill(handlers, null);
    }

    public void onOpen(ScriptFunction handler) {
        this.openHandler = handler;
    }

    public void onClose(ScriptFunction handler) {
        this.closeHandler = handler;
    }

    ScriptFunction openHandler() {
        return openHandler;
    }

    ScriptFunction closeHandler() {
        return closeHandler;
    }

    /** Closes the menu for every viewer (each on their own thread). */
    public void closeAll() {
        for (HumanEntity viewer : List.copyOf(inventory.getViewers())) {
            if (viewer instanceof Player player) {
                context.forEntity(player, () -> {
                    if (player.getOpenInventory().getTopInventory().getHolder(false) == this) {
                        player.closeInventory();
                    }
                });
            }
        }
    }
}
