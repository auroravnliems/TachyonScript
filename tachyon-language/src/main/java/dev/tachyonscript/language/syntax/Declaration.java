package dev.tachyonscript.language.syntax;

import dev.tachyonscript.language.source.Span;

import java.util.List;

/** Top-level declarations. */
public sealed interface Declaration extends Node {

    /** Text of the {@code ///} comment preceding the declaration, or an empty string. */
    default String documentation() {
        return "";
    }

    /** A function parameter {@code name: Type}. */
    record Parameter(Identifier name, TypeRef type, Span span) {
    }

    /** {@code module economy.currency} */
    record Module(QualifiedName name, Span span) implements Declaration {
    }

    /**
     * {@code import economy.currency}, {@code import economy.currency as money} or
     * {@code import { formatMoney } from economy}. {@code names} is empty for whole-module imports;
     * {@code alias} is {@code null} when absent.
     */
    record Import(QualifiedName module, List<Identifier> names, Identifier alias, Span span) implements Declaration {
        public Import {
            names = List.copyOf(names);
        }
    }

    /** {@code event player.join { ... }} */
    record Event(QualifiedName name, Statement.Block body, String documentation, Span span) implements Declaration {
    }

    /** {@code function name(params): ReturnType { ... }}; {@code returnType} is {@code null} for void. */
    record Function(Identifier name, List<Parameter> parameters, TypeRef returnType, Statement.Block body,
                    String documentation, Span span) implements Declaration {
        public Function {
            parameters = List.copyOf(parameters);
        }
    }

    /** {@code const NAME: Type = value}; {@code type} is {@code null} when inferred. */
    record Const(Identifier name, TypeRef type, Expression value, String documentation, Span span)
            implements Declaration {
    }
}
