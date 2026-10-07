package dev.tachyonscript.runtime.interpreter;

import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.compiler.CompilationResult;
import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.compiler.CompilerOptions;
import dev.tachyonscript.compiler.InternalErrorHandler;
import dev.tachyonscript.ir.IrModule;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.runtime.ExecutionBackend;
import dev.tachyonscript.runtime.bytecode.BytecodeCompiler;
import dev.tachyonscript.runtime.code.AssembledModule;
import dev.tachyonscript.runtime.code.Assembler;
import dev.tachyonscript.runtime.code.CodeUnit;
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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JVM bytecode backend against the interpreter, which is the reference: the same source
 * must give the same results, errors (with their locations), native call order and globals.
 * Every generated class must also pass the JVM verifier's checks.
 */
class BytecodeBackendTest {
    private static final int[] INPUTS = {-2, 0, 1, 3, 7};
    private static final long SEED = 0x42595445434f4445L;

    private record Step(Object result, List<String> trace, Map<String, String> globals) { }

    @AfterEach
    void restoreLimits() {
        ExecutionStack.configure(RuntimeLimits.DEFAULT);
    }

    private static IrModule compile(String source, CompilerOptions options) {
        CompilationResult compiled = new Compiler(StandardLibrary.registry(), options, InternalErrorHandler.IGNORE)
                .compile(List.of(new SourceFile("backend.tys", source)));
        assertTrue(compiled.succeeded(), () -> compiled.diagnostics().diagnostics().stream()
                .map(DiagnosticRenderer.plain()::render).reduce("", String::concat) + "\n" + source);
        return compiled.modules().getFirst().ir();
    }

    private static LinkedModule link(IrModule ir, ExecutionBackend backend, List<String> trace) throws Exception {
        Bindings bindings = Bindings.builder().include(StandardLibrary.coreBindings())
                .bind(ServerApi.LOG_STRING, (NativeFunction.OfVoid) a -> trace.add(a.getString(0))).build();
        return Linker.link(Assembler.assemble(ir), bindings, SimpleTextService.INSTANCE, backend);
    }

    private static List<Step> execute(IrModule ir, ExecutionBackend backend, int... inputs) throws Exception {
        List<String> trace = new ArrayList<>();
        LinkedModule module = link(ir, backend, trace);
        for (CompiledFunction function : module.functions().values()) {
            assertEquals(backend, function.backend(), function.key());
        }
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

    /** Runs ASM's structural and data-flow verification on every generated class. */
    private static void verify(IrModule ir) {
        AssembledModule assembled = Assembler.assemble(ir);
        for (CodeUnit unit : assembled.units()) {
            byte[] bytes = BytecodeCompiler.generate(unit, assembled.source());
            StringWriter problems = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(bytes), BytecodeBackendTest.class.getClassLoader(), false,
                    new PrintWriter(problems));
            assertEquals("", problems.toString(), unit.key());
        }
    }

    private static void compare(String name, String source) throws Exception {
        for (CompilerOptions options : List.of(CompilerOptions.DEFAULT, CompilerOptions.DEFAULT.withOptimization(false))) {
            IrModule ir = compile(source, options);
            verify(ir);
            List<Step> expected = execute(ir, ExecutionBackend.INTERPRETER, INPUTS);
            assertEquals(expected, execute(ir, ExecutionBackend.BYTECODE, INPUTS),
                    () -> name + " (optimize=" + options.optimize() + ")\n" + source);
        }
    }

    @TestFactory
    Stream<DynamicTest> sameBehaviourAsTheInterpreterOnTheOptimizerCorpus() {
        return OptimizerDifferentialTest.fixtures().stream()
                .map(fixture -> DynamicTest.dynamicTest(fixture.name(), () -> compare(fixture.name(), fixture.source())));
    }

    @TestFactory
    Stream<DynamicTest> sameBehaviourOnGeneratedPrograms() {
        return IntStream.range(0, 64).mapToObj(index -> {
            long seed = SEED + index;
            return DynamicTest.dynamicTest("seed-" + Long.toUnsignedString(seed), () -> {
                Random random = new Random(seed);
                String[] operators = {"+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>"};
                StringBuilder body = new StringBuilder();
                for (int i = 0; i < 6; i++) {
                    String operator = operators[random.nextInt(operators.length)];
                    int constant = random.nextInt(19) - 9;
                    body.append("        a = (a ").append(operator).append(" (b + ").append(constant).append("))")
                            .append(random.nextBoolean() ? " + i" : " - i").append('\n');
                    body.append("        b = b ").append(random.nextBoolean() ? "+" : "-").append(" a % 7\n");
                    if (random.nextInt(3) == 0) body.append("        if a > b { continue }\n");
                }
                String source = """
                        function run(n: int): string {
                            var a = n * %d
                            var b = %d
                            var d = 0.5
                            var l = 7L
                            for i in 0..<%d {
                        %s        d = d * 1.5 + a
                                l = l * 3L + b
                            }
                            return "{a} {b} {d} {l} {a < b} {d > 2.0}"
                        }
                        """.formatted(random.nextInt(9) + 1, random.nextInt(100), random.nextInt(6) + 2, body);
                compare("seed-" + seed, source);
            });
        });
    }

    @Test
    void watchdogStopsLoopsAndRecursionInGeneratedCode() throws Exception {
        ExecutionStack.configure(new RuntimeLimits(128, 50_000_000L, 1024));
        String loops = """
                function run(n: int): int {
                    var x = 0
                    while true { x += n }
                }
                """;
        long start = System.nanoTime();
        assertTrue(String.valueOf(execute(compile(loops, CompilerOptions.DEFAULT), ExecutionBackend.BYTECODE, 1)
                .getFirst().result()).startsWith("TIMEOUT\n"));
        String recursion = """
                function branch(n: int): int {
                    if n == 0 { return 1 }
                    return branch(n - 1) + branch(n - 1)
                }
                function run(n: int): int { return branch(n + 59) }
                """;
        assertTrue(String.valueOf(execute(compile(recursion, CompilerOptions.DEFAULT), ExecutionBackend.BYTECODE, 1)
                .getFirst().result()).startsWith("TIMEOUT\n"));
        assertTrue(System.nanoTime() - start < 10_000_000_000L, "both stop promptly");
    }

    /**
     * Generated classes are hidden classes, whose frames the JVM leaves out of ordinary stack
     * traces; they still carry the script file and line numbers, which a stack walker that
     * shows hidden frames (or {@code -XX:+ShowHiddenFrames}) reports.
     */
    @Test
    void generatedFramesCarryTheScriptFileAndLine() throws Exception {
        String source = """
                function run(n: int): int {
                    log("first")
                    log("second")
                    return n
                }
                """;
        List<String> lines = new ArrayList<>();
        Bindings bindings = Bindings.builder().include(StandardLibrary.coreBindings())
                .bind(ServerApi.LOG_STRING, (NativeFunction.OfVoid) a -> lines.add(a.getString(0) + "@"
                        + StackWalker.getInstance(java.util.Set.of(StackWalker.Option.SHOW_HIDDEN_FRAMES))
                                .walk(frames -> frames.filter(frame -> "backend.tys".equals(frame.getFileName()))
                                        .map(frame -> String.valueOf(frame.getLineNumber())).findFirst().orElse("none"))))
                .build();
        LinkedModule module = Linker.link(Assembler.assemble(compile(source, CompilerOptions.DEFAULT)), bindings,
                SimpleTextService.INSTANCE, ExecutionBackend.BYTECODE);
        assertEquals(7, Interpreter.call(module.function("run(int)").orElseThrow(), 7));
        assertEquals(List.of("first@2", "second@3"), lines);
    }

    @Test
    void retiredGenerationsDoNotKeepTheirGeneratedClasses() throws Exception {
        String source = "function run(n: int): int { return n * 2 + 1 }\n";
        IrModule ir = compile(source, CompilerOptions.DEFAULT);
        List<WeakReference<Class<?>>> classes = new ArrayList<>();
        for (int generation = 0; generation < 20; generation++) {
            LinkedModule module = link(ir, ExecutionBackend.BYTECODE, new ArrayList<>());
            CompiledFunction run = module.function("run(int)").orElseThrow();
            assertEquals(2 * generation + 1, Interpreter.call(run, generation));
            assertNotNull(run.bytecode);
            classes.add(new WeakReference<>(run.bytecode.getClass()));
        }
        // Nothing references the modules any more: their hidden classes can be unloaded.
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (classes.stream().anyMatch(reference -> reference.get() != null) && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(20);
        }
        assertTrue(classes.stream().allMatch(reference -> reference.get() == null), "generated classes were collected");
    }

    @Test
    void generationIsDeterministicAndRejectsJumpsIntoOperands() {
        IrModule ir = compile(OptimizerDifferentialTest.fixtures().get(1).source(), CompilerOptions.DEFAULT);
        AssembledModule first = Assembler.assemble(ir);
        AssembledModule second = Assembler.assemble(ir);
        for (int i = 0; i < first.units().size(); i++) {
            assertEquals(java.util.Arrays.toString(BytecodeCompiler.generate(first.units().get(i), first.source())),
                    java.util.Arrays.toString(BytecodeCompiler.generate(second.units().get(i), second.source())));
        }
        CodeUnit unit = first.units().stream().filter(u -> u.key().equals("run(int)")).findFirst().orElseThrow();
        assertSame(unit.code(), unit.code());
        assertThrows(IllegalArgumentException.class, () -> BytecodeCompiler.generate(new CodeUnit(unit.key(), unit.displayName(),
                unit.kind(), new int[] {dev.tachyonscript.runtime.code.Opcodes.JMP, 1}, unit.primitiveSlots(),
                unit.referenceSlots(), unit.parameterSlots(), unit.parameterIsReference(), unit.returnKind(),
                unit.primitivePool(), unit.referencePool(), unit.natives(), unit.functions(), unit.classes(),
                unit.templates(), unit.globals(), unit.records(), new int[0], new int[0], new long[0],
                unit.span()), first.source()));
    }
}
