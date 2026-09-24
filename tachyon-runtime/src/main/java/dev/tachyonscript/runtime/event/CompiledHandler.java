package dev.tachyonscript.runtime.event;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;

/**
 * An event handler ready to run.
 *
 * @param event    the handled event
 * @param function the handler body (takes the platform event object)
 * @param module   name of the module declaring it
 */
public record CompiledHandler(EventDeclaration event, CompiledFunction function, String module) {
}
