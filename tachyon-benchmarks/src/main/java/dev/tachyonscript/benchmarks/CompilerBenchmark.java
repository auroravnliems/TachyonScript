package dev.tachyonscript.benchmarks;

import dev.tachyonscript.compiler.CompilationResult;
import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.stdlib.StandardLibrary;
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

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Full compilation (lexing to verified IR) of a script with 40 functions and 40 handlers. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class CompilerBenchmark {

    private Compiler compiler;
    private List<SourceFile> files;

    @Setup
    public void setUp() {
        StringBuilder source = new StringBuilder("const PREFIX = \"<gold>[Server]</gold> \"\n\n");
        for (int i = 0; i < 40; i++) {
            source.append("""
                    /// Heals a player and reports it.
                    function heal%1$d(target: Player, amount: double): bool {
                        if target.health >= target.maxHealth {
                            return false
                        }
                        target.health = math.min(target.maxHealth, target.health + amount)
                        target.send("{PREFIX}<green>Healed by {amount} ({target.health}/{target.maxHealth})")
                        return true
                    }

                    event player.join {
                        let names: List<string> = []
                        for other in server.players {
                            if other != player && other.hasPermission("staff.%1$d") {
                                names.add(other.name)
                            }
                        }
                        if heal%1$d(player, 2.5) {
                            log.info("healed {player.name}, staff online: {names.size}")
                        }
                    }

                    """.formatted(i));
        }
        files = List.of(new SourceFile("large.tys", source.toString()));
        compiler = new Compiler(StandardLibrary.registry());
        CompilationResult check = compiler.compile(files);
        if (!check.succeeded()) {
            throw new IllegalStateException(check.diagnostics().sorted().toString());
        }
    }

    @Benchmark
    public CompilationResult compileScript() {
        return compiler.compile(files);
    }
}
