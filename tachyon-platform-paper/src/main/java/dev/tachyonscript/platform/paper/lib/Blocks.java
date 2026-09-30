package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.value.Values;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/** Block helpers of the standard library bindings. */
@SuppressWarnings({"deprecation", "removal"})
public final class Blocks {

    /** Most blocks {@code World.fill} changes in one call. */
    private static final int FILL_LIMIT = 32_768;
    /** Largest radius of {@code Location.blocksInRadius}. */
    private static final int RADIUS_LIMIT = 16;

    private Blocks() {
    }

    public static void setData(Block block, String data) {
        try {
            block.setBlockData(Bukkit.createBlockData(data));
        } catch (IllegalArgumentException e) {
            throw new ScriptError("Invalid block data '" + data + "'.");
        }
    }

    public static Inventory inventory(Block block) {
        BlockState state = block.getState(false);
        return state instanceof Container container ? container.getInventory() : null;
    }

    public static Component signLine(Block block, int index) {
        if (!(block.getState(false) instanceof Sign sign)) {
            return null;
        }
        checkLine(index);
        return sign.getSide(Side.FRONT).line(index);
    }

    public static void setSignLine(Block block, int index, Component text) {
        if (!(block.getState() instanceof Sign sign)) {
            throw new ScriptError("The block at " + describe(block) + " is not a sign.");
        }
        checkLine(index);
        sign.getSide(Side.FRONT).line(index, text);
        sign.update();
    }

    private static void checkLine(int index) {
        if (index < 0 || index > 3) {
            throw new ScriptError("Signs have lines 0 to 3, not " + index + ".");
        }
    }

    public static String describe(Block block) {
        return Texts.keyText(block.getType().getKey()) + " at " + block.getWorld().getName() + " " + block.getX() + ", "
                + block.getY() + ", " + block.getZ();
    }

    /** Sets every block of the box between two corners; returns how many blocks changed. */
    public static int fill(World world, Location from, Location to, Material material) {
        if (!material.isBlock()) {
            throw new ScriptError(Texts.keyText(material.getKey()) + " is not a block.");
        }
        int minX = Math.min(from.getBlockX(), to.getBlockX());
        int minY = Math.max(world.getMinHeight(), Math.min(from.getBlockY(), to.getBlockY()));
        int minZ = Math.min(from.getBlockZ(), to.getBlockZ());
        int maxX = Math.max(from.getBlockX(), to.getBlockX());
        int maxY = Math.min(world.getMaxHeight() - 1, Math.max(from.getBlockY(), to.getBlockY()));
        int maxZ = Math.max(from.getBlockZ(), to.getBlockZ());
        long volume = (long) (maxX - minX + 1) * Math.max(0, maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > FILL_LIMIT) {
            throw new ScriptError("fill changes at most " + Values.toString(FILL_LIMIT) + " blocks at once, not "
                    + volume + ". Split the area.");
        }
        int changed = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != material) {
                        block.setType(material, false);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /** Blocks within a distance of a location (a sphere). */
    public static List<Object> inRadius(Location center, int radius) {
        if (radius < 0 || radius > RADIUS_LIMIT) {
            throw new ScriptError("blocksInRadius takes a radius from 0 to " + RADIUS_LIMIT + ", not " + radius + ".");
        }
        World world = Worlds.world(center);
        List<Object> blocks = new ArrayList<>();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        int squared = radius * radius;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                int by = cy + y;
                if (by < world.getMinHeight() || by >= world.getMaxHeight()) {
                    continue;
                }
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z <= squared) {
                        blocks.add(world.getBlockAt(cx + x, by, cz + z));
                    }
                }
            }
        }
        return blocks;
    }
}
