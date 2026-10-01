package dev.tachyonscript.tests;

import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.stdlib.EventApi;
import dev.tachyonscript.testkit.Fakes;
import dev.tachyonscript.testkit.InMemoryScripts;
import dev.tachyonscript.testkit.TestPlatform;
import dev.tachyonscript.testkit.TestInteractions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import dev.tachyonscript.api.natives.ScriptError;

class TestInteractionsTest {
    @Test
    void givingBasicItemsDoesNotFailAfterACommandHasChangedSavedData() {
        TestPlatform platform = new TestPlatform();
        ScriptEngine engine = platform.engine();
        try {
            var report = engine.load(new InMemoryScripts().put("give.tys", """
                    persistent var count: int = 0
                    @playerOnly
                    command givetest {
                        count++
                        player.give(Material.IRON_INGOT, 2)
                        player.give(ItemStack(Material.PAPER, 1, "receipt"))
                        log("finished {count}")
                    }
                    """));
            assertTrue(report.failed().isEmpty(), () -> report.diagnostics().toString());
            Fakes.Player player = platform.join("Steve");
            platform.command(player, "givetest");
            assertTrue(platform.logs().contains("SCRIPT info finished 1"), () -> platform.logs().toString());
            assertTrue(platform.logs().stream().noneMatch(line -> line.startsWith("ERROR")));
            assertEquals(List.of(new TestInteractions.GivenItem("iron_ingot", 2, null),
                    new TestInteractions.GivenItem("paper", 1, "receipt")), platform.interactions().given(player));
            assertEquals(List.of(), platform.interactions().given(platform.join("Alex")));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void menuAndItemCanBeObservedInTheTestPlatform() {
        TestPlatform platform = new TestPlatform();
        ScriptEngine engine = platform.engine();
        try {
            assertTrue(engine.load(new InMemoryScripts().put("menu.tys", """
                    event player.join {
                        let menu = Menu(1, "Test menu")
                        menu.set(0, ItemStack(Material.PAPER, 1, "Report"))
                        menu.open(player)
                    }
                    """)).failed().isEmpty());
            Fakes.Player player = platform.join("Steve");
            platform.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(player));
            assertEquals(List.of(new Fakes.Shown("menu", "Test menu", null)), player.messages());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void fileReadWriteAndHttpCallbackCanBeTestedWithoutNetworkAccess() {
        TestPlatform platform = new TestPlatform();
        ScriptEngine engine = platform.engine();
        try {
            var report = engine.load(new InMemoryScripts().put("http.tys", """
                    on load {
                        files.write("test/config.txt", "test data")
                        log(files.read("test/config.txt"))
                        web.post("https://example.invalid/", "test", "application/json",
                            (status, body) => { log("HTTP {status}: {body}") })
                    }
                    """));
            assertTrue(report.failed().isEmpty(), () -> report.diagnostics() + " " + platform.logs());
            platform.scheduler().tick(1);
            assertEquals(List.of("SCRIPT info test data", "SCRIPT info HTTP 204: "),
                    platform.logs().stream().filter(s -> s.startsWith("SCRIPT")).toList());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void menuCallbacksKeepInventoryUpdatesAndDeferCloseThenOpen() {
        TestPlatform platform = new TestPlatform();
        ScriptEngine engine = platform.engine();
        try {
            var scripts = new InMemoryScripts().put("menu.tys", """
                    event player.join {
                        let first = Menu(1, "First")
                        let second = Menu(1, "Second")
                        first.set(0, ItemStack(Material.PAPER), click => {
                            log("before {first.viewers.size}")
                            click.close()
                            second.open(click.player)
                            log("after {first.viewers.size}")
                        })
                        first.inventory.set(0, ItemStack(Material.DIAMOND))
                        first.set(1, ItemStack(Material.PAPER), click => { log("removed") })
                        first.set(1, ItemStack(Material.STONE))
                        first.open(player)
                    }
                    """);
            assertTrue(engine.load(scripts).failed().isEmpty());
            Fakes.Player player = platform.join("Steve");
            platform.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(player));
            assertTrue(platform.interactions().click(player, 1, false, false));
            assertFalse(platform.logs().contains("SCRIPT info removed"));
            assertTrue(platform.interactions().click(player, 0, false, false));
            assertTrue(platform.interactions().describe(player).startsWith("First"));
            assertTrue(platform.logs().containsAll(List.of("SCRIPT info before 1", "SCRIPT info after 1")));
            platform.scheduler().tick(1);
            assertTrue(platform.interactions().describe(player).startsWith("Second"));
            scripts.put("menu.tys", "event player.quit {}\n");
            assertTrue(engine.load(scripts).failed().isEmpty());
            assertEquals("closed", platform.interactions().describe(player));
            assertFalse(platform.interactions().click(player, 0, false, false));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void mockHttpCapturesResponsesAndIgnoresRetiredCallbacks() {
        TestPlatform platform = new TestPlatform();
        ScriptEngine engine = platform.engine();
        try {
            platform.interactions().http(-1, "test failure");
            var scripts = new InMemoryScripts().put("http.tys", """
                    command request {
                        web.get("https://example.invalid/", (status, body) => {
                            log("HTTP {status}: {body}")
                        })
                    }
                    """);
            assertTrue(engine.load(scripts).failed().isEmpty());
            platform.command(platform.console(), "request");
            platform.interactions().http(503, "unavailable");
            platform.scheduler().tick(1);
            assertTrue(platform.logs().contains("SCRIPT info HTTP -1: test failure"));
            platform.command(platform.console(), "request");
            scripts.put("http.tys", "command other {}\n");
            assertTrue(engine.load(scripts).failed().isEmpty());
            platform.scheduler().tick(1);
            assertFalse(platform.logs().contains("SCRIPT info HTTP 503: unavailable"));
            assertEquals(2, platform.interactions().requests().size());
            assertThrows(ScriptError.class, () -> platform.interactions().file("../outside.txt", "x"));
            assertThrows(ScriptError.class, () -> platform.interactions().file("C:/outside.txt", "x"));
        } finally {
            engine.shutdown();
        }
    }
}
