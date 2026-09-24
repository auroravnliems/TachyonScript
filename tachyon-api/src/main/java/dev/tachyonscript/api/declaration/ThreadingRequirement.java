package dev.tachyonscript.api.declaration;

/**
 * Which thread an operation must run on.
 *
 * <p>On Paper the tick-thread requirements ({@link #GLOBAL}, {@link #ENTITY}, {@link #REGION})
 * all mean "the main server thread". On Folia they select the global region thread, the
 * thread owning an entity, or the thread owning a location. Declaring the requirement lets
 * the runtime choose a correct execution strategy without scripts knowing about regions.
 */
public enum ThreadingRequirement {
    /** Safe to call from any thread. */
    ANY,
    /** Must run on the global region (the main thread on Paper). */
    GLOBAL,
    /** Must run on the thread that owns the entity passed as the threading parameter. */
    ENTITY,
    /** Must run on the thread that owns the location passed as the threading parameter. */
    REGION,
    /** Must not run on a tick thread, typically because it blocks on I/O. */
    ASYNC
}
