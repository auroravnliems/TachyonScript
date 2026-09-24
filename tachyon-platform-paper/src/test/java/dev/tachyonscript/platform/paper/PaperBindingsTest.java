package dev.tachyonscript.platform.paper;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.natives.Arguments;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.compiler.InternalErrorHandler;
import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.LoadReport;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.spi.EngineLogger;
import dev.tachyonscript.engine.spi.EventBridge;
import dev.tachyonscript.engine.spi.Platform;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.runtime.spi.TextService;
import dev.tachyonscript.stdlib.EventApi;
import dev.tachyonscript.stdlib.StandardLibrary;
import dev.tachyonscript.stdlib.WorldApi;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs compiled scripts against the real Paper bindings and real Bukkit event classes, with
 * players, worlds and blocks faked (no server).
 */
class PaperBindingsTest {

    private static final SymbolRegistry REGISTRY = StandardLibrary.registry();

    private final World world = BukkitFakes.world("world");
    private final List<String> logs = new ArrayList<>();

    private BukkitFakes.InlineThreading threading = new BukkitFakes.InlineThreading(false);
    private ScriptEngine engine;

    private Bindings bindings() {
        Logger logger = Logger.getLogger("TachyonScriptTest");
        logger.setUseParentHandlers(false);
        for (Handler handler : logger.getHandlers()) {
            logger.removeHandler(handler);
        }
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return Bindings.builder()
                .include(StandardLibrary.coreBindings())
                .include(new PaperBindings(threading, logger, () -> true).create())
                .build();
    }

    private void load(String script) {
        Bindings bindings = bindings();
        TextService text = new AdventureTextService();
        EngineLogger logger = new EngineLogger() {
            @Override
            public void info(String message) {
                logs.add("INFO " + message);
            }

            @Override
            public void warn(String message) {
                logs.add("WARNING " + message);
            }

            @Override
            public void error(String message) {
                logs.add("SEVERE " + message);
            }
        };
        EventBridge events = active -> {
        };
        Platform platform = new Platform() {
            @Override
            public Bindings bindings() {
                return bindings;
            }

            @Override
            public TextService text() {
                return text;
            }

            @Override
            public EventBridge events() {
                return events;
            }

            @Override
            public EngineLogger logger() {
                return logger;
            }
        };
        engine = new ScriptEngine(REGISTRY, platform, EngineOptions.DEFAULT, InternalErrorHandler.IGNORE);
        LoadReport report = engine.load(() -> List.of(new SourceFile("test.tys", script)));
        StringBuilder problems = new StringBuilder();
        report.diagnostics().forEach(d -> problems.append(DiagnosticRenderer.plain().render(d)));
        assertTrue(report.failed().isEmpty(), () -> problems + " " + report.linkProblems());
    }

    private BukkitFakes.PlayerState state(String name) {
        return new BukkitFakes.PlayerState(name, new Location(world, 10, 64, -5));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Colors used anywhere in a component tree. */
    private static Set<Object> colors(Component component) {
        Set<Object> colors = new HashSet<>();
        if (component.color() != null) {
            colors.add(component.color());
        }
        for (Component child : component.children()) {
            colors.addAll(colors(child));
        }
        return colors;
    }

    // ================================================================= completeness

    @Test
    void bindsEveryStandardDeclaration() {
        Bindings bindings = bindings();
        List<String> missingNatives = REGISTRY.natives().stream()
                .filter(declaration -> bindings.lookup(declaration).isEmpty())
                .map(NativeDeclaration::key)
                .toList();
        assertEquals(List.of(), missingNatives);
        // 'any' is the top type: values of it are never checked against a Java class.
        List<String> missingTypes = REGISTRY.types().stream()
                .filter(type -> type != Types.ANY && bindings.typeClass(type).isEmpty())
                .map(ClassType::name)
                .toList();
        assertEquals(List.of(), missingTypes);
    }

    @Test
    void bridgesEveryStandardEvent() {
        List<String> unbridged = REGISTRY.events().stream()
                .filter(event -> !PaperEventBridge.EVENT_CLASSES.containsKey(event))
                .map(event -> event.name())
                .toList();
        assertEquals(List.of(), unbridged);
    }

    // ================================================================= messages

    @Test
    void sendsMiniMessageTemplates() {
        load("""
                event player.join {
                    player.send("<green>Welcome, {player.name}!")
                }
                """);
        BukkitFakes.PlayerState steve = state("Steve");
        engine.dispatch(EventApi.PLAYER_JOIN, new PlayerJoinEvent(BukkitFakes.player(steve), (Component) null));
        assertEquals(1, steve.messages.size());
        Component message = steve.messages.getFirst();
        assertEquals("Welcome, Steve!", plain(message));
        assertEquals(Set.of(NamedTextColor.GREEN), colors(message));
    }

    @Test
    void insertsValuesAsTextNeverAsMiniMessage() {
        load("""
                event player.join {
                    player.send("<green>Welcome, {player.name}!")
                    player.send("<gradient:red:blue>Hi {player.name}</gradient>")
                }
                """);
        BukkitFakes.PlayerState mallory = state("<red>Mallory<click:run_command:/op Mallory>");
        engine.dispatch(EventApi.PLAYER_JOIN, new PlayerJoinEvent(BukkitFakes.player(mallory), (Component) null));
        assertEquals("Welcome, <red>Mallory<click:run_command:/op Mallory>!", plain(mallory.messages.get(0)));
        assertFalse(colors(mallory.messages.get(0)).contains(NamedTextColor.RED));
        assertEquals("Hi <red>Mallory<click:run_command:/op Mallory>", plain(mallory.messages.get(1)));
        assertTrue(mallory.messages.stream().allMatch(m -> m.clickEvent() == null && m.children().stream()
                .allMatch(c -> c.clickEvent() == null)));
    }

    @Test
    void changesJoinMessages() {
        load("""
                event player.join {
                    event.joinMessage = "<yellow>{player.name} is here"
                }
                """);
        PlayerJoinEvent join = new PlayerJoinEvent(BukkitFakes.player(state("Alex")), Component.text("Alex joined the game"));
        engine.dispatch(EventApi.PLAYER_JOIN, join);
        assertEquals("Alex is here", plain(join.joinMessage()));
    }

    // ================================================================= events

    @Test
    void cancelsBlockBreaksWithoutPermission() {
        load("""
                event block.break {
                    if !player.hasPermission("build.bypass") {
                        event.cancel()
                        player.send("You cannot break {block.type} here.")
                    }
                }
                """);
        BukkitFakes.PlayerState guest = state("Guest");
        Player guestPlayer = BukkitFakes.player(guest);
        BlockBreakEvent denied = new BlockBreakEvent(BukkitFakes.block(guest.location, Material.STONE), guestPlayer);
        engine.dispatch(EventApi.BLOCK_BREAK, denied);
        assertTrue(denied.isCancelled());
        assertEquals("You cannot break minecraft:stone here.", plain(guest.messages.getFirst()));

        BukkitFakes.PlayerState builder = state("Builder");
        builder.permissions.add("build.bypass");
        BlockBreakEvent allowed = new BlockBreakEvent(BukkitFakes.block(builder.location, Material.DIRT),
                BukkitFakes.player(builder));
        engine.dispatch(EventApi.BLOCK_BREAK, allowed);
        assertFalse(allowed.isCancelled());
    }

    @Test
    void readsChatAsPlainText() {
        load("""
                event player.chat {
                    if message.contains("badword") {
                        event.cancel()
                        player.send("<red>Please keep the chat friendly.")
                    }
                }
                """);
        BukkitFakes.PlayerState chatter = state("Chatter");
        Player player = BukkitFakes.player(chatter);
        Component text = Component.text("this has a ").append(Component.text("badword", NamedTextColor.RED));
        AsyncChatEvent chat = new AsyncChatEvent(true, player, Set.of(), ChatRenderer.defaultRenderer(), text, text, null);
        engine.dispatch(EventApi.PLAYER_CHAT, chat);
        assertTrue(chat.isCancelled());
        assertEquals("Please keep the chat friendly.", plain(chatter.messages.getFirst()));
    }

    // ================================================================= threading

    @Test
    void routesEntityWritesThroughTheOwner() {
        load("""
                event player.join {
                    player.food = 25
                    player.level = -3
                    player.gameMode = GameMode.CREATIVE
                }
                """);
        BukkitFakes.PlayerState steve = state("Steve");
        steve.food = 5;
        steve.level = 7;
        engine.dispatch(EventApi.PLAYER_JOIN, new PlayerJoinEvent(BukkitFakes.player(steve), (Component) null));
        assertEquals(20, steve.food, "food is clamped to the vanilla maximum");
        assertEquals(0, steve.level, "levels cannot be negative");
        assertEquals(GameMode.CREATIVE, steve.gameMode);
        assertEquals(3, threading.entityWrites, "every entity write goes through the owner's thread");
    }

    @Test
    void teleportsOnTheOwnerAndAsynchronouslyOnFolia() {
        String script = """
                event player.join {
                    player.teleport(player.location.add(0.0, 10.0, 0.0))
                }
                """;
        load(script);
        BukkitFakes.PlayerState paper = state("PaperPlayer");
        engine.dispatch(EventApi.PLAYER_JOIN, new PlayerJoinEvent(BukkitFakes.player(paper), (Component) null));
        assertEquals(74.0, paper.teleportedTo.getY());
        assertFalse(paper.teleportedAsync);
        assertEquals(1, threading.entityWrites);

        threading = new BukkitFakes.InlineThreading(true);
        load(script);
        BukkitFakes.PlayerState folia = state("FoliaPlayer");
        engine.dispatch(EventApi.PLAYER_JOIN, new PlayerJoinEvent(BukkitFakes.player(folia), (Component) null));
        assertEquals(74.0, folia.teleportedTo.getY());
        assertTrue(folia.teleportedAsync, "Folia only supports asynchronous teleports");
        assertEquals(64.0, folia.location.getY(), "the script's location value was not modified");
    }

    // ================================================================= errors

    @Test
    void reportsScriptErrorsFromBindings() {
        Location a = new Location(world, 0, 0, 0);
        Location b = new Location(BukkitFakes.world("nether"), 0, 0, 0);
        NativeDeclaration distance = WorldApi.LOCATION_DISTANCE.invocable();
        NativeFunction.OfDouble function = (NativeFunction.OfDouble) bindings().lookup(distance).orElseThrow();
        ScriptError error = assertThrows(ScriptError.class, () -> function.call(new ArgumentsOf(a, b)));
        assertTrue(error.getMessage().contains("different worlds"), error.getMessage());
        assertEquals(5.0, function.call(new ArgumentsOf(a, new Location(world, 3, 4, 0))));
    }

    @Test
    void reportsRuntimeErrorsWithScriptLocations() {
        load("""
                event player.join {
                    let far = location(player.world, 0.0, 0.0, 0.0)
                    player.teleport(far)
                    let names: List<string> = []
                    player.send("{names[3]}")
                }
                """);
        engine.dispatch(EventApi.PLAYER_JOIN, new PlayerJoinEvent(BukkitFakes.player(state("Steve")), (Component) null));
        String errors = String.join("\n", logs);
        assertTrue(errors.contains("test.tys:5"), errors);
    }

    @Test
    void logsAtTheRightLevels() {
        load("""
                event player.join {
                    log.info("info {player.name}")
                    log.warn("warn")
                    log.error("error")
                    log.debug("debug")
                }
                """);
        engine.dispatch(EventApi.PLAYER_JOIN, new PlayerJoinEvent(BukkitFakes.player(state("Steve")), (Component) null));
        assertTrue(logs.contains("INFO info Steve"), logs::toString);
        assertTrue(logs.contains("WARNING warn"), logs::toString);
        assertTrue(logs.contains("SEVERE error"), logs::toString);
        assertTrue(logs.contains("INFO [debug] debug"), logs::toString);
    }

    /** Arguments backed by an array, for calling bindings directly. */
    private record ArgumentsOf(Object... values) implements Arguments {
        @Override
        public int count() {
            return values.length;
        }

        @Override
        public Object getRef(int index) {
            return values[index];
        }

        @Override
        public int getInt(int index) {
            return (Integer) values[index];
        }

        @Override
        public long getLong(int index) {
            return (Long) values[index];
        }

        @Override
        public float getFloat(int index) {
            return (Float) values[index];
        }

        @Override
        public double getDouble(int index) {
            return (Double) values[index];
        }

        @Override
        public boolean getBool(int index) {
            return (Boolean) values[index];
        }
    }
}
