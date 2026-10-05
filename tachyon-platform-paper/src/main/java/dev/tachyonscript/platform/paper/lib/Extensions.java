package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptError;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Typed Paper operations used by the extended library. No reflective dispatch. */
public final class Extensions {
    private Extensions() { }

    public static BlockData blockData(String text) {
        try { return Bukkit.createBlockData(text); }
        catch (IllegalArgumentException error) { throw new ScriptError("Invalid block data: " + text); }
    }

    public static BlockData merge(BlockData original, BlockData changes) {
        try { return original.merge(changes); }
        catch (IllegalArgumentException error) {
            throw new ScriptError("BlockData.merge needs parsed data of the same material with explicitly set properties.");
        }
    }

    private static Sign sign(Block block) {
        if (!(block.getState() instanceof Sign sign)) throw new ScriptError("This block is not a sign.");
        return sign;
    }

    public static Component signLine(Block block, Side side, int index) {
        if (index < 0 || index > 3) throw new ScriptError("Sign line index must be from 0 to 3.");
        return sign(block).getSide(side).line(index);
    }

    public static void signLine(Block block, Side side, int index, Component text) {
        if (index < 0 || index > 3) throw new ScriptError("Sign line index must be from 0 to 3.");
        Sign sign = sign(block);
        sign.getSide(side).line(index, text);
        sign.update();
    }

    public static boolean signGlowing(Block block, Side side) { return sign(block).getSide(side).isGlowingText(); }
    public static void signGlowing(Block block, Side side, boolean value) {
        Sign sign = sign(block); sign.getSide(side).setGlowingText(value); sign.update();
    }
    public static DyeColor signColor(Block block, Side side) { return sign(block).getSide(side).getColor(); }
    public static void signColor(Block block, Side side, DyeColor value) {
        Sign sign = sign(block); sign.getSide(side).setColor(value); sign.update();
    }
    public static boolean signWaxed(Block block) { return sign(block).isWaxed(); }
    public static void signWaxed(Block block, boolean value) {
        Sign sign = sign(block); sign.setWaxed(value); sign.update();
    }

    public static Vector vector(Vector3f value) { return new Vector(value.x(), value.y(), value.z()); }
    public static Vector3f vector(Vector value) {
        value.checkFinite();
        Vector3f result = new Vector3f((float) value.getX(), (float) value.getY(), (float) value.getZ());
        if (!result.isFinite()) throw new ScriptError("Display vectors must fit finite float coordinates.");
        return result;
    }
    public static void translation(Display display, Vector value) {
        Transformation current = display.getTransformation();
        display.setTransformation(new Transformation(vector(value), current.getLeftRotation(), current.getScale(), current.getRightRotation()));
    }
    public static void scale(Display display, Vector value) {
        Transformation current = display.getTransformation();
        display.setTransformation(new Transformation(current.getTranslation(), current.getLeftRotation(), vector(value), current.getRightRotation()));
    }
    public static void rotation(Display display, Quaternionf value, boolean left) {
        Transformation current = display.getTransformation();
        display.setTransformation(new Transformation(current.getTranslation(), left ? value : current.getLeftRotation(),
                current.getScale(), left ? current.getRightRotation() : value));
    }
    public static Quaternionf quaternion(double x, double y, double z, double w) {
        double largest = Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.max(Math.abs(z), Math.abs(w)));
        if (!Double.isFinite(largest) || largest == 0) throw new ScriptError("A quaternion must be finite and have a nonzero length.");
        x /= largest; y /= largest; z /= largest; w /= largest;
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        return new Quaternionf((float) (x / length), (float) (y / length), (float) (z / length), (float) (w / length));
    }
    public static Quaternionf axisAngle(Vector axis, double radians) {
        axis.checkFinite();
        double largest = Math.max(Math.max(Math.abs(axis.getX()), Math.abs(axis.getY())), Math.abs(axis.getZ()));
        if (largest == 0 || !Double.isFinite(radians))
            throw new ScriptError("A rotation needs a nonzero axis and finite angle in radians.");
        Vector unit = new Vector(axis.getX() / largest, axis.getY() / largest, axis.getZ() / largest).normalize();
        return new Quaternionf().fromAxisAngleRad(vector(unit), (float) Math.IEEEremainder(radians, 2 * Math.PI));
    }
    public static Display.Brightness brightness(int block, int sky) {
        if (block < 0 || block > 15 || sky < 0 || sky > 15) throw new ScriptError("Display light levels must be from 0 to 15.");
        return new Display.Brightness(block, sky);
    }
}
