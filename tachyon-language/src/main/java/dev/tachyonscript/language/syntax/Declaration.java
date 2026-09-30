package dev.tachyonscript.language.syntax;

import dev.tachyonscript.language.source.Span;

import java.util.List;

/** Top-level declarations. */
public sealed interface Declaration extends Node {

    /** Text of the {@code ///} comment preceding the declaration, or an empty string. */
    default String documentation() {
        return "";
    }

    /** Annotations written before the declaration ({@code @permission("x")}); empty if none. */
    default List<Annotation> annotations() {
        return List.of();
    }

    /**
     * {@code @name} or {@code @name(arguments)} before a declaration.
     *
     * @param name      annotation name
     * @param arguments argument expressions (constants, durations or bare names)
     */
    record Annotation(Identifier name, List<Expression> arguments, Span span) {
        public Annotation {
            arguments = List.copyOf(arguments);
        }
    }

    /**
     * A parameter {@code name: Type}, optionally with a default value ({@code amount: int = 1})
     * or, for the last parameter of a command, the rest of the command line
     * ({@code message: string...}).
     */
    record Parameter(Identifier name, TypeRef type, Expression defaultValue, boolean rest, Span span) {
        public Parameter(Identifier name, TypeRef type, Span span) {
            this(name, type, null, false, span);
        }
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
    record Event(QualifiedName name, Statement.Block body, String documentation, List<Annotation> annotations,
                 Span span) implements Declaration {
        public Event {
            annotations = List.copyOf(annotations);
        }
    }

    /** {@code function name(params): ReturnType { ... }}; {@code returnType} is {@code null} for void. */
    record Function(Identifier name, List<Parameter> parameters, TypeRef returnType, Statement.Block body,
                    String documentation, List<Annotation> annotations, Span span) implements Declaration {
        public Function {
            parameters = List.copyOf(parameters);
            annotations = List.copyOf(annotations);
        }
    }

    /** {@code const NAME: Type = value}; {@code type} is {@code null} when inferred. */
    record Const(Identifier name, TypeRef type, Expression value, String documentation, Span span)
            implements Declaration {
    }

    /** Where a top-level variable keeps its value. */
    enum Storage {
        /** {@code let}/{@code var}: in memory while the script is loaded. */
        SCRIPT,
        /** {@code persistent var}: saved in the database, one value for the server. */
        PERSISTENT,
        /** {@code playerdata var}: saved in the database, one value per player ({@code player.name}). */
        PLAYERDATA
    }

    /**
     * A top-level variable: {@code let x = 1}, {@code var x = 1}, {@code persistent var x = 1}
     * or {@code playerdata var coins: int = 0}.
     */
    record Global(Identifier name, TypeRef type, Expression initializer, boolean mutable, Storage storage,
                  String documentation, List<Annotation> annotations, Span span) implements Declaration {
        public Global {
            annotations = List.copyOf(annotations);
        }
    }

    /** A field of a record, optionally with a default value. */
    record RecordField(Identifier name, TypeRef type, Expression defaultValue, Span span) {
    }

    /**
     * {@code record Warp(name: string, location: Location) { function ... }}: an immutable data
     * type. {@code methods} are functions with access to the fields.
     */
    record Record(Identifier name, List<RecordField> fields, List<Function> methods, String documentation,
                  Span span) implements Declaration {
        public Record {
            fields = List.copyOf(fields);
            methods = List.copyOf(methods);
        }
    }

    /**
     * {@code command heal(target: Player? = null) { ... }}. {@code path} has more than one part
     * for sub-commands ({@code command warp.set(name: string)}).
     */
    record Command(QualifiedName path, List<Parameter> parameters, Statement.Block body, String documentation,
                   List<Annotation> annotations, Span span) implements Declaration {
        public Command {
            parameters = List.copyOf(parameters);
            annotations = List.copyOf(annotations);
        }
    }

    /** {@code on load { }} or {@code on unload { }}. */
    record Lifecycle(boolean load, Statement.Block body, Span span) implements Declaration {
    }

    /**
     * A task started when the script loads: {@code every 5 minutes { }} (repeating, {@code
     * time} is {@code null}) or {@code at "20:00" { }} (daily, {@code interval} is {@code null}).
     */
    record Task(Expression interval, Expression time, Statement.Block body, List<Annotation> annotations, Span span)
            implements Declaration {
        public Task {
            annotations = List.copyOf(annotations);
        }
    }

    /** {@code placeholder coins { return "..." }}: a PlaceholderAPI placeholder ({@code %tys_coins%}). */
    record Placeholder(Identifier name, Statement.Block body, String documentation, Span span)
            implements Declaration {
    }
}
