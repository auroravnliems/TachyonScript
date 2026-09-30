package dev.tachyonscript.platform.paper.lib;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/** A click in a {@link ScriptMenu}, given to the slot's click handler. */
public final class MenuClick {

    private final ScriptMenu menu;
    private final Player player;
    private final InventoryClickEvent event;
    private volatile boolean cancelled;

    MenuClick(ScriptMenu menu, Player player, InventoryClickEvent event, boolean cancelled) {
        this.menu = menu;
        this.player = player;
        this.event = event;
        this.cancelled = cancelled;
    }

    public ScriptMenu menu() {
        return menu;
    }

    public Player player() {
        return player;
    }

    public int slot() {
        return event.getRawSlot();
    }

    public ClickType click() {
        return event.getClick();
    }

    public ItemStack item() {
        return Items.orNull(event.getCurrentItem());
    }

    public ItemStack cursor() {
        return Items.orNull(event.getCursor());
    }

    public int hotbarButton() {
        return event.getHotbarButton();
    }

    public boolean cancelled() {
        return cancelled;
    }

    public void cancelled(boolean value) {
        this.cancelled = value;
    }

    /** Closes the menu for the player right after the click (closing during the click is not allowed). */
    public void close() {
        player.getScheduler().run(menu.context().plugin(), task -> {
            if (player.getOpenInventory().getTopInventory().getHolder(false) == menu) {
                player.closeInventory();
            }
        }, null);
    }
}
