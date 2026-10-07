package dev.tachyonscript.runtime.interpreter;

import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.compiler.CompilationResult;
import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.compiler.CompilerOptions;
import dev.tachyonscript.compiler.InternalErrorHandler;
import dev.tachyonscript.ir.IrModule;
import dev.tachyonscript.ir.IrPrinter;
import dev.tachyonscript.ir.opt.Optimizer;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.runtime.code.Assembler;
import dev.tachyonscript.runtime.error.ScriptRuntimeException;
import dev.tachyonscript.runtime.link.LinkedModule;
import dev.tachyonscript.runtime.link.Linker;
import dev.tachyonscript.runtime.spi.SimpleTextService;
import dev.tachyonscript.stdlib.ServerApi;
import dev.tachyonscript.stdlib.StandardLibrary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-to-runtime equivalence, with independently linked state for every pipeline variant. */
class OptimizerDifferentialTest {
    private static final CompilerOptions UNOPTIMIZED = CompilerOptions.DEFAULT.withOptimization(false);
    private static final int[] INPUTS = {-2, 0, 1, 3, 7};
    private static final long SEED = 0x54414348594f4eL;

    private record Variant(String name, CompilerOptions options) { }
    record Fixture(String name, String source) { }
    private record Step(Object result, List<String> trace, Map<String, String> globals) { }

    @AfterEach
    void restoreLimits() {
        ExecutionStack.configure(RuntimeLimits.DEFAULT);
    }

    private static List<Variant> variants() {
        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant("all", CompilerOptions.DEFAULT));
        for (String pass : Optimizer.passes()) {
            variants.add(new Variant("without-" + pass, CompilerOptions.DEFAULT.withDisabledPasses(Set.of(pass))));
            Set<String> otherPasses = new LinkedHashSet<>(Optimizer.passes());
            otherPasses.remove(pass);
            variants.add(new Variant("only-" + pass, CompilerOptions.DEFAULT.withDisabledPasses(otherPasses)));
        }
        return variants;
    }

    private static IrModule compile(String source, CompilerOptions options) {
        CompilationResult compiled = new Compiler(StandardLibrary.registry(), options, InternalErrorHandler.IGNORE)
                .compile(List.of(new SourceFile("differential.tys", source)));
        assertTrue(compiled.succeeded(), () -> compiled.diagnostics().diagnostics().stream()
                .map(DiagnosticRenderer.plain()::render).reduce("", String::concat) + "\n" + source);
        return compiled.modules().getFirst().ir();
    }

    private static List<Step> execute(IrModule ir, int... inputs) throws Exception {
        List<String> trace = new ArrayList<>();
        Bindings bindings = Bindings.builder().include(StandardLibrary.coreBindings())
                .bind(ServerApi.LOG_STRING, (NativeFunction.OfVoid) a -> trace.add(a.getString(0))).build();
        LinkedModule module = Linker.link(Assembler.assemble(ir), bindings, SimpleTextService.INSTANCE);
        module.function("$init").ifPresent(Interpreter::call);
        List<Step> steps = new ArrayList<>();
        for (int input : inputs) {
            Object result;
            try {
                result = Interpreter.call(module.function("run(int)").orElseThrow(), input);
            } catch (ScriptRuntimeException error) {
                result = error.kind() + "\n" + error.render(false);
            }
            Map<String, String> globals = new LinkedHashMap<>();
            module.globals().forEach((name, cell) -> globals.put(name, String.valueOf(cell.boxed())));
            steps.add(new Step(result, List.copyOf(trace), Map.copyOf(globals)));
            assertEquals(0, ExecutionStack.current().depth());
            for (Object slot : ExecutionStack.current().references) {
                assertNull(slot, "execution must release reference slots, also after errors");
            }
        }
        return steps;
    }

    private static void compare(Fixture fixture, boolean inspectPasses) throws Exception {
        ExecutionStack.configure(RuntimeLimits.DEFAULT);
        IrModule original = compile(fixture.source(), UNOPTIMIZED);
        List<Step> expected = execute(original, INPUTS);
        for (Variant variant : variants()) {
            IrModule optimized = compile(fixture.source(), variant.options());
            assertEquals(expected, execute(optimized, INPUTS), () -> fixture.name() + "/" + variant.name()
                    + "\n" + fixture.source() + "\n" + IrPrinter.print(optimized));
        }
        if (inspectPasses) {
            List<String> observed = new ArrayList<>();
            IrModule optimized = Optimizer.optimize(original, Set.of(), (pass, snapshot) -> {
                observed.add(pass);
                try {
                    assertEquals(expected, execute(snapshot, INPUTS), fixture.name() + " after " + pass);
                } catch (Exception error) {
                    throw new AssertionError(fixture.name() + " after " + pass, error);
                }
            });
            assertEquals(Set.copyOf(Optimizer.passes()), Set.copyOf(observed));
            assertEquals(IrPrinter.print(optimized), IrPrinter.print(compile(fixture.source(), CompilerOptions.DEFAULT)),
                    "debug observation cannot change deterministic compilation");
            assertEquals(IrPrinter.print(original), IrPrinter.print(compile(fixture.source(), UNOPTIMIZED)),
                    "passes cannot mutate their input IR");
        }
    }

    @TestFactory
    Stream<DynamicTest> sourceCorpus() {
        return fixtures().stream().map(f -> DynamicTest.dynamicTest(f.name(), () -> compare(f, true)));
    }

    /** Shared with {@link BytecodeBackendTest}: the same programs must behave the same on every backend. */
    static List<Fixture> fixtures() {
        return List.of(
                new Fixture("mutable-copies-and-overwritten-stores", """
                        function run(n: int): int {
                            var a = n
                            let saved = a
                            var b = saved
                            let before = b
                            a = 19
                            b = 23
                            var dead = 100
                            dead = 200
                            return saved * 1000 + before * 100 + a * 10 + b
                        }
                        """),
                new Fixture("nested-loops-joins-and-short-circuit", """
                        function mark(n: int): bool { log("mark {n}"); return n > 0 }
                        function run(n: int): string {
                            var total = 0
                            for i in 0..7 {
                                var j = 0
                                while j < 4 {
                                    j++
                                    if j == 2 { continue }
                                    if i == 5 { break }
                                    total += (i + n) * j
                                }
                            }
                            let a = n > 0 && mark(n)
                            let b = n <= 0 || mark(-n)
                            let branch = switch n { case 0 -> 10; case 1, 3 -> 20; default -> total }
                            return "{total} {a} {b} {branch}"
                        }
                        """),
                new Fixture("recursion-closures-and-loop-captures", """
                        function fib(n: int): int {
                            if n < 2 { return n }
                            return fib(n - 1) + fib(n - 2)
                        }
                        function make(a: int): function(int): int { return x => x + a }
                        function run(n: int): string {
                            let fs: List<function(): int> = []
                            for i in 1..3 { fs.add(() => i * 10 + n) }
                            let add = make(n)
                            return "{fs[0]()} {fs[1]()} {fs[2]()} {add(fib(9))}"
                        }
                        """),
                new Fixture("records-collections-nullability-and-casts", """
                        record Point(x: int, y: int) {
                            function total(): int { return x + y }
                        }
                        function run(n: int): string {
                            let p = Point(n, 2)
                            let boxed: any = p
                            let recovered = boxed as? Point
                            let values: List<int> = [n, 2, 3]
                            for x in values { values.add(x * 2) }
                            let map = {"a": n, "b": 7}
                            map["a"] += 2
                            let missing = map["missing"] ?? 19
                            let nullable: int? = n > 0 ? n : null
                            return "{boxed is Point} {recovered?.total()} {p == Point(n, 2)} "
                                + "{values} {map} {missing} {nullable ?? -1}"
                        }
                        """),
                new Fixture("globals-and-native-call-order", """
                        var counter = 2
                        var history: List<string> = []
                        function bump(n: int): int {
                            counter += n
                            log("bump {counter}")
                            history.add("{n}")
                            return counter
                        }
                        function run(n: int): string {
                            let before = counter
                            bump(n)
                            let first = bump(1)
                            let after = counter
                            let unused = math.clamp(5, 0, 10)
                            return "{before} {first} {after} {history}"
                        }
                        """),
                new Fixture("exception-intermediate-locals-and-finally", """
                        function run(n: int): string {
                            var value = 4
                            var out = ""
                            try {
                                value = 9
                                log("before {value}")
                                let unused = 10 / n
                                value = 15
                                if n == 3 { throw "three" }
                                out = "ok {value}"
                            } catch e {
                                out = "caught {value} {e.kind}"
                            } finally {
                                log("finally {value}")
                                value += 1
                            }
                            return "{out} {value}"
                        }
                        """),
                new Fixture("nested-handlers-return-break-continue", """
                        var cleanup = 0
                        function inner(n: int): int {
                            try {
                                if n == 1 { return 5 }
                                let xs = [n, 0]
                                xs.forEach(x => log("ratio {12 / x}"))
                            } catch e {
                                log("inner {e.kind}")
                                throw e
                            } finally {
                                cleanup += 1
                                [n].forEach(x => log("clean {x}"))
                            }
                            return 0
                        }
                        function run(n: int): string {
                            var total = 0
                            for i in 0..4 {
                                try {
                                    if i == 1 { continue }
                                    if i == 4 { break }
                                    total += inner(n)
                                } catch e {
                                    total += 100
                                } finally { total += 1 }
                            }
                            return "{total} {cleanup}"
                        }
                        """),
                new Fixture("unused-index-error-retains-location", """
                        function run(n: int): int {
                            log("before index")
                            let xs = [7]
                            let unused = xs[n]
                            log("after index")
                            return 99
                        }
                        """),
                new Fixture("unused-cast-error-retains-location", """
                        record Point(x: int)
                        function run(n: int): int {
                            let value: any = "wrong"
                            let unused = value as Point
                            return n
                        }
                        """),
                new Fixture("unused-constant-zero-divisor-is-runtime-error", """
                        function run(n: int): int {
                            var zero = 0
                            let unused = n / zero
                            return 99
                        }
                        """),
                new Fixture("native-error-is-never-discarded", """
                        function run(n: int): int {
                            log("before native")
                            let unused = math.clamp(n, 10, 0)
                            log("after native")
                            return 99
                        }
                        """),
                new Fixture("float-ieee-and-integer-overflow", """
                        function run(n: int): string {
                            var zero = 0.0
                            var one = 1.0
                            let nan = zero / zero
                            let inf = one / zero
                            let negzero = -zero
                            var f = 0.0f
                            let fnan = f / f
                            var max = 2147483647
                            var lmax = 9223372036854775807L
                            var shift = 65L
                            return "{nan == nan} {nan != nan} {nan < one} {inf} {one / negzero} "
                                + "{fnan != fnan} {max + 1} {lmax + 1L} {lmax >> shift} {n >>> 33}"
                        }
                        """));
    }

    @TestFactory
    Stream<DynamicTest> reproducibleGeneratedPrograms() {
        return IntStream.range(0, 96).mapToObj(index -> {
            long seed = SEED + index;
            return DynamicTest.dynamicTest("seed-" + Long.toUnsignedString(seed), () -> {
                Random random = new Random(seed);
                int multiplier = random.nextInt(31) + 1;
                int offset = random.nextInt(201) - 100;
                int mask = random.nextInt(255);
                int initial = random.nextInt();
                String source = """
                        function run(n: int): int {
                            var result = %d
                            var i = 0
                            while i < 9 {
                                let previous = result
                                if (i + n) %% 3 == 0 {
                                    result = (result * %d + %d) ^ %d
                                } else {
                                    result = (result >> 1) + previous * (i + 1)
                                }
                                i++
                            }
                            return result
                        }
                        """.formatted(initial, multiplier, offset, mask);
                List<Step> baseline = execute(compile(source, UNOPTIMIZED), INPUTS);
                for (int inputIndex = 0; inputIndex < INPUTS.length; inputIndex++) {
                    int result = initial;
                    for (int i = 0; i < 9; i++) {
                        int previous = result;
                        result = (i + INPUTS[inputIndex]) % 3 == 0 ? (result * multiplier + offset) ^ mask
                                : (result >> 1) + previous * (i + 1);
                    }
                    assertEquals(result, baseline.get(inputIndex).result(), "independent Java oracle, seed " + seed);
                }
                compare(new Fixture("seed-" + seed, source), false);
            });
        });
    }

    @TestFactory
    Stream<DynamicTest> watchdogSurvivesEmptyLoopsAndThreadedJumps() {
        List<Variant> modes = new ArrayList<>(variants());
        modes.add(new Variant("off", UNOPTIMIZED));
        return modes.stream().map(mode -> DynamicTest.dynamicTest(mode.name(), () -> {
            String source = """
                    function run(n: int): int {
                        try {
                            while true {
                                var dead = 1
                                if true { continue }
                            }
                        } catch e { return 10 }
                        return n
                    }
                    """;
            IrModule module = compile(source, mode.options());
            ExecutionStack.configure(new RuntimeLimits(32, 10_000_000L, 32));
            long start = System.nanoTime();
            List<Step> result = execute(module, 0);
            assertTrue(String.valueOf(result.getFirst().result()).startsWith("TIMEOUT\n"));
            assertTrue(System.nanoTime() - start < 5_000_000_000L);
        }));
    }

    @Test
    void reducesCodeAndSlotsWithoutDroppingUnusedParameters() throws Exception {
        String source = """
                function run(n: int): int {
                    var a = 10
                    var b = 20
                    let unused = a + b
                    a = 30
                    return a + b
                }
                """;
        IrModule original = compile(source, UNOPTIMIZED);
        IrModule optimized = compile(source, CompilerOptions.DEFAULT);
        var before = Assembler.assemble(original).units().getFirst();
        var after = Assembler.assemble(optimized).units().getFirst();
        assertTrue(after.code().length < before.code().length);
        assertTrue(after.primitiveSlots() < before.primitiveSlots());
        assertEquals(1, after.parameterSlots().length);
        assertEquals(50, execute(optimized, 42).getFirst().result());
    }

    @Test
    void unusedHostEqualityAndStringConversionStillExecute() throws Exception {
        String source = """
                function probe(a: any, b: any): int {
                    let unusedEquality = a == b
                    let unusedText = "{a}"
                    return 17
                }
                """;
        List<Variant> modes = new ArrayList<>(variants());
        modes.add(new Variant("off", UNOPTIMIZED));
        for (Variant mode : modes) {
            LinkedModule module = Linker.link(Assembler.assemble(compile(source, mode.options())),
                    StandardLibrary.coreBindings(), SimpleTextService.INSTANCE);
            List<String> calls = new ArrayList<>();
            Object probe = new Object() {
                @Override
                public boolean equals(Object other) {
                    calls.add("equals");
                    return false;
                }

                @Override
                public int hashCode() { return 0; }

                @Override
                public String toString() {
                    calls.add("toString");
                    return "host value";
                }
            };
            assertEquals(17, Interpreter.call(module.function("probe(any, any)").orElseThrow(), probe, new Object()));
            assertEquals(List.of("equals", "toString"), calls, mode.name());
        }
    }

    @Test
    void rejectsUnknownPassesAndDefensivelyCopiesOptions() {
        Set<String> disabled = new LinkedHashSet<>(Set.of("dead-code"));
        CompilerOptions options = CompilerOptions.DEFAULT.withDisabledPasses(disabled);
        disabled.clear();
        assertEquals(Set.of("dead-code"), options.disabledPasses());
        assertThrows(IllegalArgumentException.class, () -> options.withDisabledPasses(Set.of("typo")));
    }
}
