package dev.tachyonscript.stdlib;

import dev.tachyonscript.api.declaration.Effect;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.doc.Documentation;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.api.value.ScriptTask;

import java.util.List;

import static dev.tachyonscript.stdlib.MinecraftTypes.TASK;

/** Members of {@code Task}, the value of {@code task} inside an {@code every} block. Implemented here for every platform. */
public final class SchedulingApi {

    public static final FunctionDeclaration CANCEL = FunctionDeclaration.method(TASK, "cancel")
            .effects(Effect.MODIFIES_WORLD)
            .documentation(new Documentation("Stops the repeating block; the current run finishes.", "",
                    List.of("every 1 second {\n    if task.runs >= 10 {\n        task.cancel()\n    }\n}"), "0.2.0"))
            .build();

    public static final PropertyDeclaration CANCELLED = PropertyDeclaration.member(TASK, "cancelled", Types.BOOL)
            .doc("Whether the task was cancelled.").build();

    public static final PropertyDeclaration RUNS = PropertyDeclaration.member(TASK, "runs", Types.LONG)
            .doc("How many times the block has started running, this run included.").build();

    public static final PropertyDeclaration LOCATION = PropertyDeclaration.member(TASK, "location", Types.STRING)
            .doc("Where the task was started, e.g. 'shop.tys:12'.").build();

    public static final FunctionDeclaration TO_STRING = FunctionDeclaration.method(TASK, "toString")
            .returns(Types.STRING).doc("A description of the task.").build();

    public static final List<FunctionDeclaration> FUNCTIONS = List.of(CANCEL, TO_STRING);
    public static final List<PropertyDeclaration> PROPERTIES = List.of(CANCELLED, RUNS, LOCATION);

    private SchedulingApi() {
    }

    static void bind(Bindings.Builder b) {
        b.bindType(TASK, ScriptTask.class);
        b.bind(CANCEL, (NativeFunction.OfVoid) a -> ((ScriptTask) a.getRef(0)).cancel());
        b.bindGetter(CANCELLED, (NativeFunction.OfBool) a -> ((ScriptTask) a.getRef(0)).isCancelled());
        b.bindGetter(RUNS, (NativeFunction.OfLong) a -> ((ScriptTask) a.getRef(0)).runs());
        b.bindGetter(LOCATION, (NativeFunction.OfRef) a -> ((ScriptTask) a.getRef(0)).location());
        b.bind(TO_STRING, (NativeFunction.OfRef) a -> a.getRef(0).toString());
    }
}
