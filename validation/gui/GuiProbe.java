package dev.tachyonscript.validation;

import dev.tachyonscript.platform.paper.lib.ScriptMenu;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Local test server only. No runtime dependency on this probe. */
public final class GuiProbe extends JavaPlugin implements Listener {
    private static final class ExternalHolder implements InventoryHolder {
        private final Inventory inventory = Bukkit.createInventory(this, 9, Component.text("GUI probe"));
        @Override public Inventory getInventory() { return inventory; }
    }

    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }

    private static int diamonds(ItemStack item) {
        return item != null && item.getType() == Material.DIAMOND ? item.getAmount() : 0;
    }

    private static String item(ItemStack item) {
        return item == null || item.isEmpty() ? "AIR:0" : item.getType() + ":" + item.getAmount();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp() || args.length == 0) return true;
        if (args[0].equals("prepare")) {
            player.setItemOnCursor(null);
            player.closeInventory();
            player.getInventory().clear();
            player.setGameMode(GameMode.SURVIVAL);
            player.getNearbyEntities(20, 20, 20).stream().filter(Item.class::isInstance).forEach(org.bukkit.entity.Entity::remove);
            player.getInventory().setItem(2, new ItemStack(Material.STONE, 4));
            player.getInventory().setItemInOffHand(new ItemStack(Material.STICK, 1));
            if (args[1].equals("external")) {
                Inventory inventory = new ExternalHolder().getInventory();
                inventory.setItem(0, new ItemStack(Material.DIAMOND, 8));
                player.openInventory(inventory);
            } else player.performCommand(args[1] + "gui");
            player.updateInventory();
            player.sendMessage("GUI_READY " + args[1]);
        } else if (args[0].equals("seed")) {
            // A script command may open its screen asynchronously. The client waits for
            // the new window before this separate command seeds and snapshots the cursor.
            if (args[1].equals("cursor")) player.setItemOnCursor(new ItemStack(Material.DIAMOND, 8));
            if (args[1].equals("bottom")) player.getInventory().setItem(9, new ItemStack(Material.DIAMOND, 8));
            player.updateInventory();
            player.sendMessage("GUI_SEEDED " + args[1]);
        } else if (args[0].equals("inspect")) {
            Inventory top = player.getOpenInventory().getTopInventory();
            int stored = 0;
            for (ItemStack stack : player.getInventory().getContents()) stored += diamonds(stack);
            int dropped = player.getNearbyEntities(20, 20, 20).stream().filter(Item.class::isInstance)
                    .map(Item.class::cast).mapToInt(value -> diamonds(value.getItemStack())).sum();
            String holder = top.getHolder(false) instanceof ScriptMenu ? "menu"
                    : top.getHolder(false) instanceof ExternalHolder ? "external" : "ordinary";
            player.sendMessage("GUI_STATE " + holder + " " + item(top.getItem(0)) + " "
                    + item(top.getItem(1)) + " " + item(player.getItemOnCursor()) + " " + stored + " " + dropped
                    + " " + item(player.getInventory().getItem(2)) + " " + item(player.getInventory().getItemInOffHand()));
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void external(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ExternalHolder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void externalDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ExternalHolder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void click(InventoryClickEvent event) {
        event.getWhoClicked().sendMessage("GUI_ACTION " + event.getClick() + " " + event.getAction());
        event.getWhoClicked().sendMessage("GUI_EVENT " + event.getClick() + " " + event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void drag(InventoryDragEvent event) {
        event.getWhoClicked().sendMessage("GUI_DRAG " + event.getType() + " " + event.isCancelled());
    }
}
