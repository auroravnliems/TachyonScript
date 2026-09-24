package dev.tachyonscript.stdlib;

import dev.tachyonscript.api.declaration.Effect;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Types;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** The {@code math} namespace. All functions are implemented here; none needs the server. */
public final class MathApi {

    public static final FunctionDeclaration ABS_INT = unary("abs", Types.INT, "Absolute value.");
    public static final FunctionDeclaration ABS_LONG = unary("abs", Types.LONG, "Absolute value.");
    public static final FunctionDeclaration ABS_DOUBLE = unary("abs", Types.DOUBLE, "Absolute value.");
    public static final FunctionDeclaration MIN_INT = binary("min", Types.INT, "The smaller value.");
    public static final FunctionDeclaration MIN_LONG = binary("min", Types.LONG, "The smaller value.");
    public static final FunctionDeclaration MIN_DOUBLE = binary("min", Types.DOUBLE, "The smaller value.");
    public static final FunctionDeclaration MAX_INT = binary("max", Types.INT, "The larger value.");
    public static final FunctionDeclaration MAX_LONG = binary("max", Types.LONG, "The larger value.");
    public static final FunctionDeclaration MAX_DOUBLE = binary("max", Types.DOUBLE, "The larger value.");
    public static final FunctionDeclaration CLAMP_INT = clamp(Types.INT);
    public static final FunctionDeclaration CLAMP_DOUBLE = clamp(Types.DOUBLE);
    public static final FunctionDeclaration FLOOR = rounding("floor", "Largest whole number not greater than the value.");
    public static final FunctionDeclaration CEIL = rounding("ceil", "Smallest whole number not less than the value.");
    public static final FunctionDeclaration ROUND = rounding("round", "Nearest whole number (halves round up).");
    public static final FunctionDeclaration SQRT = unary("sqrt", Types.DOUBLE, "Square root.");
    public static final FunctionDeclaration SIN = unary("sin", Types.DOUBLE, "Sine of an angle in radians.");
    public static final FunctionDeclaration COS = unary("cos", Types.DOUBLE, "Cosine of an angle in radians.");
    public static final FunctionDeclaration TAN = unary("tan", Types.DOUBLE, "Tangent of an angle in radians.");
    public static final FunctionDeclaration POW = FunctionDeclaration.global("math.pow")
            .parameter("base", Types.DOUBLE).parameter("exponent", Types.DOUBLE).returns(Types.DOUBLE)
            .effects(Effect.PURE).doc("base raised to the power exponent.").build();
    public static final FunctionDeclaration RANDOM = FunctionDeclaration.global("math.random").returns(Types.DOUBLE)
            .doc("A random number from 0 (inclusive) to 1 (exclusive).").build();
    public static final FunctionDeclaration RANDOM_INT = FunctionDeclaration.global("math.randomInt")
            .parameter("min", Types.INT).parameter("max", Types.INT).returns(Types.INT)
            .doc("A random whole number from min to max, both inclusive.").build();
    public static final PropertyDeclaration PI = PropertyDeclaration.global("math.pi", Types.DOUBLE)
            .getterEffects(Effect.PURE).doc("The ratio of a circle's circumference to its diameter.").build();

    public static final List<FunctionDeclaration> FUNCTIONS = List.of(ABS_INT, ABS_LONG, ABS_DOUBLE, MIN_INT, MIN_LONG,
            MIN_DOUBLE, MAX_INT, MAX_LONG, MAX_DOUBLE, CLAMP_INT, CLAMP_DOUBLE, FLOOR, CEIL, ROUND, SQRT, SIN, COS, TAN, POW,
            RANDOM, RANDOM_INT);

    public static final List<PropertyDeclaration> PROPERTIES = List.of(PI);

    private MathApi() {
    }

    static void bind(Bindings.Builder bindings) {
        bindings.bind(ABS_INT, (NativeFunction.OfInt) a -> Math.abs(a.getInt(0)));
        bindings.bind(ABS_LONG, (NativeFunction.OfLong) a -> Math.abs(a.getLong(0)));
        bindings.bind(ABS_DOUBLE, (NativeFunction.OfDouble) a -> Math.abs(a.getDouble(0)));
        bindings.bind(MIN_INT, (NativeFunction.OfInt) a -> Math.min(a.getInt(0), a.getInt(1)));
        bindings.bind(MIN_LONG, (NativeFunction.OfLong) a -> Math.min(a.getLong(0), a.getLong(1)));
        bindings.bind(MIN_DOUBLE, (NativeFunction.OfDouble) a -> Math.min(a.getDouble(0), a.getDouble(1)));
        bindings.bind(MAX_INT, (NativeFunction.OfInt) a -> Math.max(a.getInt(0), a.getInt(1)));
        bindings.bind(MAX_LONG, (NativeFunction.OfLong) a -> Math.max(a.getLong(0), a.getLong(1)));
        bindings.bind(MAX_DOUBLE, (NativeFunction.OfDouble) a -> Math.max(a.getDouble(0), a.getDouble(1)));
        bindings.bind(CLAMP_INT, (NativeFunction.OfInt) a -> {
            int min = a.getInt(1);
            int max = a.getInt(2);
            if (min > max) {
                throw new ScriptError("math.clamp: min (" + min + ") is greater than max (" + max + ")");
            }
            return Math.max(min, Math.min(max, a.getInt(0)));
        });
        bindings.bind(CLAMP_DOUBLE, (NativeFunction.OfDouble) a -> {
            double min = a.getDouble(1);
            double max = a.getDouble(2);
            if (min > max) {
                throw new ScriptError("math.clamp: min (" + min + ") is greater than max (" + max + ")");
            }
            return Math.max(min, Math.min(max, a.getDouble(0)));
        });
        // double -> int casts saturate (and map NaN to 0), so huge values never wrap around.
        bindings.bind(FLOOR, (NativeFunction.OfInt) a -> (int) Math.floor(a.getDouble(0)));
        bindings.bind(CEIL, (NativeFunction.OfInt) a -> (int) Math.ceil(a.getDouble(0)));
        bindings.bind(ROUND, (NativeFunction.OfInt) a -> (int) Math.floor(a.getDouble(0) + 0.5));
        bindings.bind(SQRT, (NativeFunction.OfDouble) a -> Math.sqrt(a.getDouble(0)));
        bindings.bind(SIN, (NativeFunction.OfDouble) a -> Math.sin(a.getDouble(0)));
        bindings.bind(COS, (NativeFunction.OfDouble) a -> Math.cos(a.getDouble(0)));
        bindings.bind(TAN, (NativeFunction.OfDouble) a -> Math.tan(a.getDouble(0)));
        bindings.bind(POW, (NativeFunction.OfDouble) a -> Math.pow(a.getDouble(0), a.getDouble(1)));
        bindings.bind(RANDOM, (NativeFunction.OfDouble) a -> ThreadLocalRandom.current().nextDouble());
        bindings.bind(RANDOM_INT, (NativeFunction.OfInt) a -> {
            int min = a.getInt(0);
            int max = a.getInt(1);
            if (min > max) {
                throw new ScriptError("math.randomInt: min (" + min + ") is greater than max (" + max + ")");
            }
            return (int) ThreadLocalRandom.current().nextLong(min, (long) max + 1);
        });
        bindings.bindGetter(PI, (NativeFunction.OfDouble) a -> Math.PI);
    }

    private static FunctionDeclaration unary(String name, PrimitiveType type, String summary) {
        return FunctionDeclaration.global("math." + name).parameter("value", type).returns(type)
                .effects(Effect.PURE).doc(summary).build();
    }

    private static FunctionDeclaration binary(String name, PrimitiveType type, String summary) {
        return FunctionDeclaration.global("math." + name).parameter("a", type).parameter("b", type).returns(type)
                .effects(Effect.PURE).doc(summary).build();
    }

    private static FunctionDeclaration clamp(PrimitiveType type) {
        return FunctionDeclaration.global("math.clamp").parameter("value", type).parameter("min", type)
                .parameter("max", type).returns(type).effects(Effect.PURE)
                .doc("The value limited to the range min..max.").build();
    }

    private static FunctionDeclaration rounding(String name, String summary) {
        return FunctionDeclaration.global("math." + name).parameter("value", Types.DOUBLE).returns(Types.INT)
                .effects(Effect.PURE).doc(summary).build();
    }
}
