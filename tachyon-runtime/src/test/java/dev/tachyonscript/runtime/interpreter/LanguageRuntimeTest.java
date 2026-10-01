package dev.tachyonscript.runtime.interpreter;

import dev.tachyonscript.runtime.error.ScriptRuntimeException;
import dev.tachyonscript.runtime.link.LinkedModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the language features of version 0.2 through the whole pipeline (compiler, assembler, linker, interpreter). */
class LanguageRuntimeTest {

    @BeforeEach
    void reset() {
        ScriptHarness.LOG.clear();
        ExecutionStack.configure(RuntimeLimits.DEFAULT);
    }

    @AfterEach
    void restoreLimits() {
        ExecutionStack.configure(RuntimeLimits.DEFAULT);
    }

    /** Loads a module and runs its top-level variable initializer. */
    private static LinkedModule start(String source) {
        LinkedModule module = ScriptHarness.load(source);
        module.function("$init").ifPresent(Interpreter::call);
        return module;
    }

    private static Object call(LinkedModule module, String function, Object... arguments) {
        return Interpreter.call(module.function(function).orElseThrow(() -> new AssertionError("no " + function
                + " in " + module.functions().keySet())), arguments);
    }

    @Test
    void lambdasCaptureValuesAndRunInListOperations() {
        LinkedModule module = start("""
                function run(limit: int): string {
                    let numbers = [5, 1, 8, 3, 9, 2]
                    let bonus = 1
                    let big = numbers.filter(n => n + bonus > limit)
                    let doubled = big.map(n => n * 2)
                    let sorted = doubled.sorted()
                    let total = numbers.sum()
                    let names = ["b", "a", "c"].sortedBy(s => s)
                    return "{sorted} {total} {names.join("-")} {numbers.any(n => n > 8)} {numbers.count(n => n % 2 == 0)}"
                }
                """);
        assertEquals("[10, 16, 18] 28 a-b-c true 2", call(module, "run(int)", 4));
    }

    @Test
    void lambdasInFinallyCompileOnceAndRunOnEveryExit() {
        LinkedModule module = start("""
                function run(n: int): int {
                    try {
                        if n == 0 { return 10 }
                        if n == 1 { throw "expected" }
                    } catch e {
                        log(e.message)
                        return 20
                    } finally {
                        [n].forEach(x => log("clean {x}"))
                    }
                    return 30
                }
                """);
        assertEquals(10, call(module, "run(int)", 0));
        assertEquals(20, call(module, "run(int)", 1));
        assertEquals(30, call(module, "run(int)", 2));
        assertEquals(List.of("clean 0", "expected", "clean 1", "clean 2"), ScriptHarness.LOG);
    }

    @Test
    void functionValuesCanBeStoredPassedAndCalled() {
        LinkedModule module = start("""
                function twice(f: function(int): int, x: int): int {
                    return f(f(x))
                }
                function square(x: int): int {
                    return x * x
                }
                function adder(amount: int): function(int): int {
                    return x => x + amount
                }
                function run(): int {
                    let add3 = adder(3)
                    let nested = (a: int) => (b: int) => a * b
                    let times4 = nested(4)
                    return twice(add3, 1) + twice(square, 2) + times4(5)
                }
                """);
        assertEquals(7 + 16 + 20, call(module, "run()"));
    }

    @Test
    void listLoopsWalkASnapshotSoTheBodyMayChangeTheList() {
        LinkedModule module = start("""
                function run(): string {
                    let grow = [1, 2]
                    var seen = ""
                    for x in grow {
                        grow.add(x * 10)
                        seen += "{x} "
                    }
                    let shrink = [1, 2, 3, 4]
                    var visited = 0
                    for x in shrink {
                        shrink.remove(x)
                        visited++
                    }
                    return "{seen}{grow} {visited} {shrink}"
                }
                """);
        assertEquals("1 2 [1, 2, 10, 20] 4 []", call(module, "run()"));
    }

    @Test
    void listsAndMapsInTextShowTheirElementsLikeTemplatesDo() {
        LinkedModule module = start("""
                function run(): string {
                    let times = [1 second, 90 seconds]
                    let nested = [[1 second], [2 minutes, 3 hours]]
                    let limits = {"kit": 1 day, "home": 30 seconds}
                    let plain = [1, 2.5]
                    return "{times} {nested} {limits} {plain} " + times
                }
                """);
        assertEquals("[1s, 1m 30s] [[1s], [2m, 3h]] {kit: 1d, home: 30s} [1, 2.5] [1s, 1m 30s]", call(module, "run()"));
    }

    @Test
    void mapsSupportLiteralsIndexingIterationAndAtomicUpdates() {
        LinkedModule module = start("""
                function run(): string {
                    let prices = {"diamond": 100, "gold": 20}
                    prices["iron"] = 5
                    prices["iron"] += 10
                    prices["gold"] -= 5
                    var total = 0
                    var keys = ""
                    for name, price in prices {
                        total += price
                        keys += name
                    }
                    let missing = prices["emerald"] ?? -1
                    return "{total} {keys} {missing} {prices.size} {"gold" in prices} {prices.keysSortedByValue()}"
                }
                """);
        assertEquals("130 diamondgoldiron -1 3 true [gold, iron, diamond]", call(module, "run()"));
    }

    @Test
    void topLevelVariablesKeepStateAndInitializeInOrder() {
        LinkedModule module = start("""
                let base = 10
                var counter = base * 2
                var names: List<string> = []
                function bump(name: string): int {
                    counter += 1
                    names.add(name)
                    return counter
                }
                function summary(): string {
                    return "{counter} {names}"
                }
                """);
        assertEquals(21, call(module, "bump(string)", "a"));
        assertEquals(22, call(module, "bump(string)", "b"));
        assertEquals("22 [a, b]", call(module, "summary()"));
        assertEquals(22, module.globals().get("counter").boxed());
    }

    @Test
    void recordsCompareByContentAndHaveMethods() {
        LinkedModule module = start("""
                record Point(x: int, y: int = 0) {
                    function plus(other: Point): Point {
                        return Point(x + other.x, y + other.y)
                    }
                    function label(): string {
                        return "({x}, {y})"
                    }
                }
                function run(): string {
                    let a = Point(1, 2)
                    let b = Point(3)
                    let c = a.plus(b)
                    let any: any = c
                    let same = c == Point(4, 2)
                    let isPoint = any is Point
                    return "{c.label()} {same} {isPoint} {c}"
                }
                """);
        assertEquals("(4, 2) true true Point(x=4, y=2)", call(module, "run()"));
    }

    @Test
    void tryCatchFinallyHandlesErrorsAndEarlyExits() {
        LinkedModule module = start("""
                var log = ""
                function risky(n: int): int {
                    if n < 0 {
                        throw "negative: {n}"
                    }
                    return 100 / n
                }
                function safe(n: int): string {
                    try {
                        return "ok {risky(n)}"
                    } catch e {
                        return "caught {e.kind}: {e.message}"
                    } finally {
                        log += "[{n}]"
                    }
                }
                function loop(): int {
                    var sum = 0
                    for i in 0..5 {
                        try {
                            if i == 1 {
                                continue
                            }
                            if i == 4 {
                                break
                            }
                            sum += 10 / (2 - i)
                        } catch {
                            sum += 1000
                        } finally {
                            sum += 1
                        }
                    }
                    return sum
                }
                function rethrow(): string {
                    try {
                        try {
                            risky(-1)
                        } catch e {
                            throw e
                        }
                    } catch outer {
                        return outer.message + " at " + outer.location
                    }
                    return "unreachable"
                }
                function logText(): string {
                    return log
                }
                """);
        assertEquals("ok 20", call(module, "safe(int)", 5));
        assertEquals("caught division: Division by zero.", call(module, "safe(int)", 0));
        assertEquals("caught thrown: negative: -3", call(module, "safe(int)", -3));
        assertEquals("[5][0][-3]", call(module, "logText()"));
        // i=0: 5+1, i=1: continue (+1), i=2: division by zero caught (+1000+1), i=3: 10/-1=-10 +1, i=4: break (+1)
        assertEquals(5 + 1 + 1 + 1000 + 1 - 10 + 1 + 1, call(module, "loop()"));
        String rethrown = (String) call(module, "rethrow()");
        assertTrue(rethrown.startsWith("negative: -1 at test.tys:"), rethrown);
    }

    @Test
    void uncaughtThrowReportsTheScriptLocation() {
        LinkedModule module = start("""
                function fail() {
                    throw "Not enough money"
                }
                """);
        ScriptRuntimeException error = assertThrows(ScriptRuntimeException.class, () -> call(module, "fail()"));
        assertEquals(ScriptRuntimeException.Kind.THROWN, error.kind());
        assertEquals("Not enough money", error.getMessage());
        assertEquals(2, error.frames().getFirst().line());
    }

    @Test
    void switchConditionalBitwiseAndMembership() {
        LinkedModule module = start("""
                function classify(n: int): string {
                    let size = switch n {
                        case 0 -> "zero"
                        case 1, 2, 3 -> "small"
                        default -> n > 100 ? "huge" : "big"
                    }
                    var flags = 0
                    switch n % 3 {
                        case 0 -> flags |= 1
                        case 1 -> {
                            flags |= 2
                            flags <<= 1
                        }
                        default -> flags = ~flags
                    }
                    let inRange = n in 10..20
                    return "{size} {flags} {inRange} {n & 6} {n ^ 5} {n >> 1} {-8 >>> 28}"
                }
                """);
        assertEquals("zero 1 false 0 5 0 15", call(module, "classify(int)", 0));
        assertEquals("small 4 false 0 4 0 15", call(module, "classify(int)", 1));
        assertEquals("big -1 true 6 11 7 15", call(module, "classify(int)", 14));
        assertEquals("huge 1 false 6 99 51 15", call(module, "classify(int)", 102));
    }

    @Test
    void incrementsDefaultParametersAndConditionals() {
        LinkedModule module = start("""
                function greet(name: string, times: int = 2, loud: bool = false): string {
                    var text = ""
                    var i = 0
                    while i < times {
                        text += loud ? name.upper() : name
                        i++
                    }
                    return text
                }
                function run(): string {
                    return greet("a") + "|" + greet("b", 3) + "|" + greet("c", 1, true)
                }
                """);
        assertEquals("aa|bbb|C", call(module, "run()"));
    }

    @Test
    void listOperationsCoverTheWholeApi() {
        LinkedModule module = start("""
                function run(): string {
                    let list = [4, 2, 7]
                    list.insert(1, 9)
                    list.remove(7)
                    let removed = list.removeAt(0)
                    list.addAll([5, 5])
                    let copy = list.copy()
                    copy.reverse()
                    list.sort()
                    let parts = [list.first() ?? 0, list.last() ?? 0, list.indexOf(5), list.lastIndexOf(5)]
                    let groups = ["apple", "avocado", "banana"].groupBy(s => s.length)
                    let byFirst = ["apple", "banana"].associateBy(s => s.length)
                    return "{removed} {list} {copy} {parts} {list.distinct()} {list.take(2)} {list.drop(3)} "
                        + "{list.subList(1, 3)} {list.min()} {list.max()} {list.average()} {groups} {byFirst} "
                        + "{list.maxBy(n => -n)} {list.findIndex(n => n > 4)} {list.find(n => n > 100) ?? -1}"
                }
                """);
        assertEquals("4 [2, 5, 5, 9] [5, 5, 2, 9] [2, 9, 1, 2] [2, 5, 9] [2, 5] [9] [5, 5] 2 9 5.25 "
                        + "{5: [apple], 7: [avocado], 6: [banana]} {5: apple, 6: banana} 2 1 -1",
                call(module, "run()"));
    }

    @Test
    void capturedLoopVariablesAreCopiedPerIteration() {
        LinkedModule module = start("""
                function run(): string {
                    let functions: List<function(): int> = []
                    for i in 1..3 {
                        functions.add(() => i * 10)
                    }
                    var out = ""
                    for f in functions {
                        out += "{f()} "
                    }
                    return out
                }
                """);
        assertEquals("10 20 30 ", call(module, "run()"));
    }

    @Test
    void errorsInLambdasPropagateThroughNatives() {
        LinkedModule module = start("""
                function run(): string {
                    try {
                        [1, 0, 2].forEach(n => ScriptProbe(10 / n))
                    } catch e {
                        return "caught {e.message}"
                    }
                    return "none"
                }
                function ScriptProbe(n: int) {
                }
                """);
        assertEquals("caught Division by zero.", call(module, "run()"));
        List<String> log = ScriptHarness.LOG;
        assertEquals(List.of(), log);
    }
}
