package dev.tachyonscript.language.semantic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A lexical scope of locals. Lookup walks outwards through parent scopes. */
final class Scope {

    private final Scope parent;
    private final Map<String, LocalSymbol> locals = new LinkedHashMap<>();

    Scope(Scope parent) {
        this.parent = parent;
    }

    Scope parent() {
        return parent;
    }

    LocalSymbol lookup(String name) {
        for (Scope scope = this; scope != null; scope = scope.parent) {
            LocalSymbol local = scope.locals.get(name);
            if (local != null) {
                return local;
            }
        }
        return null;
    }

    LocalSymbol lookupHere(String name) {
        return locals.get(name);
    }

    void declare(LocalSymbol local) {
        locals.put(local.name(), local);
    }

    Collection<LocalSymbol> locals() {
        return locals.values();
    }

    /** Names visible from this scope, innermost first (for suggestions). */
    List<String> visibleNames() {
        List<String> names = new ArrayList<>();
        for (Scope scope = this; scope != null; scope = scope.parent) {
            names.addAll(scope.locals.keySet());
        }
        return names;
    }
}
