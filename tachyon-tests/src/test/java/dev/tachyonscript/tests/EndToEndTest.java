package dev.tachyonscript.tests;

import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.LoadMode;
import dev.tachyonscript.engine.LoadReport;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.stdlib.EventApi;
import dev.tachyonscript.testkit.Fakes;
import dev.tachyonscript.testkit.InMemoryScripts;
import dev.tachyonscript.testkit.TestPlatform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndToEndTest {

    private TestPlatform platform;
    private ScriptEngine engine;
    private InMemoryScripts scripts;

    @BeforeEach
    void setUp() {
        platform = new TestPlatform();
        engine = platform.engine();
        scripts = new InMemoryScripts();
    }

    private LoadReport load() {
        LoadReport report = engine.load(scripts);
        return report;
    }

    private LoadReport loadClean() {
        LoadReport report = load();
        StringBuilder rendered = new StringBuilder();
        report.diagnostics().forEach(d -> rendered.append(DiagnosticRenderer.plain().render(d)));
        assertTrue(report.activated() && report.failed().isEmpty(), () -> rendered + " " + report.linkProblems());
        return report;
    }

    @Test
    void runsTheFirstScript() {
        scripts.put("join.tys", """
                event player.join {
                    player.send("Hello {player.name}!")
                }
                """);
        loadClean();
        Fakes.Player steve = platform.join("Steve");
        platform.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(steve));
        assertEquals(List.of("Hello Steve!"), steve.messages());
    }

    @Test
    void runsTheSpecificationExamples() {
        scripts.put("welcome.tys", """
                const PREFIX = "<gold>[Server]</gold> "

                function heal(target: Player) {
                    target.health = target.maxHealth
                    target.food = 20
                    target.send("{PREFIX}<green>You have been healed.")
                }

                event player.join {
                    player.send("<gradient:#ffffff:#a855f7>Welcome {player.name}!</gradient>")
                    if player.hasPermission("server.vip") {
                        heal(player)
                    }
                }
                """);
        scripts.put("combat.tys", """
                event player.death {
                    if killer != null {
                        killer.send("<yellow>You killed {victim.name}.")
                    }
                    event.deathMessage = null
                }

                event block.break {
                    if !player.hasPermission("build.bypass") {
                        event.cancel()
                    }
                }

                event entity.damage {
                    if entity is Player && cause == "fall" {
                        event.damage = math.max(0.0, event.damage - 4.0)
                    }
                }
                """);
        LoadReport report = loadClean();
        assertEquals(2, report.scripts());
        assertEquals(4, report.handlers());

        Fakes.Player vip = platform.join("Aurora").grant("server.vip");
        vip.health(5);
        vip.food(3);
        platform.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(vip));
        assertEquals(List.of("<gradient:#ffffff:#a855f7>Welcome Aurora!</gradient>",
                "<gold>[Server]</gold> <green>You have been healed."), vip.messages());
        assertEquals(20.0, vip.health());
        assertEquals(20, vip.food());

        Fakes.Player victim = platform.join("Steve");
        Fakes.DeathEvent death = new Fakes.DeathEvent(victim, vip);
        platform.fire(engine, EventApi.PLAYER_DEATH, death);
        assertEquals("<yellow>You killed Steve.", vip.messages().getLast());
        assertEquals(null, death.deathMessage);
        platform.fire(engine, EventApi.PLAYER_DEATH, new Fakes.DeathEvent(victim, null));

        Fakes.BlockBreakEvent breakEvent = new Fakes.BlockBreakEvent(victim,
                new Fakes.Block(victim.location(), "minecraft:stone"));
        platform.fire(engine, EventApi.BLOCK_BREAK, breakEvent);
        assertTrue(breakEvent.isCancelled());
        Fakes.BlockBreakEvent allowed = new Fakes.BlockBreakEvent(victim.grant("build.bypass"), breakEvent.block);
        platform.fire(engine, EventApi.BLOCK_BREAK, allowed);
        assertFalse(allowed.isCancelled());

        Fakes.DamageEvent fall = new Fakes.DamageEvent(victim, "fall", 10);
        platform.fire(engine, EventApi.ENTITY_DAMAGE, fall);
        assertEquals(6.0, fall.damage);
        Fakes.DamageEvent zombie = new Fakes.DamageEvent(new Fakes.LivingEntity("Zombie", victim.location()), "fall", 10);
        platform.fire(engine, EventApi.ENTITY_DAMAGE, zombie);
        assertEquals(10.0, zombie.damage);
    }

    @Test
    void broadcastsAndIteratesPlayers() {
        scripts.put("greet.tys", """
                event player.join {
                    for other in server.players {
                        if other != player {
                            other.send("{player.name} joined ({server.onlineCount} online)")
                        }
                    }
                    broadcast("<gray>Welcome, {player}")
                }
                """);
        loadClean();
        Fakes.Player alex = platform.join("Alex");
        Fakes.Player steve = platform.join("Steve");
        platform.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(steve));
        assertEquals(List.of("Steve joined (2 online)", "<gray>Welcome, Steve"), alex.messages());
        assertEquals(List.of("<gray>Welcome, Steve"), steve.messages());
    }

    @Test
    void keepsWorkingScriptsWhenAReloadFails() {
        scripts.put("join.tys", "event player.join {\n    player.send(\"v1\")\n}");
        scripts.put("other.tys", "event player.quit {\n    player.send(\"bye\")\n}");
        loadClean();
        Fakes.Player steve = platform.join("Steve");

        scripts.put("join.tys", "event player.join {\n    player.sned(\"broken\")\n}");
        LoadReport broken = load();
        assertTrue(broken.activated());
        assertEquals(List.of("join.tys"), broken.failed());
        assertEquals(List.of("join.tys"), broken.keptPrevious());
        platform.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(steve));
        assertEquals(List.of("v1"), steve.messages(), "old working script remains active");

        scripts.put("join.tys", "event player.join {\n    player.send(\"v2\")\n}");
        LoadReport fixed = loadClean();
        assertEquals(1, fixed.compiled());
        assertEquals(1, fixed.reused(), "unchanged scripts are not recompiled");
        platform.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(steve));
        assertEquals(List.of("v1", "v2"), steve.messages());
    }

    @Test
    void strictModeRejectsTheWholeLoad() {
        ScriptEngine strict = platform.engine(new EngineOptions(LoadMode.STRICT, EngineOptions.DEFAULT.compiler(),
                EngineOptions.DEFAULT.limits(), 0, false));
        scripts.put("a.tys", "event player.join {\n    player.send(\"a1\")\n}");
        assertTrue(strict.load(scripts).activated());
        long generation = strict.generation().id();
        scripts.put("a.tys", "event player.join {\n    player.send(\"a2\")\n}");
        scripts.put("b.tys", "event player.join {\n    nope()\n}");
        LoadReport report = strict.load(scripts);
        assertFalse(report.activated());
        assertEquals(generation, strict.generation().id());
        Fakes.Player steve = platform.join("Steve");
        strict.dispatch(EventApi.PLAYER_JOIN, new Fakes.JoinEvent(steve));
        assertEquals(List.of("a1"), steve.messages());
    }

    @Test
    void tracksActiveEventsForListenerRegistration() {
        scripts.put("move.tys", "event player.move {\n    if player.health < 10 {\n        player.send(\"low\")\n    }\n}");
        loadClean();
        assertEquals(java.util.Set.of(EventApi.PLAYER_MOVE), platform.activeEvents());
        scripts.remove("move.tys");
        loadClean();
        assertEquals(java.util.Set.of(), platform.activeEvents(), "no listener stays registered without handlers");
    }

    @Test
    void shutdownDeactivatesScriptsAndRefusesLaterLoads() {
        scripts.put("join.tys", "event player.join {\n    player.send(\"hi\")\n}");
        loadClean();
        engine.shutdown();
        assertEquals(java.util.Set.of(), platform.activeEvents());
        LoadReport late = load();
        assertFalse(late.activated());
        assertEquals("The engine has been shut down.", late.failure());
        Fakes.Player steve = platform.join("Steve");
        platform.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(steve));
        assertEquals(List.of(), steve.messages());
    }

    @Test
    void reportsRuntimeErrorsWithoutStoppingOtherHandlers() {
        scripts.put("a.tys", """
                event player.join {
                    let zero = player.food - player.food
                    player.send("ratio {10 / zero}")
                }
                """);
        scripts.put("b.tys", "event player.join {\n    player.send(\"still running\")\n}");
        loadClean();
        Fakes.Player steve = platform.join("Steve");
        for (int i = 0; i < 5; i++) {
            platform.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(steve));
        }
        assertEquals(5, steve.messages().size());
        List<String> errors = platform.logs().stream().filter(line -> line.startsWith("ERROR")).toList();
        assertEquals(1, errors.size(), "repeated errors are rate limited: " + errors);
        assertTrue(errors.getFirst().contains("TachyonRuntimeError: Division by zero."), errors.getFirst());
        assertTrue(errors.getFirst().contains("at a.tys:3 (event player.join)"), errors.getFirst());
        assertEquals(5, engine.errors().sites().getFirst().count());
    }

    @Test
    void profilesHandlers() {
        scripts.put("join.tys", "event player.join {\n    player.send(\"hi\")\n}");
        loadClean();
        Fakes.Player steve = platform.join("Steve");
        engine.startProfiling();
        for (int i = 0; i < 100; i++) {
            platform.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(steve));
        }
        engine.stopProfiling();
        var rows = engine.profiler().report().byScript();
        assertEquals(1, rows.size());
        assertEquals(100, rows.getFirst().calls());
        assertTrue(engine.profiler().report().format().contains("join.tys"));
    }

    @Test
    void warnsAboutSlowHandlers() {
        ScriptEngine slow = platform.engine(new EngineOptions(LoadMode.LENIENT, EngineOptions.DEFAULT.compiler(),
                EngineOptions.DEFAULT.limits(), 1_000, false));
        scripts.put("busy.tys", """
                event player.join {
                    var total = 0
                    for i in 1..200000 {
                        total += i % 7
                    }
                    player.send("{total}")
                }
                """);
        assertTrue(slow.load(scripts).activated());
        slow.dispatch(EventApi.PLAYER_JOIN, new Fakes.JoinEvent(platform.join("Steve")));
        assertTrue(platform.logs().stream().anyMatch(line -> line.startsWith("WARN Slow script execution: busy.tys:1")),
                () -> platform.logs().toString());
    }

    @Test
    void releasesOldGenerationsAfterReload() throws InterruptedException {
        scripts.put("join.tys", "event player.join {\n    player.send(\"v0\")\n}");
        loadClean();
        java.lang.ref.WeakReference<Object> old =
                new java.lang.ref.WeakReference<>(engine.generation().scripts().get("join.tys").linked());
        Fakes.Player steve = platform.join("Steve");
        platform.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(steve));
        for (int i = 1; i <= 20; i++) {
            scripts.put("join.tys", "event player.join {\n    player.send(\"v" + i + "\")\n}");
            loadClean();
            platform.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(steve));
        }
        for (int attempt = 0; attempt < 20 && old.get() != null; attempt++) {
            System.gc();
            Thread.sleep(20);
        }
        assertEquals(null, old.get(), "a replaced module must not be retained");
        assertEquals("v20", steve.messages().getLast());
    }
}
