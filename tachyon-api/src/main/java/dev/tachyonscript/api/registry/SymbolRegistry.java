package dev.tachyonscript.api.registry;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.declaration.EventVariable;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.declaration.Parameter;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.ListType;
import dev.tachyonscript.api.type.MapType;
import dev.tachyonscript.api.type.NullableType;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.type.Types;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable snapshot of every declaration visible to scripts: types, global functions and
 * properties, members, and events.
 *
 * <p>A registry is built once (standard library first, then addons), frozen, and shared by
 * all compilations. It is safe to use from any thread. Iteration orders are registration
 * orders, which keeps compiler output deterministic.
 */
public final class SymbolRegistry {

    /** Type names reserved by the language itself. */
    private static final Set<String> RESERVED_TYPE_NAMES =
            Set.of("int", "long", "float", "double", "bool", "void", "Duration", "List", "Map", "null");

    private final Map<String, ClassType> types;
    private final Map<String, List<FunctionDeclaration>> functions;
    private final Map<String, PropertyDeclaration> globalProperties;
    private final Map<String, Set<String>> namespaceMembers;
    private final Map<ClassType, Map<String, List<FunctionDeclaration>>> methods;
    private final Map<ClassType, Map<String, PropertyDeclaration>> properties;
    private final Map<String, EventDeclaration> events;
    private final Map<EventDeclaration, Integer> eventIndices;
    private final List<NativeDeclaration> natives;

    private SymbolRegistry(Builder builder) {
        this.types = Collections.unmodifiableMap(new LinkedHashMap<>(builder.types));
        Map<String, List<FunctionDeclaration>> functionCopy = new LinkedHashMap<>();
        builder.functions.forEach((name, overloads) -> functionCopy.put(name, List.copyOf(overloads)));
        this.functions = Collections.unmodifiableMap(functionCopy);
        this.globalProperties = Collections.unmodifiableMap(new LinkedHashMap<>(builder.globalProperties));
        Map<ClassType, Map<String, List<FunctionDeclaration>>> methodCopy = new LinkedHashMap<>();
        builder.methods.forEach((owner, byName) -> {
            Map<String, List<FunctionDeclaration>> inner = new LinkedHashMap<>();
            byName.forEach((name, overloads) -> inner.put(name, List.copyOf(overloads)));
            methodCopy.put(owner, Collections.unmodifiableMap(inner));
        });
        this.methods = Collections.unmodifiableMap(methodCopy);
        Map<ClassType, Map<String, PropertyDeclaration>> propertyCopy = new LinkedHashMap<>();
        builder.properties.forEach((owner, byName) ->
                propertyCopy.put(owner, Collections.unmodifiableMap(new LinkedHashMap<>(byName))));
        this.properties = Collections.unmodifiableMap(propertyCopy);
        this.events = Collections.unmodifiableMap(new LinkedHashMap<>(builder.events));
        Map<EventDeclaration, Integer> indices = new LinkedHashMap<>();
        for (EventDeclaration event : events.values()) {
            indices.put(event, indices.size());
        }
        this.eventIndices = Collections.unmodifiableMap(indices);
        this.natives = List.copyOf(builder.natives.values());
        this.namespaceMembers = computeNamespaces();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---------------------------------------------------------------- types

    /** Looks up a class type by name (including the built-ins {@code any}, {@code string}, {@code Component}). */
    public Optional<ClassType> type(String name) {
        return Optional.ofNullable(types.get(name));
    }

    public Collection<ClassType> types() {
        return types.values();
    }

    /** Whether {@code name} is reserved for a language-level type ({@code int}, {@code List}, ...). */
    public static boolean isReservedTypeName(String name) {
        return RESERVED_TYPE_NAMES.contains(name);
    }

    // ------------------------------------------------------------- globals

    /** Overloads of the global function with the given qualified name; empty if none. */
    public List<FunctionDeclaration> functions(String qualifiedName) {
        return functions.getOrDefault(qualifiedName, List.of());
    }

    public Optional<PropertyDeclaration> globalProperty(String qualifiedName) {
        return Optional.ofNullable(globalProperties.get(qualifiedName));
    }

    /** Whether {@code qualifiedName} is a namespace (a prefix of some global function or property). */
    public boolean isNamespace(String qualifiedName) {
        return !qualifiedName.isEmpty() && namespaceMembers.containsKey(qualifiedName);
    }

    /**
     * Simple names declared directly in a namespace (functions, properties and nested
     * namespaces), sorted. Use {@code ""} for the root namespace.
     */
    public Set<String> namespaceMembers(String namespace) {
        return namespaceMembers.getOrDefault(namespace, Set.of());
    }

    // ------------------------------------------------------------- members

    /** Method overloads declared directly on {@code owner} (not inherited). */
    public List<FunctionDeclaration> declaredMethods(ClassType owner, String name) {
        Map<String, List<FunctionDeclaration>> byName = methods.get(owner);
        return byName == null ? List.of() : byName.getOrDefault(name, List.of());
    }

    /** Property declared directly on {@code owner} (not inherited). */
    public Optional<PropertyDeclaration> declaredProperty(ClassType owner, String name) {
        Map<String, PropertyDeclaration> byName = properties.get(owner);
        return byName == null ? Optional.empty() : Optional.ofNullable(byName.get(name));
    }

    /** Names of all members declared directly on {@code owner}. */
    public Set<String> declaredMemberNames(ClassType owner) {
        Set<String> names = new TreeSet<>();
        Map<String, List<FunctionDeclaration>> byName = methods.get(owner);
        if (byName != null) {
            names.addAll(byName.keySet());
        }
        Map<String, PropertyDeclaration> props = properties.get(owner);
        if (props != null) {
            names.addAll(props.keySet());
        }
        return names;
    }

    // -------------------------------------------------------------- events

    public Optional<EventDeclaration> event(String name) {
        return Optional.ofNullable(events.get(name));
    }

    public Collection<EventDeclaration> events() {
        return events.values();
    }

    /** Names of all registered events, in registration order. */
    public Set<String> eventNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(events.keySet()));
    }

    /**
     * Dense index of an event in this registry ({@code 0..events().size()-1}). Runtime
     * handler tables are arrays indexed by it, so dispatch needs no map lookup.
     */
    public int eventIndex(EventDeclaration event) {
        Integer index = eventIndices.get(event);
        if (index == null) {
            throw new IllegalArgumentException(event + " is not registered in this registry");
        }
        return index;
    }

    // ------------------------------------------------------------- natives

    /** Every bindable operation, in registration order. */
    public List<NativeDeclaration> natives() {
        return natives;
    }

    private Map<String, Set<String>> computeNamespaces() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(functions.keySet());
        names.addAll(globalProperties.keySet());
        for (String name : names) {
            String[] parts = name.split("\\.");
            String prefix = "";
            for (String part : parts) {
                result.computeIfAbsent(prefix, k -> new TreeSet<>()).add(part);
                prefix = prefix.isEmpty() ? part : prefix + "." + part;
            }
        }
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        result.forEach((ns, members) -> frozen.put(ns, Collections.unmodifiableSet(members)));
        return Collections.unmodifiableMap(frozen);
    }

    /**
     * Mutable builder. Each registration is validated completely before anything is
     * recorded, so a rejected registration leaves the builder unchanged.
     */
    public static final class Builder {
        private final Map<String, ClassType> types = new LinkedHashMap<>();
        private final Map<String, List<FunctionDeclaration>> functions = new LinkedHashMap<>();
        private final Map<String, PropertyDeclaration> globalProperties = new LinkedHashMap<>();
        private final Map<ClassType, Map<String, List<FunctionDeclaration>>> methods = new LinkedHashMap<>();
        private final Map<ClassType, Map<String, PropertyDeclaration>> properties = new LinkedHashMap<>();
        private final Map<String, EventDeclaration> events = new LinkedHashMap<>();
        private final Map<String, NativeDeclaration> natives = new LinkedHashMap<>();

        private Builder() {
            types.put(Types.ANY.name(), Types.ANY);
            types.put(Types.STRING.name(), Types.STRING);
            types.put(Types.COMPONENT.name(), Types.COMPONENT);
        }

        /** Registers a class type. Its supertypes must already be registered. */
        public Builder type(ClassType type) {
            if (RESERVED_TYPE_NAMES.contains(type.name())) {
                throw new RegistrationException("Type name '" + type.name() + "' is reserved by the language");
            }
            if (types.containsKey(type.name())) {
                throw new RegistrationException("Type '" + type.name() + "' is already registered");
            }
            for (ClassType supertype : type.supertypes()) {
                if (types.get(supertype.name()) != supertype) {
                    throw new RegistrationException("Supertype '" + supertype.name() + "' of '" + type.name()
                            + "' must be registered first");
                }
            }
            types.put(type.name(), type);
            return this;
        }

        /** Registers a global function or a method. */
        public Builder function(FunctionDeclaration function) {
            requireRegistered(function.returnType(), function);
            for (Parameter parameter : function.invocable().parameters()) {
                requireRegistered(parameter.type(), function);
            }
            requireUniqueNative(function.invocable(), "function " + function.signature());
            if (function.isMethod()) {
                ClassType owner = function.owner().orElseThrow();
                Map<String, PropertyDeclaration> ownerProperties = properties.get(owner);
                if (ownerProperties != null && ownerProperties.containsKey(function.name())) {
                    throw new RegistrationException("Method " + function.signature()
                            + " conflicts with property " + owner.name() + "." + function.name());
                }
                methods.computeIfAbsent(owner, k -> new LinkedHashMap<>())
                        .computeIfAbsent(function.name(), k -> new ArrayList<>())
                        .add(function);
            } else {
                String name = function.name();
                if (globalProperties.containsKey(name)) {
                    throw new RegistrationException("Function " + function.signature()
                            + " conflicts with global property " + name);
                }
                if (isPropertyPrefix(name)) {
                    throw new RegistrationException("Function " + function.signature()
                            + " is nested under a global property");
                }
                functions.computeIfAbsent(name, k -> new ArrayList<>()).add(function);
            }
            natives.put(function.invocable().key(), function.invocable());
            return this;
        }

        /** Registers a member or global property. */
        public Builder property(PropertyDeclaration property) {
            requireRegistered(property.type(), property);
            requireUniqueNative(property.getter(), "property " + property);
            property.setter().ifPresent(setter -> requireUniqueNative(setter, "property " + property));
            if (property.isGlobal()) {
                String name = property.name();
                if (functions.containsKey(name)) {
                    throw new RegistrationException("Global property " + name + " conflicts with a function of the same name");
                }
                for (String existing : functions.keySet()) {
                    if (existing.startsWith(name + ".")) {
                        throw new RegistrationException("Global property " + name + " conflicts with namespace " + name);
                    }
                }
                for (String existing : globalProperties.keySet()) {
                    if (existing.startsWith(name + ".") || name.startsWith(existing + ".")) {
                        throw new RegistrationException("Global property " + name + " conflicts with " + existing);
                    }
                }
                if (isPropertyPrefix(name)) {
                    throw new RegistrationException("Global property " + name + " is nested under another property");
                }
                globalProperties.put(name, property);
            } else {
                ClassType owner = property.owner().orElseThrow();
                requireRegistered(owner, property);
                Map<String, List<FunctionDeclaration>> ownerMethods = methods.get(owner);
                if (ownerMethods != null && ownerMethods.containsKey(property.name())) {
                    throw new RegistrationException("Property " + property + " conflicts with method "
                            + owner.name() + "." + property.name());
                }
                Map<String, PropertyDeclaration> existing = properties.get(owner);
                if (existing != null && existing.containsKey(property.name())) {
                    throw new RegistrationException("Property " + owner.name() + "." + property.name() + " is already registered");
                }
                properties.computeIfAbsent(owner, k -> new LinkedHashMap<>()).put(property.name(), property);
            }
            natives.put(property.getter().key(), property.getter());
            property.setter().ifPresent(setter -> natives.put(setter.key(), setter));
            return this;
        }

        /** Registers an event. */
        public Builder event(EventDeclaration event) {
            if (events.containsKey(event.name())) {
                throw new RegistrationException("Event '" + event.name() + "' is already registered");
            }
            requireRegistered(event.eventType(), event);
            for (EventVariable variable : event.variables()) {
                requireRegistered(variable.type(), event);
                requireUniqueNative(variable.getter(), event.toString());
            }
            events.put(event.name(), event);
            for (EventVariable variable : event.variables()) {
                natives.put(variable.getter().key(), variable.getter());
            }
            return this;
        }

        public SymbolRegistry build() {
            return new SymbolRegistry(this);
        }

        private boolean isPropertyPrefix(String qualifiedName) {
            for (String property : globalProperties.keySet()) {
                if (qualifiedName.startsWith(property + ".")) {
                    return true;
                }
            }
            return false;
        }

        private void requireUniqueNative(NativeDeclaration declaration, String what) {
            if (natives.containsKey(declaration.key())) {
                throw new RegistrationException("Duplicate signature for " + what + ": " + declaration.key());
            }
        }

        private void requireRegistered(Type type, Object owner) {
            switch (type) {
                case ClassType classType -> {
                    if (types.get(classType.name()) != classType) {
                        throw new RegistrationException(owner + " references unregistered type '" + classType.name() + "'");
                    }
                }
                case NullableType nullable -> requireRegistered(nullable.inner(), owner);
                case ListType list -> requireRegistered(list.element(), owner);
                case MapType map -> {
                    requireRegistered(map.key(), owner);
                    requireRegistered(map.value(), owner);
                }
                default -> {
                    // Primitive, null and error types need no registration.
                }
            }
        }
    }
}
