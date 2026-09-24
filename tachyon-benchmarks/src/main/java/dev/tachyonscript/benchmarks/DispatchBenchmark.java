package dev.tachyonscript.benchmarks;

import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.stdlib.EventApi;
import dev.tachyonscript.testkit.Fakes;
import dev.tachyonscript.testkit.TestPlatform;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Cost of running event handlers through the engine, against the same logic written as a
 * plain Java listener body. The fakes stand in for Bukkit objects; their getters are as
 * cheap as the server's, so the difference is the engine and interpreter overhead.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class DispatchBenchmark {

    private static final String SCRIPT = """
            const LOW_HEALTH = 6.0

            event player.move {
                if player.health < LOW_HEALTH && player.food > 0 {
                    player.send("<red>You are hurt!")
                }
            }
            """;

    private ScriptEngine engine;
    private int moveIndex;
    private int quitIndex;
    private Fakes.Player player;
    private Fakes.MoveEvent move;
    private Fakes.QuitEvent quit;

    @Setup
    public void setUp() {
        TestPlatform platform = new TestPlatform();
        engine = Scripts.load(platform, "move.tys", SCRIPT);
        moveIndex = engine.registry().eventIndex(EventApi.PLAYER_MOVE);
        quitIndex = engine.registry().eventIndex(EventApi.PLAYER_QUIT);
        player = platform.join("Steve");
        move = new Fakes.MoveEvent(player, player.location(), player.location());
        quit = new Fakes.QuitEvent(player);
    }

    /** One handler that reads two properties and does not send (the common case on move). */
    @Benchmark
    public void scriptHandler() {
        engine.dispatch(moveIndex, move);
    }

    @Benchmark
    public void javaHandler(Blackhole blackhole) {
        Fakes.Player mover = move.player;
        if (mover.health() < 6.0 && mover.food() > 0) {
            blackhole.consume("<red>You are hurt!");
        }
    }

    /** An event without script handlers: what the engine costs when nothing listens. */
    @Benchmark
    public void eventWithoutHandlers() {
        engine.dispatch(quitIndex, quit);
    }
}
