package dev.tachyonscript.benchmarks;

import dev.tachyonscript.compiler.CompilerOptions;
import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.LoadMode;
import dev.tachyonscript.engine.LoadReport;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;
import dev.tachyonscript.runtime.interpreter.RuntimeLimits;
import dev.tachyonscript.testkit.InMemoryScripts;
import dev.tachyonscript.testkit.TestPlatform;

/** Loads benchmark scripts through the real pipeline (compiler, assembler, linker). */
final class Scripts {

    /**
     * Production settings except that slow-execution checks are off, so that no benchmark
     * measures the clock reads they need.
     */
    static final EngineOptions OPTIONS = new EngineOptions(LoadMode.STRICT, CompilerOptions.DEFAULT,
            RuntimeLimits.DEFAULT, 0, false);

    private Scripts() {
    }

    static ScriptEngine load(TestPlatform platform, String path, String source) {
        ScriptEngine engine = platform.engine(OPTIONS);
        LoadReport report = engine.load(new InMemoryScripts().put(path, source));
        if (!report.activated() || !report.failed().isEmpty()) {
            throw new IllegalStateException("Benchmark script failed to compile: " + report.diagnostics()
                    + " " + report.linkProblems());
        }
        return engine;
    }

    static CompiledFunction function(ScriptEngine engine, String path, String key) {
        return engine.generation().scripts().get(path).linked().function(key)
                .orElseThrow(() -> new IllegalStateException("No function " + key));
    }
}
