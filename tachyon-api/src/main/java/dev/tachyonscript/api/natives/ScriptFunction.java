package dev.tachyonscript.api.natives;

/**
 * A function value created by a script, as received by a native: a lambda
 * ({@code p => p.level > 10}), a reference to a script function, or the body of a scheduled
 * block ({@code after 5 seconds { ... }}).
 *
 * <p>Calling it runs script code on the calling thread, under the same safety limits as any
 * other script execution (call depth, execution time). Primitive arguments and results are
 * boxed ({@code Integer}, {@code Double}, ...); a function whose declared result is
 * {@code void} returns {@code null}. The compiler guarantees argument types when scripts pass
 * functions to natives, so natives must pass arguments of the declared parameter types.
 *
 * <p>Errors raised by the script propagate as unchecked exceptions; natives should let them
 * pass so the script location is reported. A function value is immutable and may be called
 * from any thread, including concurrently.
 */
public interface ScriptFunction {

    /** Number of parameters. */
    int arity();

    Object invoke();

    Object invoke(Object argument);

    Object invoke(Object first, Object second);

    /** Calls the function with any number of arguments. */
    Object invokeWithArguments(Object... arguments);

    /** Where the function was defined, for messages, e.g. {@code lambda at shop.tys:12}. */
    String describe();
}
