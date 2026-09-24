package dev.tachyonscript.platform.paper;

import org.bukkit.entity.Entity;

/**
 * Runs world-changing operations on the thread that owns the affected state.
 *
 * <p>Scripts do not know about regions. A handler may modify an entity it does not own
 * (another player on Folia, or anything from an asynchronous event on Paper); such writes
 * are forwarded to the owner and run as soon as it ticks. Writes on the owning thread run
 * immediately. Bindings never check threads themselves; they always go through this.
 */
interface Threading {

    /** Whether regions tick in parallel (Folia). */
    boolean folia();

    /** Runs {@code action} on the thread owning {@code entity}; skipped if the entity is removed first. */
    void forEntity(Entity entity, Runnable action);

    /** Runs {@code action} on the global region (the main thread on Paper). */
    void global(Runnable action);
}
