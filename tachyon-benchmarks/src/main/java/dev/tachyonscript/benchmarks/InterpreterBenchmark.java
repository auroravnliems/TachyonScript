package dev.tachyonscript.benchmarks;

import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;
import dev.tachyonscript.runtime.interpreter.Interpreter;
import dev.tachyonscript.testkit.TestPlatform;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Pure computation in the interpreter against the same code in Java. The Java versions are
 * the lower bound a bytecode backend could approach; the gap is the interpreter's cost.
 * {@code Interpreter.call} boxes its argument and result, which is included.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class InterpreterBenchmark {

    private static final String SCRIPT = """
            function sum(n: int): int {
                var total = 0
                var i = 0
                while i < n {
                    total += i * 2 + 1
                    i += 1
                }
                return total
            }

            function fib(n: int): int {
                if n < 2 {
                    return n
                }
                return fib(n - 1) + fib(n - 2)
            }

            function distance(steps: int): double {
                var x = 0.0
                var y = 0.0
                for i in 0..<steps {
                    x += 0.5
                    y += x / 3.0
                }
                return math.sqrt(x * x + y * y)
            }
            """;

    @Param({"1000"})
    public int loopLength;

    @Param({"20"})
    public int fibArgument;

    private CompiledFunction sum;
    private CompiledFunction fib;
    private CompiledFunction distance;

    @Setup
    public void setUp() {
        ScriptEngine engine = Scripts.load(new TestPlatform(), "bench.tys", SCRIPT);
        sum = Scripts.function(engine, "bench.tys", "sum(int)");
        fib = Scripts.function(engine, "bench.tys", "fib(int)");
        distance = Scripts.function(engine, "bench.tys", "distance(int)");
    }

    @Benchmark
    public Object scriptIntegerLoop() {
        return Interpreter.call(sum, loopLength);
    }

    @Benchmark
    public int javaIntegerLoop() {
        int total = 0;
        for (int i = 0; i < loopLength; i++) {
            total += i * 2 + 1;
        }
        return total;
    }

    @Benchmark
    public Object scriptRecursiveCalls() {
        return Interpreter.call(fib, fibArgument);
    }

    @Benchmark
    public int javaRecursiveCalls() {
        return javaFib(fibArgument);
    }

    private static int javaFib(int n) {
        return n < 2 ? n : javaFib(n - 1) + javaFib(n - 2);
    }

    @Benchmark
    public Object scriptDoubleLoop() {
        return Interpreter.call(distance, loopLength);
    }

    @Benchmark
    public double javaDoubleLoop() {
        double x = 0;
        double y = 0;
        for (int i = 0; i < loopLength; i++) {
            x += 0.5;
            y += x / 3.0;
        }
        return Math.sqrt(x * x + y * y);
    }
}
