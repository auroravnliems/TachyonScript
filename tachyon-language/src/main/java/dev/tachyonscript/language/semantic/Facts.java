package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.type.Type;

import java.util.HashMap;
import java.util.Map;

/**
 * Narrowing facts implied by a condition: what is known when it is true, and when it is false.
 */
record Facts(Map<LocalSymbol, Type> whenTrue, Map<LocalSymbol, Type> whenFalse) {

    static final Facts NONE = new Facts(Map.of(), Map.of());

    Facts negate() {
        return new Facts(whenFalse, whenTrue);
    }

    static Map<LocalSymbol, Type> union(Map<LocalSymbol, Type> a, Map<LocalSymbol, Type> b) {
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        Map<LocalSymbol, Type> merged = new HashMap<>(a);
        merged.putAll(b);
        return Map.copyOf(merged);
    }
}
