package dev.tachyonscript.language.syntax;

import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.language.source.Span;

import java.util.List;

/**
 * The syntax tree of one file.
 *
 * @param file         the parsed file
 * @param module       the {@code module} declaration, or {@code null}
 * @param imports      import declarations in source order
 * @param declarations all other top-level declarations in source order
 * @param span         the whole file
 */
public record SourceUnit(SourceFile file, Declaration.Module module, List<Declaration.Import> imports,
                         List<Declaration> declarations, Span span) implements Node {

    public SourceUnit {
        imports = List.copyOf(imports);
        declarations = List.copyOf(declarations);
    }
}
