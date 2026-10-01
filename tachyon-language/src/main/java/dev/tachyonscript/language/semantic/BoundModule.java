package dev.tachyonscript.language.semantic;

import dev.tachyonscript.language.source.SourceFile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The semantic model of one file: everything later stages need, with no unresolved names.
 *
 * @param file          the source file
 * @param name          module name ({@code module} declaration, or derived from the path)
 * @param functions     script functions and record methods, in declaration order
 * @param handlers      event handlers in declaration order
 * @param constants     constants in declaration order (already inlined at their uses)
 * @param globals       top-level variables in declaration order
 * @param records       records in declaration order
 * @param commands      commands in declaration order
 * @param tasks         {@code every} and {@code at} tasks
 * @param loadHooks     {@code on load} blocks, in order
 * @param unloadHooks   {@code on unload} blocks, in order
 * @param placeholders  placeholders
 * @param initializer   initializes the script variables (and saved variables without a saved
 *                      value); {@code null} when the module has no top-level variables
 * @param defaults      for each {@code playerdata var}: the function computing its initial value
 * @param fieldDefaults for each record field with a default value: the function computing it, by
 *                      function key ({@code $field:Record.field}); saved records written before
 *                      the field existed get their value from it
 * @param imports       names of the modules this one imports
 */
public record BoundModule(SourceFile file, String name, List<BoundFunction> functions, List<BoundEventHandler> handlers,
                          List<ConstantSymbol> constants, List<GlobalSymbol> globals, List<RecordSymbol> records,
                          List<BoundCommand> commands, List<BoundTask> tasks, List<BoundFunction> loadHooks,
                          List<BoundFunction> unloadHooks, List<BoundPlaceholder> placeholders, BoundFunction initializer,
                          Map<GlobalSymbol, BoundFunction> defaults, Map<String, BoundFunction> fieldDefaults,
                          List<String> imports) {

    public BoundModule {
        functions = List.copyOf(functions);
        handlers = List.copyOf(handlers);
        constants = List.copyOf(constants);
        globals = List.copyOf(globals);
        records = List.copyOf(records);
        commands = List.copyOf(commands);
        tasks = List.copyOf(tasks);
        loadHooks = List.copyOf(loadHooks);
        unloadHooks = List.copyOf(unloadHooks);
        placeholders = List.copyOf(placeholders);
        defaults = Collections.unmodifiableMap(new LinkedHashMap<>(defaults));
        fieldDefaults = Collections.unmodifiableMap(new LinkedHashMap<>(fieldDefaults));
        imports = List.copyOf(imports);
    }

    /** A top-level name declared by this module (for imports), or null. */
    public Object member(String memberName) {
        for (ConstantSymbol constant : constants) {
            if (constant.name().equals(memberName)) {
                return constant;
            }
        }
        for (GlobalSymbol global : globals) {
            if (global.name().equals(memberName)) {
                return global;
            }
        }
        for (RecordSymbol record : records) {
            if (record.name().equals(memberName)) {
                return record;
            }
        }
        List<FunctionSymbol> overloads = functions(memberName);
        return overloads.isEmpty() ? null : overloads;
    }

    /** Top-level functions (not methods) with the given name. */
    public List<FunctionSymbol> functions(String functionName) {
        return functions.stream().map(BoundFunction::symbol)
                .filter(symbol -> symbol != null && !symbol.isMethod() && symbol.name().equals(functionName))
                .toList();
    }
}
