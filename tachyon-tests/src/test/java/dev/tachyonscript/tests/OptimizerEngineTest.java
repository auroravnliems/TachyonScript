package dev.tachyonscript.tests;

import dev.tachyonscript.compiler.CompilerOptions;
import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.LoadMode;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.storage.MemoryBackend;
import dev.tachyonscript.ir.opt.Optimizer;
import dev.tachyonscript.runtime.ExecutionBackend;
import dev.tachyonscript.runtime.interpreter.RuntimeLimits;
import dev.tachyonscript.stdlib.EventApi;
import dev.tachyonscript.testkit.Fakes;
import dev.tachyonscript.testkit.InMemoryScripts;
import dev.tachyonscript.testkit.TestPlatform;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optimizations cannot move effects across modules, events, scheduling or generation retirement. */
class OptimizerEngineTest {
    private record Observation(List<Object> messages, List<String> scriptLog, boolean cancelled, int pending) { }
    private record Variant(String name, CompilerOptions options) { }

    @TestFactory
    Stream<DynamicTest> engineStateMatchesWithoutOptimization() {
        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant("all", CompilerOptions.DEFAULT));
        for (String pass : Optimizer.passes()) {
            variants.add(new Variant("without-" + pass, CompilerOptions.DEFAULT.withDisabledPasses(Set.of(pass))));
            Set<String> others = new LinkedHashSet<>(Optimizer.passes());
            others.remove(pass);
            variants.add(new Variant("only-" + pass, CompilerOptions.DEFAULT.withDisabledPasses(others)));
        }
        return variants.stream().map(v -> DynamicTest.dynamicTest(v.name(), () ->
                assertEquals(execute(CompilerOptions.DEFAULT.withOptimization(false)), execute(v.options()))));
    }

    /** The bytecode backend runs the same engine scenario with the same observable result. */
    @TestFactory
    Stream<DynamicTest> engineStateMatchesOnTheBytecodeBackend() {
        return Stream.of(CompilerOptions.DEFAULT, CompilerOptions.DEFAULT.withOptimization(false)).map(options ->
                DynamicTest.dynamicTest("optimize=" + options.optimize(), () -> assertEquals(
                        execute(options, ExecutionBackend.INTERPRETER), execute(options, ExecutionBackend.BYTECODE))));
    }

    private static Observation execute(CompilerOptions compiler) {
        return execute(compiler, ExecutionBackend.INTERPRETER);
    }

    private static Observation execute(CompilerOptions compiler, ExecutionBackend backend) {
        TestPlatform platform = new TestPlatform();
        ScriptEngine engine = platform.engine(new EngineOptions(LoadMode.LENIENT, compiler, RuntimeLimits.DEFAULT, 0, false)
                .withStorage(new MemoryBackend(), 0).withBackend(backend));
        InMemoryScripts scripts = new InMemoryScripts();
        scripts.put("bank.tys", """
                module bank
                persistent var count = 0
                function bump(n: int): int { count += n; return count }
                """);
        String consumer = """
                import bank
                playerdata var coins = 10
                on load { log("load {bank.count}") }
                on unload { log("unload {bank.count}") }
                @playerOnly
                command deposit(amount: int) {
                    player.coins += amount
                    let value = bank.bump(amount)
                    sender.send("deposit {value} coins {player.coins}")
                }
                event player.join {
                    let captured = bank.bump(1)
                    after 2 ticks for player { player.send("later {captured} now {bank.count}") }
                    async { let text = "async {captured}"; sync { player.send("{text}") } }
                }
                @priority(LOW)
                event block.break {
                    let previous = bank.count
                    bank.bump(3)
                    event.cancel()
                    player.send("break {previous} now {bank.count}")
                }
                @priority(HIGH)
                @ignoreCancelled
                event block.break { player.send("must be skipped") }
                """;
        scripts.put("consumer.tys", consumer);
        Fakes.Player player = platform.join("Optimizer");
        try {
            load(engine, scripts);
            for (var script : engine.generation().scripts().values()) {
                for (var function : script.linked().functions().values()) {
                    assertEquals(effective(backend), function.backend(), script.path() + " " + function.key());
                }
            }
            platform.command(player, "deposit 4");
            platform.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(player));
            platform.command(player, "deposit 2");
            platform.scheduler().tick(2);
            Fakes.BlockBreakEvent event = new Fakes.BlockBreakEvent(player,
                    new Fakes.Block(player.location(), "minecraft:stone"));
            platform.fire(engine, EventApi.BLOCK_BREAK, event);
            // A scheduled closure from the retired module must never run after reload.
            platform.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(player));
            scripts.put("consumer.tys", consumer + "\n// new generation\n");
            load(engine, scripts);
            platform.scheduler().tick(5);
            platform.command(player, "deposit 1");
            assertEquals(List.of("deposit 4 coins 14", "deposit 7 coins 16", "async 5", "later 5 now 7",
                    "break 7 now 10", "deposit 12 coins 17"), player.messages());
            assertTrue(event.isCancelled());
            assertTrue(platform.logs().stream().noneMatch(line -> line.startsWith("ERROR")), platform.logs()::toString);
            engine.shutdown();
            return new Observation(List.copyOf(player.messages()), platform.logs().stream()
                    .filter(line -> line.startsWith("SCRIPT")).toList(), event.isCancelled(), platform.scheduler().pending());
        } finally {
            engine.shutdown();
        }
    }

    /** The backend actually used: {@code -Ptachyon.backend} runs every test engine on one backend. */
    private static ExecutionBackend effective(ExecutionBackend requested) {
        String forced = System.getProperty("tachyon.test.backend", "");
        return forced.isBlank() ? requested : ExecutionBackend.valueOf(forced.toUpperCase(java.util.Locale.ROOT));
    }

    private static void load(ScriptEngine engine, InMemoryScripts scripts) {
        var report = engine.load(scripts);
        assertTrue(report.activated() && report.failed().isEmpty(), () -> report.diagnostics() + " " + report.linkProblems());
    }
}
