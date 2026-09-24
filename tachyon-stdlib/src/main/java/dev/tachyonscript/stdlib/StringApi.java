package dev.tachyonscript.stdlib;

import dev.tachyonscript.api.declaration.Effect;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.type.Types;

import java.util.List;
import java.util.Locale;

/** Members of {@code string}. All are pure and implemented here. */
public final class StringApi {

    public static final PropertyDeclaration LENGTH = PropertyDeclaration.member(Types.STRING, "length", Types.INT)
            .getterEffects(Effect.PURE).doc("Number of characters.").build();
    public static final PropertyDeclaration IS_EMPTY = PropertyDeclaration.member(Types.STRING, "isEmpty", Types.BOOL)
            .getterEffects(Effect.PURE).doc("Whether the text is empty.").build();
    public static final FunctionDeclaration UPPER = noArgs("upper", Types.STRING, "Upper-case copy.");
    public static final FunctionDeclaration LOWER = noArgs("lower", Types.STRING, "Lower-case copy.");
    public static final FunctionDeclaration TRIM = noArgs("trim", Types.STRING, "Copy without leading and trailing spaces.");
    public static final FunctionDeclaration CONTAINS = oneString("contains", Types.BOOL, "Whether the text contains another text.");
    public static final FunctionDeclaration STARTS_WITH = oneString("startsWith", Types.BOOL, "Whether the text starts with a prefix.");
    public static final FunctionDeclaration ENDS_WITH = oneString("endsWith", Types.BOOL, "Whether the text ends with a suffix.");
    public static final FunctionDeclaration REPLACE = FunctionDeclaration.method(Types.STRING, "replace")
            .parameter("target", Types.STRING).parameter("replacement", Types.STRING).returns(Types.STRING)
            .effects(Effect.PURE).doc("Copy with every occurrence of target replaced.").build();
    public static final FunctionDeclaration TO_INT = noArgs("toInt", Types.nullable(Types.INT),
            "The text as a whole number, or null if it is not one.");
    public static final FunctionDeclaration TO_DOUBLE = noArgs("toDouble", Types.nullable(Types.DOUBLE),
            "The text as a number, or null if it is not one.");

    public static final List<PropertyDeclaration> PROPERTIES = List.of(LENGTH, IS_EMPTY);
    public static final List<FunctionDeclaration> FUNCTIONS = List.of(UPPER, LOWER, TRIM, CONTAINS, STARTS_WITH,
            ENDS_WITH, REPLACE, TO_INT, TO_DOUBLE);

    private StringApi() {
    }

    static void bind(Bindings.Builder bindings) {
        bindings.bindGetter(LENGTH, (NativeFunction.OfInt) a -> a.getString(0).length());
        bindings.bindGetter(IS_EMPTY, (NativeFunction.OfBool) a -> a.getString(0).isEmpty());
        bindings.bind(UPPER, (NativeFunction.OfRef) a -> a.getString(0).toUpperCase(Locale.ROOT));
        bindings.bind(LOWER, (NativeFunction.OfRef) a -> a.getString(0).toLowerCase(Locale.ROOT));
        bindings.bind(TRIM, (NativeFunction.OfRef) a -> a.getString(0).strip());
        bindings.bind(CONTAINS, (NativeFunction.OfBool) a -> a.getString(0).contains(a.getString(1)));
        bindings.bind(STARTS_WITH, (NativeFunction.OfBool) a -> a.getString(0).startsWith(a.getString(1)));
        bindings.bind(ENDS_WITH, (NativeFunction.OfBool) a -> a.getString(0).endsWith(a.getString(1)));
        bindings.bind(REPLACE, (NativeFunction.OfRef) a -> a.getString(0).replace(a.getString(1), a.getString(2)));
        bindings.bind(TO_INT, (NativeFunction.OfRef) a -> {
            try {
                return Integer.valueOf(a.getString(0).strip());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        });
        bindings.bind(TO_DOUBLE, (NativeFunction.OfRef) a -> {
            String text = a.getString(0).strip();
            // Only plain decimal notation: Java would also accept "NaN", "Infinity", hex floats
            // and type suffixes such as "1d".
            return isPlainDecimal(text) ? Double.valueOf(text) : null;
        });
    }

    /**
     * Whether {@code text} is plain decimal notation, {@code [+-]? (digits [. digits] | . digits)
     * ([eE] [+-]? digits)?} with ASCII digits. Hand-written: this runs in scripts, where a
     * regular expression would be needlessly slow.
     */
    static boolean isPlainDecimal(String text) {
        int length = text.length();
        int i = 0;
        if (i < length && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
            i++;
        }
        int start = i;
        while (i < length && isDigit(text.charAt(i))) {
            i++;
        }
        int digits = i - start;
        if (i < length && text.charAt(i) == '.') {
            i++;
            int fraction = i;
            while (i < length && isDigit(text.charAt(i))) {
                i++;
            }
            digits += i - fraction;
        }
        if (digits == 0) {
            return false;
        }
        if (i < length && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            i++;
            if (i < length && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
                i++;
            }
            int exponent = i;
            while (i < length && isDigit(text.charAt(i))) {
                i++;
            }
            if (i == exponent) {
                return false;
            }
        }
        return i == length;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static FunctionDeclaration noArgs(String name, dev.tachyonscript.api.type.Type returns, String summary) {
        return FunctionDeclaration.method(Types.STRING, name).returns(returns).effects(Effect.PURE).doc(summary).build();
    }

    private static FunctionDeclaration oneString(String name, dev.tachyonscript.api.type.Type returns, String summary) {
        return FunctionDeclaration.method(Types.STRING, name).parameter("text", Types.STRING).returns(returns)
                .effects(Effect.PURE).doc(summary).build();
    }
}
