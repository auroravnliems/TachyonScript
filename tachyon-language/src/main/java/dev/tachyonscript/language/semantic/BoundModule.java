package dev.tachyonscript.language.semantic;

import dev.tachyonscript.language.source.SourceFile;

import java.util.List;

/**
 * The semantic model of one file: everything later stages need, with no unresolved names.
 *
 * @param file      the source file
 * @param name      module name ({@code module} declaration, or derived from the path)
 * @param functions script functions in declaration order
 * @param handlers  event handlers in declaration order
 * @param constants constants in declaration order (already inlined at their uses)
 */
public record BoundModule(SourceFile file, String name, List<BoundFunction> functions,
                          List<BoundEventHandler> handlers, List<ConstantSymbol> constants) {

    public BoundModule {
        functions = List.copyOf(functions);
        handlers = List.copyOf(handlers);
        constants = List.copyOf(constants);
    }
}
