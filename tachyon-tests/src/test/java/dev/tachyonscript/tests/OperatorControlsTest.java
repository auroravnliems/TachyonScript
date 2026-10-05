package dev.tachyonscript.tests;

import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.LoadReport;
import dev.tachyonscript.engine.ScriptControls;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.runtime.error.ScriptRuntimeException;
import dev.tachyonscript.runtime.interpreter.Interpreter;
import dev.tachyonscript.testkit.InMemoryScripts;
import dev.tachyonscript.testkit.TestPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OperatorControlsTest {
    @TempDir Path directory;

    @Test
    void failedPersistenceStillStopsExecutionAndFailedEnableKeepsTheGateClosed() throws Exception {
        Path parent = directory.resolve("unwritable-parent");
        ScriptControls controls = new ScriptControls(parent.resolve("disabled.properties"));
        // Portable I/O failure: a regular file cannot be used as the state directory.
        java.nio.file.Files.writeString(parent, "not a directory");
        TestPlatform platform = new TestPlatform();
        ScriptEngine engine = platform.engine();
        engine.controls(controls);
        InMemoryScripts sources = new InMemoryScripts().put("shop.tys",
                "function value(): int { return 5 }\nevery 1 second { log(\"reward\") }");
        try {
            clean(engine.load(sources));
            var exported = engine.generation().scripts().get("shop.tys").linked().function("value()").orElseThrow();
            assertThrows(java.io.IOException.class, () -> engine.disable(Set.of("shop.tys"), false));
            assertFalse(controls.allowed("shop.tys"));
            assertThrows(ScriptRuntimeException.class, () -> Interpreter.call(exported));
            assertEquals(0, platform.scheduler().pending());
            long revision = controls.revision();
            assertThrows(java.io.IOException.class, () -> controls.enable(Set.of("shop.tys"), false));
            assertFalse(controls.allowed("shop.tys"));
            assertTrue(controls.revision() > revision);
            clean(engine.load(sources));
            assertTrue(engine.generation().scripts().isEmpty());
        } finally { engine.shutdown(); }
    }

    private static void clean(LoadReport report) {
        assertTrue(report.activated(), () -> report.toString());
        assertEquals(List.of(), report.failed(), () -> report.diagnostics() + " " + report.linkProblems());
    }

    @Test
    void targetedReloadPreservesUnrelatedEditsDeletionsAdditionsVariablesAndTimers() {
        TestPlatform platform = new TestPlatform();
        ScriptEngine engine = platform.engine();
        InMemoryScripts sources = new InMemoryScripts()
                .put("shop.tys", "on load { log(\"old\") }")
                .put("clock.tys", "var count = 0\nevery 1 second { count += 1 }\nfunction value(): int { return count }");
        try {
            clean(engine.load(sources));
            var clock = engine.generation().scripts().get("clock.tys");
            platform.scheduler().tick(20);
            sources.put("shop.tys", "on load { log(\"new\") }")
                    .put("clock.tys", "broken ! ! !")
                    .put("new.tys", "on load { log(\"must not load\") }");
            LoadReport report = engine.reload(sources, Set.of("shop.tys"));
            clean(report);
            assertEquals(1, report.compiled());
            assertSame(clock, engine.generation().scripts().get("clock.tys"));
            assertFalse(engine.generation().scripts().containsKey("new.tys"));
            assertEquals(1, Interpreter.call(clock.linked().function("value()").orElseThrow()));
            sources.remove("clock.tys");
            clean(engine.reload(sources, Set.of("shop.tys")));
            assertSame(clock, engine.generation().scripts().get("clock.tys"));
            platform.scheduler().tick(20);
            assertEquals(2, Interpreter.call(clock.linked().function("value()").orElseThrow()));
        } finally { engine.shutdown(); }
    }

    @Test
    void importersUseTheirActiveSourcesAndRollbackTogetherOnIncompatibleChanges() {
        TestPlatform platform = new TestPlatform();
        ScriptEngine engine = platform.engine();
        InMemoryScripts sources = new InMemoryScripts().put("prices.tys", "function price(): int { return 1 }")
                .put("shop.tys", "import prices\nfunction value(): int { return prices.price() }")
                .put("other.tys", "on load { log(\"other\") }");
        try {
            clean(engine.load(sources));
            var other = engine.generation().scripts().get("other.tys");
            sources.put("prices.tys", "function price(): int { return 2 }").put("shop.tys", "broken ! ! !");
            clean(engine.reload(sources, Set.of("prices.tys")));
            var shop = engine.generation().scripts().get("shop.tys");
            var prices = engine.generation().scripts().get("prices.tys");
            assertEquals(2, Interpreter.call(shop.linked().function("value()").orElseThrow()));
            assertSame(other, engine.generation().scripts().get("other.tys"));
            sources.put("prices.tys", "function price(): string { return \"incompatible\" }");
            assertFalse(engine.reload(sources, Set.of("prices.tys")).activated());
            assertSame(shop, engine.generation().scripts().get("shop.tys"));
            assertSame(prices, engine.generation().scripts().get("prices.tys"));
            assertEquals(2, Interpreter.call(shop.linked().function("value()").orElseThrow()));
        } finally { engine.shutdown(); }
    }

    @Test
    void emergencyStopCancelsTasksRevokesExportsSkipsUnloadAndPersistsWithDependents() throws Exception {
        Path state = directory.resolve("disabled.properties");
        TestPlatform platform = new TestPlatform();
        ScriptEngine engine = platform.engine();
        engine.controls(new ScriptControls(state));
        InMemoryScripts sources = new InMemoryScripts().put("economy.tys", """
                function price(): int { return 5 }
                on unload { log("unload must not pay rewards") }
                every 1 second { log("reward") }
                command reward { log("command reward") }
                """).put("shop.tys", "import economy\nfunction price(): int { return economy.price() }")
                .put("chat.tys", "on load { log(\"chat\") }");
        try {
            clean(engine.load(sources));
            var exported = engine.generation().scripts().get("economy.tys").linked().function("price()").orElseThrow();
            var chat = engine.generation().scripts().get("chat.tys");
            assertEquals(Set.of("economy.tys", "shop.tys"), engine.disable(Set.of("economy.tys"), false));
            assertThrows(ScriptRuntimeException.class, () -> Interpreter.call(exported));
            assertEquals(0, platform.scheduler().pending());
            assertEquals(Set.of("chat.tys"), engine.generation().scripts().keySet());
            assertFalse(platform.logs().stream().anyMatch(line -> line.contains("unload must not")));
            clean(engine.load(sources));
            assertSame(chat, engine.generation().scripts().get("chat.tys"));
            ScriptControls restored = new ScriptControls(state);
            assertFalse(restored.allowed("economy.tys"));
            assertFalse(restored.allowed("shop.tys"));
            engine.controls().enable(Set.of("economy.tys"), false);
            clean(engine.reload(sources, Set.of("economy.tys")));
            assertThrows(ScriptRuntimeException.class, () -> Interpreter.call(exported));
            assertFalse(engine.generation().scripts().containsKey("shop.tys"));
        } finally { engine.shutdown(); }
    }

    @Test
    void globalStopAlsoBlocksNewFilesAndSurvivesRestartUntilExplicitlyEnabled() throws Exception {
        Path state = directory.resolve("all.properties");
        TestPlatform platform = new TestPlatform();
        ScriptEngine engine = platform.engine();
        engine.controls(new ScriptControls(state));
        InMemoryScripts sources = new InMemoryScripts().put("a.tys", "on load { log(\"a\") }");
        try {
            clean(engine.load(sources));
            engine.disable(Set.of(), true);
            sources.put("new.tys", "on load { log(\"new\") }");
            clean(engine.load(sources));
            assertTrue(engine.generation().scripts().isEmpty());
        } finally { engine.shutdown(); }
        ScriptEngine restarted = new TestPlatform().engine();
        try {
            restarted.controls(new ScriptControls(state));
            clean(restarted.load(sources));
            assertTrue(restarted.generation().scripts().isEmpty());
            assertThrows(IllegalStateException.class, () -> restarted.controls().enable(Set.of("a.tys"), false));
            restarted.controls().enable(Set.of(), true);
            clean(restarted.load(sources));
            assertEquals(2, restarted.generation().scripts().size());
        } finally { restarted.shutdown(); }
    }

    @Test
    void disableDuringReloadCannotReactivateThePreparedVersion() {
        ScriptEngine engine = new TestPlatform().engine();
        InMemoryScripts sources = new InMemoryScripts().put("shop.tys", "on load { log(\"shop\") }");
        try {
            clean(engine.load(sources));
            sources.put("shop.tys", "on load { log(\"replacement\") }");
            AtomicInteger reads = new AtomicInteger();
            engine.load(() -> {
                if (reads.incrementAndGet() == 2) engine.disable(Set.of("shop.tys"), false);
                return sources.read();
            });
            assertFalse(engine.controls().allowed("shop.tys"));
            assertTrue(engine.generation().scripts().isEmpty());
        } finally { engine.shutdown(); }
    }

    @Test
    void defaultWarningsAreOffAndCanBeChangedWithoutReplacingScripts() {
        ScriptEngine engine = new TestPlatform().engine();
        try {
            assertEquals(0, EngineOptions.DEFAULT.slowThresholdNanos());
            clean(engine.load(new InMemoryScripts().put("a.tys", "on load { log(\"a\") }")));
            var generation = engine.generation();
            engine.slowWarnings(50_000_000);
            engine.startProfiling();
            engine.slowWarnings(0);
            engine.stopProfiling();
            assertSame(generation, engine.generation());
        } finally { engine.shutdown(); }
    }
}
