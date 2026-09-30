package dev.tachyonscript.runtime.value;

import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.ir.GlobalRef;
import dev.tachyonscript.runtime.error.ScriptRuntimeException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * The storage of one top-level variable ({@code let}, {@code var} or {@code persistent var}).
 *
 * <p>Primitive values are kept as {@code long} bits in the same encoding as interpreter
 * registers (ints sign-extended, floats and doubles as raw bits, booleans 0 or 1), so reading
 * and writing never boxes. Fields are volatile: a write on one thread is seen by handlers on
 * every other thread (Folia regions, asynchronous events). {@code +=} and {@code -=} are atomic.
 *
 * <p>A cell of a {@code persistent var} has a {@link Persistence} through which its saved
 * value is restored when the script loads.
 */
public final class GlobalCell {

    /** Restores the saved value of a {@code persistent var}. */
    public interface Persistence {
        /** Loads the saved value into {@code cell}; returns false if there is none. */
        boolean restore(GlobalCell cell);
    }

    private static final VarHandle PRIMITIVE;

    static {
        try {
            PRIMITIVE = MethodHandles.lookup().findVarHandle(GlobalCell.class, "primitive", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final GlobalRef ref;
    private final boolean reference;
    private final boolean nullable;
    private volatile long primitive;
    private volatile Object value;
    private volatile Persistence persistence;

    public GlobalCell(GlobalRef ref) {
        this.ref = ref;
        this.reference = ref.type().representation() == Representation.REF;
        this.nullable = ref.type().isNullable();
    }

    public GlobalRef ref() {
        return ref;
    }

    public boolean isReference() {
        return reference;
    }

    public void persistence(Persistence persistence) {
        this.persistence = persistence;
    }

    // ------------------------------------------------------------------ interpreter access

    public long getPrimitive() {
        return primitive;
    }

    public void setPrimitive(long bits) {
        primitive = bits;
    }

    /** The reference value; fails if a non-null variable is read before its initializer ran. */
    public Object getReference() {
        Object current = value;
        if (current == null && !nullable) {
            throw new ScriptRuntimeException(ScriptRuntimeException.Kind.NULL,
                    "'" + ref.name() + "' is used before it is initialized.", null);
        }
        return current;
    }

    public void setReference(Object newValue) {
        value = newValue;
    }

    /** Atomically adds {@code deltaBits} (register encoding of the variable's type: int, long or double). */
    public void add(long deltaBits) {
        PrimitiveType type = (PrimitiveType) ref.type();
        switch (type) {
            case INT -> {
                long old;
                do {
                    old = primitive;
                } while (!PRIMITIVE.compareAndSet(this, old, (long) ((int) old + (int) deltaBits)));
            }
            case LONG -> PRIMITIVE.getAndAdd(this, deltaBits);
            case DOUBLE -> {
                double delta = Double.longBitsToDouble(deltaBits);
                long old;
                do {
                    old = primitive;
                } while (!PRIMITIVE.compareAndSet(this, old, Double.doubleToRawLongBits(Double.longBitsToDouble(old) + delta)));
            }
            default -> throw new IllegalStateException("Cannot add to " + ref + " of type " + type.displayName());
        }
    }

    /** Loads the saved value of a persistent variable; false when there is none. */
    public boolean restore() {
        Persistence current = persistence;
        return current != null && current.restore(this);
    }

    // ------------------------------------------------------------------ boxed access (storage, tools)

    /** The value boxed ({@code Integer}, {@code Long}, ... or the reference), without the initialization check. */
    public Object boxed() {
        if (reference) {
            return value;
        }
        long bits = primitive;
        return switch (ref.type().representation()) {
            case INT -> (int) bits;
            case LONG -> bits;
            case FLOAT -> Float.intBitsToFloat((int) bits);
            case DOUBLE -> Double.longBitsToDouble(bits);
            case BOOL -> bits != 0;
            default -> throw new IllegalStateException(ref + " has no value");
        };
    }

    /** Sets the value from a boxed value of the variable's type. */
    public void setBoxed(Object boxed) {
        if (reference) {
            value = boxed;
            return;
        }
        primitive = switch (boxed) {
            case Integer i -> i;
            case Long l -> l;
            case Float f -> Float.floatToRawIntBits(f);
            case Double d -> Double.doubleToRawLongBits(d);
            case Boolean b -> b ? 1 : 0;
            default -> throw new IllegalArgumentException("Cannot store " + boxed + " in " + ref);
        };
    }

    @Override
    public String toString() {
        return ref.key() + " = " + boxed();
    }
}
