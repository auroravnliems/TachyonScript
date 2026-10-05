package dev.tachyonscript.platform.paper;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.compiler.InternalErrorHandler;
import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.spi.*;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.platform.paper.lib.Extensions;
import dev.tachyonscript.platform.paper.lib.TransientMetadata;
import dev.tachyonscript.runtime.interpreter.Interpreter;
import dev.tachyonscript.stdlib.StandardLibrary;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.DyeColor;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import net.kyori.adventure.text.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** The real compiler/runtime and generated bindings, with only Bukkit hosts faked. */
class ExtensionsTest {
    private ScriptEngine engine;
    private PaperContext context;

    private void start(List<SourceFile> sources) {
        var registry = StandardLibrary.registry();
        Plugin plugin = BukkitFakes.fake(Plugin.class, Map.of("getName", a -> "ExtensionsTest",
                "getDataFolder", a -> new File("build/tmp/extensions-test")));
        var threads = new BukkitFakes.InlineThreading(false);
        context = new PaperContext(plugin, threads);
        var bindings = PaperPlatform.builtInBindings(context, threads, Logger.getLogger("ExtensionsTest"), () -> false);
        var events = BukkitFakes.fake(EventBridge.class, Map.of("activeEventsChanged", a -> null, "isCancelled", a -> false));
        var logger = BukkitFakes.fake(EngineLogger.class, Map.of("info", a -> null, "warn", a -> null,
                "error", a -> { fail("Unexpected engine error: " + a[0]); return null; }));
        var players = BukkitFakes.fake(PlayerDirectory.class, Map.of("id", a -> ((Player) a[0]).getUniqueId(),
                "name", a -> "Tester"));
        Platform platform = BukkitFakes.fake(Platform.class, Map.of(
                "bindings", a -> bindings, "text", a -> new AdventureTextService(), "events", a -> events,
                "logger", a -> logger, "scheduler", a -> InlineScheduler.INSTANCE,
                "commands", a -> InlineScheduler.NO_COMMANDS, "arguments", a -> new PaperArgumentTypes(registry, bindings),
                "players", a -> players));
        engine = new ScriptEngine(registry, platform, EngineOptions.DEFAULT, InternalErrorHandler.IGNORE);
        context.attach(engine);
        var report = engine.load(() -> sources);
        assertTrue(report.activated() && report.failed().isEmpty(), () -> report.toString());
    }

    private Object call(String path, String signature, Object... args) {
        return Interpreter.call(engine.generation().scripts().get(path).linked().function(signature).orElseThrow(), args);
    }

    @AfterEach void stop() { if (engine != null) engine.shutdown(); }

    @Test void metadataBelongsToLatestWriterAndRetiresOnReloadDisableAndShutdown() throws Exception {
        String code = "function write(p: Player, value: string) { p.setMetadata(\"test.shared\", value) }";
        var sources = List.of(new SourceFile("a.tys", code), new SourceFile("b.tys", code));
        start(sources);
        UUID id = UUID.randomUUID();
        Player player = BukkitFakes.fake(Player.class, Map.of("getUniqueId", a -> id));
        call("a.tys", "write(Player, string)", player, "first");
        call("b.tys", "write(Player, string)", player, "second");
        assertEquals(1, context.metadata().size());
        assertTrue(engine.reload(() -> sources, Set.of("a.tys")).activated());
        assertEquals("second", context.metadata().get(player, "test.shared"));
        engine.disable(Set.of("b.tys"), false);
        assertNull(context.metadata().get(player, "test.shared"));
        call("a.tys", "write(Player, string)", player, "third");
        engine.shutdown();
        assertEquals(0, context.metadata().size());
    }

    @Test void metadataCleanupUsesEntityIdentityBlockCoordinatesAndWorldIdentity() {
        TransientMetadata metadata = new TransientMetadata();
        UUID worldId = UUID.randomUUID(), otherId = UUID.randomUUID(), playerId = UUID.randomUUID();
        World world = BukkitFakes.fake(World.class, Map.of("getUID", a -> worldId));
        World other = BukkitFakes.fake(World.class, Map.of("getUID", a -> otherId));
        Player player = BukkitFakes.fake(Player.class, Map.of("getUniqueId", a -> playerId));
        Block first = block(world, -1, 70, -17), same = block(world, -1, 70, -17);
        Block adjacent = block(world, 0, 70, -17), foreign = block(other, -1, 70, -17);
        metadata.set(first, "a", 1); metadata.set(first, "b", 2);
        metadata.set(adjacent, "a", 3); metadata.set(foreign, "a", 4);
        metadata.set(world, "a", 5); metadata.set(player, "a", 6);
        assertEquals(1, metadata.get(same, "a"));
        metadata.set(same, "b", null);
        assertEquals(5, metadata.size());
        Chunk chunk = BukkitFakes.fake(Chunk.class, Map.of("getWorld", a -> world, "getX", a -> -1, "getZ", a -> -2));
        metadata.chunkUnloaded(new ChunkUnloadEvent(chunk));
        assertNull(metadata.get(first, "a"));
        assertEquals(3, metadata.get(adjacent, "a"));
        metadata.entityRemoved(new EntityRemoveFromWorldEvent(player, world));
        assertNull(metadata.get(player, "a"));
        metadata.worldUnloaded(new WorldUnloadEvent(world));
        assertNull(metadata.get(adjacent, "a")); assertNull(metadata.get(world, "a"));
        assertEquals(4, metadata.get(foreign, "a"));
        metadata.clear(); assertEquals(0, metadata.size());
        metadata.set(first, "a", 7); metadata.set(first, "a", null);
        assertEquals(0, metadata.size());
        assertThrows(ScriptError.class, () -> metadata.set(world, " ", 1));
        assertThrows(ScriptError.class, () -> metadata.get(world, "x".repeat(129)));
        assertThrows(ScriptError.class, () -> metadata.set(new Object(), "a", 1));
    }

    private static Block block(World world, int x, int y, int z) {
        return BukkitFakes.fake(Block.class, Map.of("getWorld", a -> world, "getX", a -> x, "getY", a -> y, "getZ", a -> z));
    }

    @Test void rotationsNormalizeExtremeFiniteInputsAndRejectInvalidValues() {
        for (double size : new double[] {Double.MIN_VALUE, 1, Double.MAX_VALUE}) {
            Quaternionf rotation = Extensions.quaternion(size, size, size, size);
            assertEquals(0.5f, rotation.x, 0.000001f);
            assertEquals(1f, rotation.lengthSquared(), 0.000001f);
            Vector axis = new Vector(size, size, size), original = axis.clone();
            assertEquals(1f, Extensions.axisAngle(axis, 0.7).lengthSquared(), 0.000001f);
            assertEquals(original, axis);
        }
        assertTrue(Extensions.axisAngle(new Vector(0, 1, 0), Double.MAX_VALUE).isFinite());
        assertThrows(ScriptError.class, () -> Extensions.quaternion(0, 0, 0, 0));
        assertThrows(ScriptError.class, () -> Extensions.quaternion(Double.NaN, 0, 0, 1));
        assertThrows(ScriptError.class, () -> Extensions.axisAngle(new Vector(), 1));
        assertThrows(ScriptError.class, () -> Extensions.axisAngle(new Vector(0, 1, 0), Double.POSITIVE_INFINITY));
        assertThrows(ScriptError.class, () -> Extensions.vector(new Vector(Double.MAX_VALUE, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> Extensions.vector(new Vector(Double.NaN, 0, 0)));
        assertEquals(15, Extensions.brightness(0, 15).getSkyLight());
        assertThrows(ScriptError.class, () -> Extensions.brightness(-1, 0));
        assertThrows(ScriptError.class, () -> Extensions.brightness(0, 16));
    }

    @Test void displayTransformChangesCopyInputsAndPreserveOtherComponents() {
        Transformation original = new Transformation(new Vector3f(1, 2, 3), new Quaternionf(), new Vector3f(2), new Quaternionf());
        Transformation[] value = {original};
        Display display = BukkitFakes.fake(Display.class, Map.of("getTransformation", a -> value[0],
                "setTransformation", a -> { value[0] = (Transformation) a[0]; return null; }));
        Vector input = new Vector(7, 8, 9);
        Extensions.translation(display, input);
        input.setX(99);
        assertEquals(new Vector3f(7, 8, 9), value[0].getTranslation());
        assertEquals(new Vector3f(1, 2, 3), original.getTranslation());
        assertEquals(new Vector3f(2), value[0].getScale());
        Extensions.scale(display, new Vector(4, 5, 6));
        Quaternionf rotation = Extensions.axisAngle(new Vector(0, 1, 0), 1);
        Extensions.rotation(display, rotation, true);
        rotation.identity();
        assertNotEquals(rotation, value[0].getLeftRotation());
        assertEquals(new Quaternionf(), value[0].getRightRotation());
        Vector copy = Extensions.vector(value[0].getTranslation());
        copy.setX(100);
        assertEquals(7f, value[0].getTranslation().x);
    }

    @Test void generatedBoxOperationsReturnCopiesAndKeepBoundarySemantics() {
        start(List.of(new SourceFile("box.tys", """
                function change(b: BoundingBox): BoundingBox {
                    return b.expand(1.0).shift(Vector(2.0, 0.0, 0.0)).union(BoundingBox(-3.0, 0.0, 0.0, 0.0, 1.0, 1.0))
                }
                function contains(b: BoundingBox, p: Vector): bool { return b.contains(p) }
                """)));
        BoundingBox box = new BoundingBox(0, 0, 0, 1, 1, 1);
        BoundingBox result = (BoundingBox) call("box.tys", "change(BoundingBox)", box);
        assertEquals(new BoundingBox(0, 0, 0, 1, 1, 1), box);
        assertEquals(new BoundingBox(-3, -1, -1, 4, 2, 2), result);
        assertEquals(true, call("box.tys", "contains(BoundingBox, Vector)", box, new Vector(0, 0, 0)));
        assertEquals(false, call("box.tys", "contains(BoundingBox, Vector)", box, new Vector(1, 1, 1)));
    }

    @Test void statisticOverloadsForwardTypedArgumentsThroughRealBindings() {
        List<List<Object>> calls = new ArrayList<>();
        Player player = BukkitFakes.fake(Player.class, Map.of(
                "getStatistic", a -> { calls.add(List.of(a)); return 7; },
                "setStatistic", a -> { calls.add(List.of(a)); return null; },
                "incrementStatistic", a -> { calls.add(List.of(a)); return null; },
                "decrementStatistic", a -> { calls.add(List.of(a)); return null; }));
        start(List.of(new SourceFile("stats.tys", """
                function update(p: Player): int {
                    p.setStatistic(Statistic.JUMP, 9)
                    p.incrementStatistic(Statistic.MINE_BLOCK, Material.STONE, 2)
                    p.decrementStatistic(Statistic.KILL_ENTITY, EntityType.ZOMBIE, 1)
                    return p.statistic(Statistic.JUMP) + p.statistic(Statistic.MINE_BLOCK, Material.STONE)
                        + p.statistic(Statistic.KILL_ENTITY, EntityType.ZOMBIE)
                }
                """)));
        assertEquals(21, call("stats.tys", "update(Player)", player));
        assertEquals(List.of(Statistic.JUMP, 9), calls.get(0));
        assertEquals(List.of(Statistic.MINE_BLOCK, Material.STONE, 2), calls.get(1));
        assertEquals(List.of(Statistic.KILL_ENTITY, EntityType.ZOMBIE, 1), calls.get(2));
        assertEquals(6, calls.size());
    }

    @Test void signHelpersSelectSideValidateLineAndCommitStateChanges() {
        Component[][] lines = new Component[2][4];
        boolean[] glowing = new boolean[2], waxed = {false};
        DyeColor[] colors = {DyeColor.BLACK, DyeColor.BLACK};
        SignSide[] sides = new SignSide[2];
        for (int index = 0; index < sides.length; index++) {
            final int side = index;
            sides[index] = BukkitFakes.fake(SignSide.class, Map.of(
                    "line/1", a -> lines[side][(Integer) a[0]],
                    "line/2", a -> { lines[side][(Integer) a[0]] = (Component) a[1]; return null; },
                    "isGlowingText", a -> glowing[side],
                    "setGlowingText", a -> { glowing[side] = (Boolean) a[0]; return null; },
                    "getColor", a -> colors[side],
                    "setColor", a -> { colors[side] = (DyeColor) a[0]; return null; }));
        }
        int[] commits = {0};
        Sign sign = BukkitFakes.fake(Sign.class, Map.of("getSide", a -> sides[a[0] == Side.FRONT ? 0 : 1],
                "setWaxed", a -> { waxed[0] = (Boolean) a[0]; return null; }, "isWaxed", a -> waxed[0],
                "update/0", a -> { commits[0]++; return true; }));
        Block block = BukkitFakes.fake(Block.class, Map.of("getState", a -> sign));
        Component text = Component.text("Welcome");
        Extensions.signLine(block, Side.BACK, 3, text);
        Extensions.signGlowing(block, Side.BACK, true);
        Extensions.signColor(block, Side.BACK, DyeColor.YELLOW);
        Extensions.signWaxed(block, true);
        assertEquals(text, Extensions.signLine(block, Side.BACK, 3));
        assertNull(Extensions.signLine(block, Side.FRONT, 3));
        assertTrue(Extensions.signGlowing(block, Side.BACK));
        assertFalse(Extensions.signGlowing(block, Side.FRONT));
        assertEquals(DyeColor.YELLOW, Extensions.signColor(block, Side.BACK));
        assertTrue(Extensions.signWaxed(block)); assertEquals(4, commits[0]);
        assertThrows(ScriptError.class, () -> Extensions.signLine(block, Side.FRONT, 4));
        assertThrows(ScriptError.class, () -> Extensions.signLine(block, Side.BACK, -1, text));
        Block stone = BukkitFakes.fake(Block.class, Map.of("getState", a -> BukkitFakes.fake(BlockState.class, Map.of())));
        assertThrows(ScriptError.class, () -> Extensions.signWaxed(stone, true));
        assertEquals(4, commits[0]);
    }
}
