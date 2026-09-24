package dev.tachyonscript.compiler;

import dev.tachyonscript.ir.IrModule;
import dev.tachyonscript.language.semantic.BoundModule;
import dev.tachyonscript.language.source.SourceFile;

/**
 * The result of compiling one file.
 *
 * @param file  the source file
 * @param name  module name
 * @param bound semantic model, or {@code null} if parsing failed
 * @param ir    verified IR, or {@code null} if the file has errors
 */
public record CompiledModule(SourceFile file, String name, BoundModule bound, IrModule ir) {

    /** Whether the module compiled without errors and can be executed. */
    public boolean succeeded() {
        return ir != null;
    }
}
