package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.Parameter;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds members of class types through their linearization (most specific type first).
 *
 * <p>Methods with the same name are collected from every type in the linearization, so
 * overloads declared on supertypes stay visible; a method whose parameter types match one
 * already found on a more specific type is treated as overridden and skipped. A property is
 * found on the most specific type declaring it.
 */
final class MemberLookup {

    /** Result of looking up a member name. Exactly one of the fields is set, or neither. */
    record Result(PropertyDeclaration property, List<FunctionDeclaration> methods) {
        boolean found() {
            return property != null || !methods.isEmpty();
        }
    }

    private final SymbolRegistry registry;

    MemberLookup(SymbolRegistry registry) {
        this.registry = registry;
    }

    Result lookup(ClassType type, String name) {
        List<FunctionDeclaration> methods = new ArrayList<>();
        for (ClassType candidate : type.linearization()) {
            if (methods.isEmpty()) {
                var property = registry.declaredProperty(candidate, name);
                if (property.isPresent()) {
                    return new Result(property.get(), List.of());
                }
            }
            for (FunctionDeclaration method : registry.declaredMethods(candidate, name)) {
                if (methods.stream().noneMatch(existing -> sameParameters(existing, method))) {
                    methods.add(method);
                }
            }
        }
        return new Result(null, List.copyOf(methods));
    }

    /** All member names visible on {@code type}, for suggestions. */
    Set<String> memberNames(ClassType type) {
        Set<String> names = new TreeSet<>();
        for (ClassType candidate : type.linearization()) {
            names.addAll(registry.declaredMemberNames(candidate));
        }
        return names;
    }

    private static boolean sameParameters(FunctionDeclaration a, FunctionDeclaration b) {
        List<Parameter> pa = a.parameters();
        List<Parameter> pb = b.parameters();
        if (pa.size() != pb.size()) {
            return false;
        }
        for (int i = 0; i < pa.size(); i++) {
            Type ta = pa.get(i).type();
            if (!ta.equals(pb.get(i).type())) {
                return false;
            }
        }
        return true;
    }
}
