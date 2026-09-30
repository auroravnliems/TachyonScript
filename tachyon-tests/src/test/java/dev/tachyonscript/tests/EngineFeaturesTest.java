package dev.tachyonscript.tests;

import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.LoadReport;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.spi.CommandRegistry;
import dev.tachyonscript.engine.storage.MemoryBackend;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.stdlib.EventApi;
import dev.tachyonscript.testkit.Fakes;
import dev.tachyonscript.testkit.InMemoryScripts;
import dev.tachyonscript.testkit.TestPlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The engine features of version 0.2: scheduling, commands, saved variables, modules, hooks and priorities. */
class EngineFeaturesTest {

    private TestPlatform platform;
    private MemoryBackend storage;
    private ScriptEngine engine;
    private InMemoryScripts scripts;

    @BeforeEach
    void setUp() {
        platform = new TestPlatform();
        storage = new MemoryBackend();
        engine = platform.engine(EngineOptions.DEFAULT.withStorage(storage, 0));
        scripts = new InMemoryScripts();
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    private LoadReport load() {
        LoadReport report = engine.load(scripts);
        StringBuilder rendered = new StringBuilder();
        report.diagnostics().forEach(d -> rendered.append(DiagnosticRenderer.plain().render(d)).append('\n'));
        assertTrue(report.activated() && report.failed().isEmpty(), () -> rendered + " " + report.linkProblems()
                + " " + report.failure());
        return report;
    }

    private void join(Fakes.Player player) {
        platform.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(player));
    }

    private List<String> errors() {
        return platform.logs().stream().filter(line -> line.startsWith("ERROR")).toList();
    }

    // ---------------------------------------------------------------- scheduling

    @Test
    void afterAndEveryBlocksRunLaterAndStopWithTheirScript() {
        scripts.put("timers.tys", """
                event player.join {
                    let name = player.name
                    after 2 seconds {
                        player.send("Welcome back, {name}")
                    }
                    every 1 second {
                        player.send("tick {task.runs}")
                        if task.runs >= 3 {
                            task.cancel()
                        }
                    }
                }
                """);
        load();
        Fakes.Player steve = platform.join("Steve");
        join(steve);
        assertEquals(List.of(), steve.messages());
        platform.scheduler().tick(20);
        assertEquals(List.of("tick 1"), steve.messages());
        platform.scheduler().tick(20);
        assertEquals(List.of("tick 1", "Welcome back, Steve", "tick 2"), steve.messages());
        platform.scheduler().tick(100);
        assertEquals(List.of("tick 1", "Welcome back, Steve", "tick 2", "tick 3"), steve.messages());
        assertEquals(0, platform.scheduler().pending());

        // A reload cancels what the old version scheduled.
        join(steve);
        scripts.put("timers.tys", "event player.quit {\n}\n");
        load();
        platform.scheduler().tick(200);
        assertEquals(4, steve.messages().size());
        assertEquals(List.of(), errors());
    }

    @Test
    void recordsListsAndLogsShowHostValuesLikeTemplates() {
        scripts.put("display.tys", """
                record Visit(who: Player, where: Location, stay: Duration, times: List<Duration>)
                event player.join {
                    let visit = Visit(player, player.location, 90 seconds, [1 second, 2 minutes])
                    player.send("{visit}")
                    log(server.players)
                }
                """);
        load();
        Fakes.Player steve = platform.join("Steve");
        join(steve);
        assertEquals(List.of("Visit(who=Steve, where=world 0.5, 64, 0.5, stay=1m 30s, times=[1s, 2m])"),
                steve.messages());
        assertTrue(platform.logs().contains("SCRIPT info [Steve]"), () -> platform.logs().toString());
    }

    @Test
    void asyncAndSyncBlocksRun() {
        scripts.put("async.tys", """
                event player.join {
                    async {
                        let text = "computed"
                        sync {
                            player.send("{text}")
                        }
                    }
                }
                """);
        load();
        Fakes.Player alex = platform.join("Alex");
        join(alex);
        platform.scheduler().tick(1);
        assertEquals(List.of("computed"), alex.messages());
        assertEquals(1, platform.scheduler().asyncRuns());
    }

    @Test
    void tasksAndLifecycleHooksFollowTheScript() {
        scripts.put("life.tys", """
                var ticks = 0
                on load {
                    log("loaded with {ticks}")
                }
                every 5 seconds {
                    ticks += 1
                    log("task {ticks}")
                }
                on unload {
                    log("unloaded after {ticks}")
                }
                """);
        load();
        platform.scheduler().tick(250);
        scripts.remove("life.tys");
        load();
        platform.scheduler().tick(250);
        List<String> logs = platform.logs().stream().filter(line -> line.startsWith("SCRIPT")).toList();
        assertEquals(List.of("SCRIPT info loaded with 0", "SCRIPT info task 1", "SCRIPT info task 2",
                "SCRIPT info unloaded after 2"), logs);
    }

    // ---------------------------------------------------------------- commands

    @Test
    void commandTreesDescribeThemselvesForHelpPages() {
        scripts.put("warps.tys", """
                /// Saves your position as a warp.
                @permission("warps.admin")
                command warp.set(name: string) {
                }

                command warp.go(name: string, note: string...) {
                }

                @description("Heals a player")
                command heal(target: Player? = null, amount: int = 20) {
                }
                """);
        load();
        assertEquals(List.of(
                new CommandRegistry.HelpEntry("/warp set <name>", "Saves your position as a warp.", "warps.admin"),
                new CommandRegistry.HelpEntry("/warp go <name> <note...>", "", "")), platform.helpEntries("warp"));
        assertEquals(List.of(new CommandRegistry.HelpEntry("/heal [target] [amount]", "Heals a player", "")),
                platform.helpEntries("heal"));
    }

    @Test
    void commandsParseArgumentsAndCheckPermissionsAndCooldowns() {
        scripts.put("commands.tys", """
                @permission("server.heal")
                @description("Heals a player")
                command heal(target: Player? = null, amount: int = 20) {
                    let who = target ?? player
                    if who == null {
                        sender.send("Console must name a player")
                        return
                    }
                    who.health = amount as double
                    sender.send("Healed {who.name} to {amount}")
                }

                @cooldown(10 seconds)
                command spin {
                    sender.send("spun")
                }

                command warp.set(name: string) {
                    sender.send("set {name}")
                }

                command warp.go(name: string, note: string...) {
                    sender.send("go {name}: {note}")
                }

                @playerOnly
                command me {
                    player.send("you are {player.name}")
                }
                """);
        load();
        assertEquals(Set.of("heal", "spin", "warp", "me"), platform.commandNames());
        Fakes.Player steve = platform.join("Steve");
        Fakes.Player alex = platform.join("Alex");
        steve.health(5);

        platform.command(steve, "heal");
        assertEquals("<red>You do not have permission to use this command.", steve.messages().getLast());
        steve.grant("server.heal");
        platform.command(steve, "heal");
        assertEquals("Healed Steve to 20", steve.messages().getLast());
        platform.command(steve, "heal Alex 7");
        assertEquals(7.0, alex.health());
        platform.command(steve, "heal Nobody");
        assertEquals("<red>Player 'Nobody' is not online.", steve.messages().getLast());
        platform.command(steve, "heal Alex seven");
        assertEquals("<red>'seven' is not a number.", steve.messages().getLast());
        platform.command(steve, "heal Alex 1 2");
        assertEquals("<red>Too many arguments. Usage: /heal [target] [amount]", steve.messages().getLast());
        platform.command(platform.console(), "heal");
        assertEquals("Console must name a player", platform.console().messages().getLast());

        platform.command(steve, "spin");
        platform.command(steve, "spin");
        assertEquals("<red>Please wait 10s before using this command again.", steve.messages().getLast());

        platform.command(steve, "warp set home");
        assertEquals("set home", steve.messages().getLast());
        platform.command(steve, "warp go spawn with friends");
        assertEquals("go spawn: with friends", steve.messages().getLast());
        platform.command(steve, "warp set");
        // Values are inserted as plain text: the test text service escapes their tags.
        assertEquals("<red>Missing name. Usage: /warp set \\<name>", steve.messages().getLast());
        platform.command(steve, "warp");
        assertEquals("<gold>/warp</gold> <gray>sub-commands:</gray> set, go", steve.messages().getLast());

        platform.command(platform.console(), "me");
        assertEquals("<red>Only players can use this command.", platform.console().messages().getLast());

        assertEquals(List.of("set", "go"), platform.complete(steve, "warp "));
        assertEquals(List.of("Alex"), platform.complete(steve, "heal A"));
        assertEquals(List.of(), errors());
    }

    // ---------------------------------------------------------------- saved variables

    @Test
    void persistentVariablesSurviveReloadsAndAreSaved() {
        scripts.put("stats.tys", """
                persistent var joins: int = 0
                persistent var names: List<string> = []
                event player.join {
                    joins += 1
                    names.add(player.name)
                    player.send("join #{joins}")
                }
                """);
        load();
        Fakes.Player steve = platform.join("Steve");
        join(steve);
        join(steve);
        // Editing the script reloads it; its saved values come back.
        scripts.put("stats.tys", """
                persistent var joins: int = 0
                persistent var names: List<string> = []
                event player.join {
                    joins += 10
                    names.add(player.name)
                    player.send("join #{joins} {names}")
                }
                """);
        load();
        join(steve);
        assertEquals("join #12 [Steve, Steve, Steve]", steve.messages().getLast());
        engine.data().flush();
        assertEquals("12", storage.get("", "stats", "joins"));
        assertEquals("[\"Steve\",\"Steve\",\"Steve\"]", storage.get("", "stats", "names"));

        // A new engine (a server restart) reads them from storage.
        engine.shutdown();
        engine = platform.engine(EngineOptions.DEFAULT.withStorage(storage, 0));
        load();
        join(steve);
        assertEquals("join #22 [Steve, Steve, Steve, Steve]", steve.messages().getLast());
    }

    @Test
    void playerDataIsKeptPerPlayer() {
        scripts.put("coins.tys", """
                playerdata var coins: int = 100
                playerdata var homes: Map<string, string> = {}
                command pay(target: Player, amount: int) {
                    if player == null {
                        return
                    }
                    player.coins -= amount
                    target.coins += amount
                    target.homes["gift"] = "from {player.name}"
                    sender.send("{player.coins} left, {target.name} has {target.coins}")
                }
                placeholder coins {
                    return "{player?.name ?? "?"}:{argument}"
                }
                """);
        load();
        Fakes.Player steve = platform.join("Steve");
        Fakes.Player alex = platform.join("Alex");
        engine.playerJoining(steve.uuid());
        platform.command(steve, "pay Alex 30");
        assertEquals("70 left, Alex has 130", steve.messages().getLast());
        platform.command(steve, "pay Alex 5");
        assertEquals("65 left, Alex has 135", steve.messages().getLast());
        scripts.put("coins.tys", scripts.get("coins.tys") + "\n// edited\n");
        load();
        platform.command(alex, "pay Steve 35");
        assertEquals("100 left, Steve has 100", alex.messages().getLast());
        engine.data().flush();
        assertEquals("100", storage.get(steve.uuid().toString(), "coins", "coins"));
        assertEquals("[[\"gift\",\"from Alex\"]]", storage.get(steve.uuid().toString(), "coins", "homes"));
        assertEquals("Steve:x_y", engine.placeholder("coins_x_y", steve));
        assertEquals("?:", engine.placeholder("coins", null));
        assertNull(engine.placeholder("unknown", steve));
    }

    // ---------------------------------------------------------------- modules

    @Test
    void modulesImportEachOtherAndReloadTogether() {
        scripts.put("economy.tys", """
                module economy
                var total: long = 0
                function pay(p: Player, amount: int) {
                    total += amount
                    p.send("paid {amount}")
                }
                """);
        scripts.put("shop.tys", """
                import economy
                import {pay} from economy
                event player.join {
                    pay(player, 5)
                    economy.pay(player, 7)
                    player.send("total {economy.total}")
                }
                """);
        LoadReport first = load();
        assertEquals(2, first.compiled());
        Fakes.Player steve = platform.join("Steve");
        join(steve);
        assertEquals(List.of("paid 5", "paid 7", "total 12"), steve.messages());

        // Changing the imported module recompiles the module importing it.
        scripts.put("economy.tys", scripts.get("economy.tys").replace("paid {amount}", "paid {amount}!"));
        LoadReport second = load();
        assertEquals(2, second.compiled());
        join(steve);
        assertEquals(List.of("paid 5!", "paid 7!", "total 12"), steve.messages().subList(3, 6));

        // Changing only the importing module keeps the imported one (and its variables).
        scripts.put("shop.tys", scripts.get("shop.tys").replace("total {economy.total}", "sum {economy.total}"));
        LoadReport third = load();
        assertEquals(1, third.compiled());
        join(steve);
        assertEquals("sum 24", steve.messages().getLast());
    }

    @Test
    void importCyclesAreReported() {
        scripts.put("a.tys", "import b\nfunction fa() {\n}\n");
        scripts.put("b.tys", "import a\nfunction fb() {\n}\n");
        LoadReport report = engine.load(scripts);
        assertEquals(List.of("a.tys", "b.tys"), report.failed());
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.message().contains("cycle")), report.diagnostics()::toString);
    }

    // ---------------------------------------------------------------- events

    @Test
    void handlersRunByPriorityAndCanIgnoreCancelledEvents() {
        scripts.put("order.tys", """
                @priority(HIGH)
                event block.break {
                    player.send("high")
                }
                @priority(LOW)
                event block.break {
                    player.send("low")
                    event.cancel()
                }
                @ignoreCancelled
                event block.break {
                    player.send("normal")
                }
                @priority(MONITOR)
                event block.break {
                    player.send("monitor {event.cancelled}")
                }
                """);
        load();
        Fakes.Player steve = platform.join("Steve");
        platform.fire(engine, EventApi.BLOCK_BREAK, new Fakes.BlockBreakEvent(steve,
                new Fakes.Block(steve.location(), "minecraft:stone")));
        assertEquals(List.of("low", "high", "monitor true"), steve.messages());
        assertEquals(Set.of(1, 2, 3, 5), platform.activePriorities().get(EventApi.BLOCK_BREAK));
    }

    @Test
    void runtimeErrorsInHandlersCanBeCaught() {
        scripts.put("safe.tys", """
                event player.join {
                    try {
                        let names: List<string> = []
                        player.send("{names[3]}")
                    } catch e {
                        player.send("oops: {e.kind}")
                    }
                }
                """);
        load();
        Fakes.Player steve = platform.join("Steve");
        join(steve);
        assertEquals(List.of("oops: index"), steve.messages());
        assertFalse(platform.logs().stream().anyMatch(line -> line.startsWith("ERROR")));
    }
}
