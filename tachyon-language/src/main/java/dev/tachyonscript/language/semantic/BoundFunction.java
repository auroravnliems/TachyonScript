package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.language.source.Span;

import java.util.List;

/**
 * A type-checked unit of code that is lowered to one IR function: a script function or
 * record method ({@code symbol} set), or the body of a command, task, lifecycle hook,
 * placeholder, module initializer or playerdata default ({@code symbol} null).
 *
 * @param key         unique key within the module
 * @param displayName name shown in stack traces, e.g. {@code function reward} or {@code command /heal}
 * @param parameters  parameters, in order (for methods, {@code this} first)
 * @param returnType  result type
 * @param body        the code
 * @param span        the declaration
 * @param symbol      the script function, or {@code null}
 */
public record BoundFunction(String key, String displayName, List<LocalSymbol> parameters, Type returnType,
                            BoundStatement.Block body, Span span, FunctionSymbol symbol) {

    public BoundFunction {
        parameters = List.copyOf(parameters);
    }
}
