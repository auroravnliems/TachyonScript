package dev.tachyonscript.runtime.value;

import dev.tachyonscript.ir.GlobalRef;

/**
 * The storage of one {@code playerdata var}: a value per player, created from the variable's
 * initial value the first time a player's value is read. Implemented by the engine, which
 * keeps the values in its data store.
 *
 * <p>Values are boxed ({@code Integer}, {@code Long}, ... or references). Implementations must
 * be thread-safe; {@link #add} must be atomic.
 */
public interface PlayerDataSlot {

    GlobalRef ref();

    /** The value of {@code player} (a player or offline player object of the platform). */
    Object get(Object player);

    void set(Object player, Object value);

    /** Atomically adds {@code deltaBits} (register encoding of the variable's type: int, long or double). */
    void add(Object player, long deltaBits);
}
