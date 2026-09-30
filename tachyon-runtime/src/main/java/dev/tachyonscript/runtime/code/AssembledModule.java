package dev.tachyonscript.runtime.code;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.ir.GlobalRef;
import dev.tachyonscript.ir.RecordRef;
import dev.tachyonscript.ir.SourceText;

import java.util.List;

/**
 * All code units of one module, ready for linking.
 *
 * @param name     module name
 * @param source   source text for error reporting
 * @param units    code units, in module order
 * @param handlers event handlers (event, key of the handling unit, priority, ignoreCancelled)
 * @param globals  top-level variables declared by the module
 * @param records  record types declared by the module
 */
public record AssembledModule(String name, SourceText source, List<CodeUnit> units, List<Handler> handlers,
                              List<GlobalRef> globals, List<RecordRef> records) {

    /**
     * @param priority        listener priority, 0 (lowest) to 5 (monitor)
     * @param ignoreCancelled whether the handler is skipped for cancelled events
     */
    public record Handler(EventDeclaration event, String function, int priority, boolean ignoreCancelled) {
    }

    public AssembledModule {
        units = List.copyOf(units);
        handlers = List.copyOf(handlers);
        globals = List.copyOf(globals);
        records = List.copyOf(records);
    }
}
