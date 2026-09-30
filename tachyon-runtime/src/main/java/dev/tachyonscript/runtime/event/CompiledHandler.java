package dev.tachyonscript.runtime.event;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;

/**
 * An event handler ready to run.
 *
 * @param event           the handled event
 * @param function        the handler body (takes the platform event object)
 * @param module          name of the module declaring it
 * @param priority        listener priority, 0 (lowest) to 5 (monitor); 2 is normal
 * @param ignoreCancelled whether the handler is skipped when another handler cancelled the event
 */
public record CompiledHandler(EventDeclaration event, CompiledFunction function, String module, int priority,
                              boolean ignoreCancelled) {

    /** Number of priority levels (LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR). */
    public static final int PRIORITIES = 6;
    public static final int NORMAL_PRIORITY = 2;

    public CompiledHandler(EventDeclaration event, CompiledFunction function, String module) {
        this(event, function, module, NORMAL_PRIORITY, false);
    }
}
