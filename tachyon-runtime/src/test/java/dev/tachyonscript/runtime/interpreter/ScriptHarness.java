package dev.tachyonscript.runtime.interpreter;

import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.compiler.CompilationResult;
import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.runtime.code.Assembler;
import dev.tachyonscript.runtime.link.LinkException;
import dev.tachyonscript.runtime.link.LinkedModule;
import dev.tachyonscript.runtime.link.Linker;
import dev.tachyonscript.runtime.spi.SimpleTextService;
import dev.tachyonscript.stdlib.EntityApi;
import dev.tachyonscript.stdlib.ServerApi;
import dev.tachyonscript.stdlib.StandardLibrary;

import java.util.ArrayList;
import java.util.List;

/** Compiles and links scripts against the standard library with a few test bindings. */
final class ScriptHarness {

    static final List<String> LOG = new ArrayList<>();

    static final Bindings BINDINGS = Bindings.builder()
            .include(StandardLibrary.coreBindings())
            .bind(ServerApi.LOG_STRING, (NativeFunction.OfVoid) a -> LOG.add(a.getString(0)))
            .bind(ServerApi.PLAYER_BY_NAME, (NativeFunction.OfRef) a -> null)
            .bindGetter(EntityApi.ENTITY_NAME, (NativeFunction.OfRef) a -> String.valueOf(a.getRef(0)))
            .build();

    private ScriptHarness() {
    }

    static LinkedModule load(String source) {
        CompilationResult result = new Compiler(StandardLibrary.registry()).compile(List.of(new SourceFile("test.tys", source)));
        if (!result.succeeded()) {
            StringBuilder out = new StringBuilder();
            result.diagnostics().diagnostics().forEach(d -> out.append(DiagnosticRenderer.plain().render(d)));
            throw new AssertionError(out.toString());
        }
        try {
            return Linker.link(Assembler.assemble(result.modules().getFirst().ir()), BINDINGS, SimpleTextService.INSTANCE);
        } catch (LinkException e) {
            throw new AssertionError(e.getMessage(), e);
        }
    }

    static Object run(String source, String function, Object... arguments) {
        LinkedModule module = load(source);
        return Interpreter.call(module.function(function).orElseThrow(), arguments);
    }
}
