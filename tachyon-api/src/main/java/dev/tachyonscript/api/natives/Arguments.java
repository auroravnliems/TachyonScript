package dev.tachyonscript.api.natives;

/**
 * Read access to the arguments of a native call.
 *
 * <p>The runtime passes a view over the caller's registers instead of an argument array,
 * so calling a native allocates nothing. Arguments are indexed like the parameters of the
 * {@code NativeDeclaration}: for members, index 0 is the receiver. Each accessor must match
 * the declared parameter's representation ({@code getInt} for {@code int}, {@code getRef}
 * for reference types and nullable primitives, {@code getLong} for {@code long} and
 * {@code Duration}); the compiler guarantees argument types, so natives may cast
 * reference arguments to their bound Java class without checking.
 *
 * <p>A view is only valid during the call that received it. Implementations must not
 * store it.
 */
public interface Arguments {

    /** Number of arguments. */
    int count();

    Object getRef(int index);

    int getInt(int index);

    long getLong(int index);

    float getFloat(int index);

    double getDouble(int index);

    boolean getBool(int index);

    /** Convenience for {@code (String) getRef(index)}. */
    default String getString(int index) {
        return (String) getRef(index);
    }
}
