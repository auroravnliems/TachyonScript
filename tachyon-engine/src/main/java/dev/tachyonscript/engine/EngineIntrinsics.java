package dev.tachyonscript.engine;

import dev.tachyonscript.api.intrinsic.Intrinsics;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.runtime.interpreter.Closure;

/**
 * The built-in operations implemented by the engine: the scheduling blocks {@code after},
 * {@code every}, {@code async} and {@code sync}. Every block belongs to the script that created
 * it and is cancelled when that script is reloaded or removed.
 */
final class EngineIntrinsics {

    private EngineIntrinsics() {
    }

    static Bindings create(ScriptEngine engine) {
        Bindings.Builder b = Bindings.builder();
        b.bind(Intrinsics.SCHEDULE_AFTER, (NativeFunction.OfVoid) a ->
                engine.after(null, a.getLong(0), (Closure) a.getRef(1)));
        b.bind(Intrinsics.SCHEDULE_AFTER_FOR, (NativeFunction.OfVoid) a ->
                engine.after(a.getRef(0), a.getLong(1), (Closure) a.getRef(2)));
        b.bind(Intrinsics.SCHEDULE_EVERY, (NativeFunction.OfVoid) a ->
                engine.every(null, a.getLong(0), (Closure) a.getRef(1)));
        b.bind(Intrinsics.SCHEDULE_EVERY_FOR, (NativeFunction.OfVoid) a ->
                engine.every(a.getRef(0), a.getLong(1), (Closure) a.getRef(2)));
        b.bind(Intrinsics.SCHEDULE_ASYNC, (NativeFunction.OfVoid) a -> engine.async((Closure) a.getRef(0)));
        b.bind(Intrinsics.SCHEDULE_SYNC, (NativeFunction.OfVoid) a -> engine.sync((Closure) a.getRef(0)));
        return b.build();
    }
}
