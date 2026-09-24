package dev.tachyonscript.api.natives;

/**
 * Implementation of a native operation.
 *
 * <p>There is one functional shape per result representation so that primitive results
 * never need boxing. The shape must match the declaration's return type: {@link OfInt} for
 * {@code int}, {@link OfLong} for {@code long} and {@code Duration}, {@link OfFloat},
 * {@link OfDouble}, {@link OfBool}, {@link OfVoid} for {@code void}, and {@link OfRef} for
 * every reference type including nullable primitives (returned boxed). Bindings are
 * validated against their declarations when they are registered.
 *
 * <p>Implementations should be stateless or thread-safe: on Folia the same native can be
 * called concurrently from several region threads. They report expected failures by
 * throwing {@link ScriptError}.
 */
public sealed interface NativeFunction
        permits NativeFunction.OfVoid, NativeFunction.OfInt, NativeFunction.OfLong, NativeFunction.OfFloat,
        NativeFunction.OfDouble, NativeFunction.OfBool, NativeFunction.OfRef {

    @FunctionalInterface
    non-sealed interface OfVoid extends NativeFunction {
        void call(Arguments args);
    }

    @FunctionalInterface
    non-sealed interface OfInt extends NativeFunction {
        int call(Arguments args);
    }

    @FunctionalInterface
    non-sealed interface OfLong extends NativeFunction {
        long call(Arguments args);
    }

    @FunctionalInterface
    non-sealed interface OfFloat extends NativeFunction {
        float call(Arguments args);
    }

    @FunctionalInterface
    non-sealed interface OfDouble extends NativeFunction {
        double call(Arguments args);
    }

    @FunctionalInterface
    non-sealed interface OfBool extends NativeFunction {
        boolean call(Arguments args);
    }

    @FunctionalInterface
    non-sealed interface OfRef extends NativeFunction {
        Object call(Arguments args);
    }
}
