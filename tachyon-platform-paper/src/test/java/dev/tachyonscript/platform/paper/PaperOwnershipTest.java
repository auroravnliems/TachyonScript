package dev.tachyonscript.platform.paper;

import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.natives.Arguments;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.stdlib.EntityApi;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperOwnershipTest {
    private static final Plugin PLUGIN = BukkitFakes.fake(Plugin.class, Map.of(
            "getDataFolder", args -> new File("build/tmp/ownership-test")));

    private static Stream<PropertyDeclaration> ownedReads() {
        return Stream.of(EntityApi.SENDER_NAME, EntityApi.ENTITY_NAME, EntityApi.ENTITY_LOCATION,
                EntityApi.ENTITY_WORLD, EntityApi.ENTITY_VALID, EntityApi.HEALTH, EntityApi.FOOD,
                EntityApi.LEVEL, EntityApi.GAME_MODE_PROPERTY, EntityApi.DISPLAY_NAME);
    }

    @ParameterizedTest
    @MethodSource("ownedReads")
    void rejectsForeignTickThreadBeforeTouchingAnyEntityState(PropertyDeclaration property) {
        // Every entity method, including getName used in diagnostics, fails if it is called.
        Player foreign = BukkitFakes.fake(Player.class, Map.of());
        Threading threads = BukkitFakes.fake(Threading.class, Map.of(
                "ownsEntity", args -> false, "onTickThread", args -> true));
        PaperContext context = new PaperContext(PLUGIN, threads);
        try {
            ScriptError error = assertThrows(ScriptError.class,
                    () -> read(bindings(context), property, foreign));
            assertTrue(error.getMessage().contains("thread that owns the entity"), error::getMessage);
        } finally {
            context.web().close();
        }
    }

    @Test
    void ownedReadsRunInlineAndAsyncReadsWaitForTheOwner() {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> actualRead = new AtomicReference<>();
        Player player = BukkitFakes.fake(Player.class, Map.of("getHealth", args -> {
            actualRead.set(Thread.currentThread());
            return 6.0;
        }));
        Threading owned = BukkitFakes.fake(Threading.class, Map.of("ownsEntity", args -> true));
        PaperContext inline = new PaperContext(PLUGIN, owned);
        Threading asynchronous = BukkitFakes.fake(Threading.class, Map.of(
                "ownsEntity", args -> false, "onTickThread", args -> false,
                "forEntity", args -> {
                    assertSame(player, args[0]);
                    Thread.ofPlatform().name("test-entity-owner").start((Runnable) args[1]);
                    return null;
                }));
        PaperContext queued = new PaperContext(PLUGIN, asynchronous);
        try {
            assertEquals(6.0, read(bindings(inline), EntityApi.HEALTH, player));
            assertSame(caller, actualRead.get());
            assertEquals(6.0, read(bindings(queued), EntityApi.HEALTH, player));
            assertNotSame(caller, actualRead.get());
            assertEquals("test-entity-owner", actualRead.get().getName());
        } finally {
            inline.web().close();
            queued.web().close();
        }
    }

    @Test
    void displayNameWriteIsDeferredToTheOwner() {
        AtomicReference<Component> current = new AtomicReference<>();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        Player player = BukkitFakes.fake(Player.class, Map.of("displayName/1", args -> {
            current.set((Component) args[0]);
            return null;
        }));
        Threading threads = BukkitFakes.fake(Threading.class, Map.of("forEntity", args -> {
            assertSame(player, args[0]);
            queued.set((Runnable) args[1]);
            return null;
        }));
        PaperContext context = new PaperContext(PLUGIN, threads);
        try {
            Component name = Component.text("new name");
            NativeFunction.OfVoid write = (NativeFunction.OfVoid) bindings(context)
                    .lookup(EntityApi.DISPLAY_NAME.setter().orElseThrow()).orElseThrow();
            write.call(arguments(player, name));
            assertNull(current.get());
            queued.get().run();
            assertEquals(name, current.get());
        } finally {
            context.web().close();
        }
    }

    private static Bindings bindings(PaperContext context) {
        return new PaperBindings(context, Logger.getLogger("ownership-test"), () -> false).create();
    }

    private static Object read(Bindings bindings, PropertyDeclaration property, Player player) {
        Arguments arguments = arguments(player);
        return switch (bindings.lookup(property.getter()).orElseThrow()) {
            case NativeFunction.OfRef read -> read.call(arguments);
            case NativeFunction.OfDouble read -> read.call(arguments);
            case NativeFunction.OfInt read -> read.call(arguments);
            case NativeFunction.OfBool read -> read.call(arguments);
            default -> throw new AssertionError(property);
        };
    }

    private static Arguments arguments(Object... values) {
        List<Object> arguments = List.of(values);
        return BukkitFakes.fake(Arguments.class, Map.of(
                "count", args -> arguments.size(), "getRef", args -> arguments.get((Integer) args[0])));
    }
}
