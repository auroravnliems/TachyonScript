package dev.tachyonscript.runtime.code;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.ir.SourceText;

import java.util.List;

/**
 * All code units of one module, ready for linking.
 *
 * @param name     module name
 * @param source   source text for error reporting
 * @param units    code units, in module order
 * @param handlers event handlers (event and the key of the handling unit)
 */
public record AssembledModule(String name, SourceText source, List<CodeUnit> units, List<Handler> handlers) {

    public record Handler(EventDeclaration event, String function) {
    }

    public AssembledModule {
        units = List.copyOf(units);
        handlers = List.copyOf(handlers);
    }
}
