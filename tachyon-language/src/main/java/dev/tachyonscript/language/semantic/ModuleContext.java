package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.diagnostic.DiagnosticCollector;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.language.source.Span;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * State shared while binding one file: registry, diagnostics, module-level symbols of this
 * module and the modules it imports.
 */
final class ModuleContext {

    private final SourceFile file;
    private final String moduleName;
    private final SymbolRegistry registry;
    private final DiagnosticCollector diagnostics;
    private final MemberLookup members;
    private final TypeResolver types;
    private final Map<String, List<FunctionSymbol>> functions = new LinkedHashMap<>();
    private final Map<String, ConstantSymbol> constants = new LinkedHashMap<>();
    private final Map<String, GlobalSymbol> globals = new LinkedHashMap<>();
    private final Map<String, RecordSymbol> records = new LinkedHashMap<>();
    /** {@code import m} and {@code import m as alias}: alias to module. */
    private final Map<String, BoundModule> moduleAliases = new LinkedHashMap<>();
    /** {@code import { a } from m}: name to the imported symbol (constant, global, record or overloads). */
    private final Map<String, Object> importedNames = new LinkedHashMap<>();
    private final Map<ClassType, RecordSymbol> recordsByType = new LinkedHashMap<>();
    /** Top-level variables declared further down, while the initializers are bound: name to declaration. */
    private final Map<String, Span> pendingGlobals = new LinkedHashMap<>();
    private int temporaries;
    private int lambdas;

    ModuleContext(SourceFile file, String moduleName, SymbolRegistry registry, DiagnosticCollector diagnostics) {
        this.file = file;
        this.moduleName = moduleName;
        this.registry = registry;
        this.diagnostics = diagnostics;
        this.members = new MemberLookup(registry);
        this.types = new TypeResolver(this);
    }

    SourceFile file() {
        return file;
    }

    String moduleName() {
        return moduleName;
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

    Map<String, GlobalSymbol> globals() {
        return globals;
    }

    Map<String, RecordSymbol> records() {
        return records;
    }

    Map<String, Span> pendingGlobals() {
        return pendingGlobals;
    }

    void addRecord(RecordSymbol record) {
        records.put(record.name(), record);
        recordsByType.put(record.type(), record);
    }

    /** The record declaring {@code type} (this module or an imported one), or null. */
    RecordSymbol record(ClassType type) {
        RecordSymbol record = recordsByType.get(type);
        if (record != null) {
            return record;
        }
        for (BoundModule module : moduleAliases.values()) {
            for (RecordSymbol candidate : module.records()) {
                if (candidate.type() == type) {
                    return candidate;
                }
            }
        }
        for (Object imported : importedNames.values()) {
            if (imported instanceof RecordSymbol candidate && candidate.type() == type) {
                return candidate;
            }
        }
        return null;
    }

    Map<String, BoundModule> moduleAliases() {
        return moduleAliases;
    }

    Map<String, Object> importedNames() {
        return importedNames;
    }

    /** A class type of the standard library by name, or null when the registry has none. */
    ClassType standardType(String name) {
        return registry.type(name).orElse(null);
    }

    LocalSymbol newTemporary(dev.tachyonscript.api.type.Type type, Span span) {
        return new LocalSymbol("$t" + (temporaries++), type, true, LocalSymbol.Kind.TEMPORARY, span, null);
    }

    /** A new unique lambda key for this module, e.g. {@code lambda#3}. */
    String newLambdaKey() {
        return "lambda#" + (lambdas++);
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
