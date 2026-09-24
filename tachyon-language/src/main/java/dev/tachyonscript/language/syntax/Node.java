package dev.tachyonscript.language.syntax;

import dev.tachyonscript.language.source.Span;

/**
 * A node of the syntax tree.
 *
 * <p>The syntax tree is an immutable, faithful representation of the source: it contains
 * no resolved names or types (those live in the bound tree produced by the binder), which
 * keeps it reusable by tools such as a formatter or language server. Nodes are compared by
 * identity wherever semantic information is attached to them.
 */
public sealed interface Node permits SourceUnit, Declaration, Statement, Expression, TypeRef, Identifier, QualifiedName {

    /** Source range covered by this node. */
    Span span();
}
