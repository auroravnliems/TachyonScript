package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptFunction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;

/**
 * Routes inventory events of {@link ScriptMenu}s to their script handlers. Unless a menu
 * allows taking items, every click that could move items into or out of it is cancelled
 * before the handler runs (the handler may change that through {@code click.cancelled}).
 */
public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof ScriptMenu menu)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int size = menu.inventory().getSize();
        boolean inMenu = event.getRawSlot() >= 0 && event.getRawSlot() < size;
        if (!inMenu) {
            // Clicks in the player's own inventory are harmless unless they move items into the menu.
            if (!menu.allowTaking() && (event.isShiftClick() || event.getAction() == InventoryAction.COLLECT_TO_CURSOR)) {
                event.setCancelled(true);
            }
            return;
        }
        MenuClick click = new MenuClick(menu, player, event, !menu.allowTaking());
        ScriptFunction handler = menu.handler(event.getRawSlot());
        if (handler != null) {
            Screens.during(player, () -> menu.context().callback(handler, click));
        }
        event.setCancelled(click.cancelled());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof ScriptMenu menu) || menu.allowTaking()) {
            return;
        }
        int size = menu.inventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < size) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent event) {
        if (!event.isCancelled() && event.getInventory().getHolder(false) instanceof ScriptMenu menu
                && event.getPlayer() instanceof Player player && menu.openHandler() != null) {
            Screens.during(player, () -> menu.context().callback(menu.openHandler(), player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof ScriptMenu menu
                && event.getPlayer() instanceof Player player && menu.closeHandler() != null) {
            Screens.during(player, () -> menu.context().callback(menu.closeHandler(), player));
        }
    }
}
