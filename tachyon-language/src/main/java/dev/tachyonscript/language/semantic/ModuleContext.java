package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.diagnostic.DiagnosticCollector;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.language.source.Span;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** State shared while binding one file: registry, diagnostics and module-level symbols. */
final class ModuleContext {

    private final SourceFile file;
    private final SymbolRegistry registry;
    private final DiagnosticCollector diagnostics;
    private final MemberLookup members;
    private final TypeResolver types;
    private final Map<String, List<FunctionSymbol>> functions = new LinkedHashMap<>();
    private final Map<String, ConstantSymbol> constants = new LinkedHashMap<>();
    private int temporaries;

    ModuleContext(SourceFile file, SymbolRegistry registry, DiagnosticCollector diagnostics) {
        this.file = file;
        this.registry = registry;
        this.diagnostics = diagnostics;
        this.members = new MemberLookup(registry);
        this.types = new TypeResolver(this);
    }

    SourceFile file() {
        return file;
    }

    SymbolRegistry registry() {
        return registry;
    }

    MemberLookup members() {
        return members;
    }

    TypeResolver types() {
        return types;
    }

    Map<String, List<FunctionSymbol>> functions() {
        return functions;
    }

    List<FunctionSymbol> functions(String name) {
        return functions.getOrDefault(name, List.of());
    }

    void addFunction(FunctionSymbol function) {
        functions.computeIfAbsent(function.name(), k -> new ArrayList<>()).add(function);
    }

    Map<String, ConstantSymbol> constants() {
        return constants;
    }

    LocalSymbol newTemporary(dev.tachyonscript.api.type.Type type, Span span) {
        return new LocalSymbol("$t" + (temporaries++), type, true, LocalSymbol.Kind.TEMPORARY, span, null);
    }

    void report(Diagnostic diagnostic) {
        diagnostics.report(diagnostic);
    }

    void error(DiagnosticCode code, Span span, String message) {
        diagnostics.report(Diagnostic.builder(code, file, span, message).build());
    }

    Diagnostic.Builder diagnostic(DiagnosticCode code, Span span, String message) {
        return Diagnostic.builder(code, file, span, message);
    }

    /** Whether an error inside {@code span} of this file was already reported. */
    boolean hasErrorsAt(Span span) {
        for (Diagnostic diagnostic : diagnostics.diagnostics()) {
            if (diagnostic.isError() && diagnostic.file() == file
                    && diagnostic.span().start() >= span.start() && diagnostic.span().end() <= span.end()) {
                return true;
            }
        }
        return false;
    }
}
