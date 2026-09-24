package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.type.Type;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Flow-sensitive typing facts: locals whose type is known to be narrower than declared at
 * the current point (after {@code x != null} or {@code x is Player}). Immutable; every
 * change returns a new instance, so branches can fork and merge states freely.
 */
final class Flow {

    static final Flow EMPTY = new Flow(Map.of());

    private final Map<LocalSymbol, Type> narrowed;

    private Flow(Map<LocalSymbol, Type> narrowed) {
        this.narrowed = narrowed;
    }

    /** Type of {@code local} at this point. */
    Type typeOf(LocalSymbol local) {
        return narrowed.getOrDefault(local, local.type());
    }

    boolean isNarrowed(LocalSymbol local) {
        return narrowed.containsKey(local);
    }

    Flow with(Map<LocalSymbol, Type> facts) {
        if (facts.isEmpty()) {
            return this;
        }
        Map<LocalSymbol, Type> copy = new HashMap<>(narrowed);
        copy.putAll(facts);
        return new Flow(Map.copyOf(copy));
    }

    Flow with(LocalSymbol local, Type type) {
        return with(Map.of(local, type));
    }

    Flow without(LocalSymbol local) {
        if (!narrowed.containsKey(local)) {
            return this;
        }
        Map<LocalSymbol, Type> copy = new HashMap<>(narrowed);
        copy.remove(local);
        return new Flow(Map.copyOf(copy));
    }

    /** Removes facts about locals with any of the given names (conservative loop handling). */
    Flow withoutNames(Collection<String> names) {
        if (names.isEmpty()) {
            return this;
        }
        Map<LocalSymbol, Type> copy = new HashMap<>(narrowed);
        copy.keySet().removeIf(local -> names.contains(local.name()));
        return new Flow(Map.copyOf(copy));
    }

    /** Facts that hold in both states (used where control flow merges). */
    Flow intersect(Flow other) {
        Map<LocalSymbol, Type> common = new HashMap<>();
        narrowed.forEach((local, type) -> {
            if (type.equals(other.narrowed.get(local))) {
                common.put(local, type);
            }
        });
        return new Flow(Map.copyOf(common));
    }
}
