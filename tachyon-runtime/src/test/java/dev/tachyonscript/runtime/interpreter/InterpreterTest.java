package dev.tachyonscript.runtime.interpreter;

import dev.tachyonscript.runtime.error.ScriptRuntimeException;
import dev.tachyonscript.runtime.link.LinkedModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterpreterTest {

    @BeforeEach
    void reset() {
        ScriptHarness.LOG.clear();
        ExecutionStack.configure(RuntimeLimits.DEFAULT);
    }

    @AfterEach
    void restoreLimits() {
        ExecutionStack.configure(RuntimeLimits.DEFAULT);
    }

    @Test
    void runsArithmeticLoopsAndConditions() {
        String source = """
                function sum(n: int): int {
                    var total = 0
                    for i in 1..n {
                        if i % 3 == 0 {
                            continue
                        }
                        total += i
                    }
                    return total
                }
                function countdown(n: int): int {
                    var steps = 0
                    var x = n
                    while x > 0 {
                        x -= 1
                        steps += 1
                        if steps > 1000 {
                            break
                        }
                    }
                    return steps
                }
                """;
        assertEquals(37, ScriptHarness.run(source, "sum(int)", 10));
        assertEquals(7, ScriptHarness.run(source, "countdown(int)", 7));
        assertEquals(0, ScriptHarness.run(source, "sum(int)", 0));
    }

    @Test
    void inclusiveRangeEndingAtMaxIntTerminates() {
        String source = """
                function tail(): int {
                    var count = 0
                    for i in 2147483645..2147483647 {
                        count += 1
                    }
                    return count
                }
                """;
        assertEquals(3, ScriptHarness.run(source, "tail()"));
    }

    @Test
    void runsRecursionAndMixedNumericTypes() {
        String source = """
                function fib(n: int): int {
                    if n < 2 {
                        return n
                    }
                    return fib(n - 1) + fib(n - 2)
                }
                function mix(a: int, b: double, c: long, f: float): double {
                    return a + b + c + f + math.sqrt(16.0) + (7 / 2) + (7 % 3)
                }
                function convert(d: double): long {
                    return (d as int) + (d as long) * 2L
                }
                """;
        assertEquals(6765, ScriptHarness.run(source, "fib(int)", 20));
        assertEquals(1 + 2.5 + 3L + 0.5 + 4.0 + 3 + 1, ScriptHarness.run(source, "mix(int, double, long, float)", 1, 2.5, 3L, 0.5f));
        assertEquals(9L, ScriptHarness.run(source, "convert(double)", 3.9));
    }

    @Test
    void buildsStringsAndMessages() {
        String source = """
                const PREFIX = "<gold>[S]</gold> "
                function greet(name: string, health: double): string {
                    return "Hello {name}, health {health}, half {health / 2}, ok {health > 5}"
                }
                function message(name: string): Component {
                    return "{PREFIX}Hi <red>{name}"
                }
                function constant(): Component {
                    return "<green>static"
                }
                function logIt(n: int) {
                    log("n=" + n + ", big=" + 3000000000L + ", time=" + 90 seconds)
                }
                """;
        assertEquals("Hello Steve, health 20, half 10, ok true", ScriptHarness.run(source, "greet(string, double)", "Steve", 20.0));
        assertEquals("<gold>[S]</gold> Hi <red>\\<b>Steve", ScriptHarness.run(source, "message(string)", "<b>Steve"),
                "interpolated values are inserted as plain text");
        assertEquals("<green>static", ScriptHarness.run(source, "constant()"));
        ScriptHarness.run(source, "logIt(int)", 5);
        assertEquals(List.of("n=5, big=3000000000, time=1m 30s"), ScriptHarness.LOG);
    }

    @Test
    void handlesListsAndNulls() {
        String source = """
                function lists(): int {
                    let values: List<int> = [3, 1, 4]
                    values.add(1)
                    values[1] = 10
                    var total = 0
                    for v in values {
                        total += v
                    }
                    if values.contains(4) && !values.isEmpty {
                        total += 1000
                    }
                    return total + values.size
                }
                function nulls(name: string): string {
                    let player = server.player(name)
                    return player?.name ?? "offline"
                }
                function parse(text: string): int {
                    return text.toInt() ?? -1
                }
                """;
        assertEquals(3 + 10 + 4 + 1 + 1000 + 4, ScriptHarness.run(source, "lists()"));
        assertEquals("offline", ScriptHarness.run(source, "nulls(string)", "Notch"));
        assertEquals(42, ScriptHarness.run(source, "parse(string)", "42"));
        assertEquals(-1, ScriptHarness.run(source, "parse(string)", "x"));
    }

    @Test
    void reportsRuntimeErrorsWithScriptStackTraces() {
        String source = """
                function ratio(a: int, b: int): int {
                    return a / b
                }

                function outer(b: int): int {
                    return ratio(10, b) + 1
                }
                """;
        ScriptRuntimeException error = assertThrows(ScriptRuntimeException.class,
                () -> ScriptHarness.run(source, "outer(int)", 0));
        assertEquals(ScriptRuntimeException.Kind.DIVISION_BY_ZERO, error.kind());
        String rendered = error.render(false);
        assertTrue(rendered.startsWith("TachyonRuntimeError: Division by zero."), rendered);
        assertTrue(rendered.contains("at test.tys:2 (function ratio)"), rendered);
        assertTrue(rendered.contains("return a / b"), rendered);
        assertTrue(rendered.contains("^^^^^"), rendered);
        assertTrue(rendered.contains("at test.tys:6 (function outer)"), rendered);
        assertEquals(2, error.frames().size());
    }

    @Test
    void reportsNativeErrorsAndIndexErrors() {
        String source = """
                function clamp(): int {
                    return math.clamp(5, 10, 0)
                }
                function index(): int {
                    let values = [1, 2]
                    return values[5]
                }
                """;
        ScriptRuntimeException nativeError = assertThrows(ScriptRuntimeException.class,
                () -> ScriptHarness.run(source, "clamp()"));
        assertEquals(ScriptRuntimeException.Kind.SCRIPT, nativeError.kind());
        assertTrue(nativeError.getMessage().contains("min (10) is greater than max (0)"), nativeError.getMessage());
        ScriptRuntimeException indexError = assertThrows(ScriptRuntimeException.class,
                () -> ScriptHarness.run(source, "index()"));
        assertEquals("List index 5 is out of bounds (size 2).", indexError.getMessage());
    }

    @Test
    void stopsRunawayLoops() {
        ExecutionStack.configure(new RuntimeLimits(128, 50_000_000L, 1024));
        String source = """
                function spin(): int {
                    var x = 0
                    while true {
                        x += 1
                    }
                }
                """;
        long start = System.nanoTime();
        ScriptRuntimeException error = assertThrows(ScriptRuntimeException.class, () -> ScriptHarness.run(source, "spin()"));
        assertEquals(ScriptRuntimeException.Kind.TIMEOUT, error.kind());
        assertTrue(System.nanoTime() - start < 5_000_000_000L, "the watchdog fires promptly");
        assertTrue(error.render(false).contains("x += 1") || error.render(false).contains("while true"), error.render(false));
    }

    @Test
    void limitsRecursionDepth() {
        String source = """
                function forever(n: int): int {
                    return forever(n + 1)
                }
                """;
        ScriptRuntimeException error = assertThrows(ScriptRuntimeException.class,
                () -> ScriptHarness.run(source, "forever(int)", 0));
        assertEquals(ScriptRuntimeException.Kind.RECURSION, error.kind());
        assertEquals(0, ExecutionStack.current().depth(), "the stack is reset after an error");
    }

    @Test
    void clearsReferenceSlotsAfterExecution() {
        LinkedModule module = ScriptHarness.load("""
                function keep(name: string): string {
                    let copy = name
                    return copy.upper()
                }
                function fail(name: string): int {
                    let copy = name
                    return 1 / (copy.length - copy.length)
                }
                """);
        Interpreter.call(module.function("keep(string)").orElseThrow(), "steve");
        assertThrows(ScriptRuntimeException.class, () -> Interpreter.call(module.function("fail(string)").orElseThrow(), "alex"));
        ExecutionStack stack = ExecutionStack.current();
        for (Object slot : stack.references) {
            assertNull(slot, "no reference may survive an execution");
        }
        assertNull(stack.returnReference);
    }
}
