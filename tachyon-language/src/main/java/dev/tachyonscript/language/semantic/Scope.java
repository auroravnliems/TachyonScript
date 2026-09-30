package dev.tachyonscript.language.semantic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A lexical scope of locals. Lookup walks outwards through parent scopes.
 *
 * <p>The root scope of a lambda (or of a scheduled block) has a {@link Boundary}: a local found
 * outside it is not used directly but through a capture, a copy made when the lambda is
 * created. Nested lambdas capture through every boundary they cross.
 */
final class Scope {

    /** Turns locals found outside a lambda into the captures seen inside it. */
    interface Boundary {
        /**
         * The capture of {@code outer} inside the lambda (created on first use), or
         * {@code null} if it cannot be captured (an error has been reported).
         */
        LocalSymbol capture(LocalSymbol outer);
    }

    private final Scope parent;
    private final Map<String, LocalSymbol> locals = new LinkedHashMap<>();
    private final Boundary boundary;

    Scope(Scope parent) {
        this(parent, null);
    }

    Scope(Scope parent, Boundary boundary) {
        this.parent = parent;
        this.boundary = boundary;
    }

    Scope parent() {
        return parent;
    }

    /** Resolves {@code name}, capturing it if it is declared outside an enclosing lambda. */
    LocalSymbol lookup(String name) {
        LocalSymbol here = locals.get(name);
        if (here != null) {
            return here;
        }
        if (parent == null) {
            return null;
        }
        LocalSymbol outer = parent.lookup(name);
        if (outer == null || boundary == null) {
            return outer;
        }
        return boundary.capture(outer);
    }

    /** Resolves {@code name} without creating captures (for shadowing checks and suggestions). */
    LocalSymbol peek(String name) {
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
