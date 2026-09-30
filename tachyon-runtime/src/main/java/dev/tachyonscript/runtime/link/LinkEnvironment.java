package dev.tachyonscript.runtime.link;

import dev.tachyonscript.ir.FunctionRef;
import dev.tachyonscript.ir.GlobalRef;
import dev.tachyonscript.ir.RecordRef;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;
import dev.tachyonscript.runtime.value.GlobalCell;
import dev.tachyonscript.runtime.value.PlayerDataSlot;
import dev.tachyonscript.runtime.value.RecordType;

/**
 * What a module is linked into: the storage of top-level variables, player data and record
 * types, and the modules it imports. The engine implements it with persistent storage and
 * script instances; {@link StandaloneEnvironment} keeps everything in memory for tools and
 * tests.
 *
 * <p>Every method returns {@code null} for something it cannot provide; the linker reports
 * that as a problem.
 */
public interface LinkEnvironment {

    /** The owner of the functions being linked (the engine's script instance), or {@code null}. */
    Object owner();

    /** The cell of a {@code let}, {@code var} or {@code persistent var}, of the linked module or an imported one. */
    GlobalCell global(GlobalRef ref);

    /** The slot of a {@code playerdata var}, of the linked module or an imported one. */
    PlayerDataSlot playerData(GlobalRef ref);

    /** The descriptor of a record type, of the linked module or an imported one. */
    RecordType record(RecordRef ref);

    /** A function of an imported module (never called for functions of the module being linked). */
    CompiledFunction function(FunctionRef ref);
}
