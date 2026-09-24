package dev.tachyonscript.engine.addon;

import dev.tachyonscript.api.addon.TachyonAddon;
import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.type.ClassType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Combines the built-in declarations and bindings with addons into the registry and
 * bindings an engine runs with.
 *
 * <p>Addons are third-party code, so each one is checked in isolation, in registration
 * order, against everything accepted before it. An addon is rejected as a whole when its
 * name is taken, when {@code declare} or {@code bind} throws or conflicts with an existing
 * declaration or binding, when an operation or type it declares has no implementation, or
 * when an event it declares has no valid platform event class. A rejected addon leaves no
 * trace in the result.
 */
public final class AddonAssembly {

    /** An addon that was not loaded, and why. */
    public record Rejected(String addon, String reason) {
    }

    /**
     * The assembled registry and bindings.
     *
     * @param registry     built-in and accepted declarations
     * @param bindings     built-in and accepted implementations
     * @param loaded       names of the accepted addons, in registration order
     * @param rejected     addons that were not loaded
     * @param eventClasses platform event classes of the events declared by accepted addons
     */
    public record Result(SymbolRegistry registry, Bindings bindings, List<String> loaded, List<Rejected> rejected,
                         Map<EventDeclaration, Class<?>> eventClasses) {

        public Result {
            loaded = List.copyOf(loaded);
            rejected = List.copyOf(rejected);
            eventClasses = Map.copyOf(eventClasses);
        }
    }

    private AddonAssembly() {
    }

    /**
     * @param builtIn       registers the built-in declarations (the standard library)
     * @param builtInBindings the platform's implementations of the built-in declarations
     * @param addons        addons in registration order
     * @param isEventClass  whether a class can be used as a platform event class
     */
    public static Result assemble(Consumer<SymbolRegistry.Builder> builtIn, Bindings builtInBindings,
                                  List<TachyonAddon> addons, Predicate<Class<?>> isEventClass) {
        List<TachyonAddon> accepted = new ArrayList<>();
        List<String> loaded = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        Map<EventDeclaration, Class<?>> eventClasses = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();

        SymbolRegistry registry = registry(builtIn, accepted, null);
        Bindings bindings = builtInBindings;
        for (TachyonAddon addon : addons) {
            String name = safeName(addon);
            if (!names.add(name)) {
                rejected.add(new Rejected(name, "another addon with this name is already registered"));
                continue;
            }
            try {
                SymbolRegistry candidate = registry(builtIn, accepted, addon);
                Bindings.Builder own = Bindings.builder();
                addon.bind(own);
                Bindings ownBindings = own.build();
                String problem = checkImplementations(registry, candidate, ownBindings);
                Map<EventDeclaration, Class<?>> classes = Map.copyOf(Objects.requireNonNullElse(addon.eventClasses(), Map.of()));
                if (problem == null) {
                    problem = checkEvents(registry, candidate, classes, isEventClass);
                }
                if (problem != null) {
                    rejected.add(new Rejected(name, problem));
                    continue;
                }
                Bindings combined = Bindings.builder().include(bindings).include(ownBindings).build();
                accepted.add(addon);
                loaded.add(name);
                registry = candidate;
                bindings = combined;
                eventClasses.putAll(classes);
            } catch (RuntimeException | LinkageError e) {
                rejected.add(new Rejected(name, e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        }
        return new Result(registry, bindings, loaded, rejected, eventClasses);
    }

    /** A fresh registry: built-ins, the accepted addons, and optionally one more. */
    private static SymbolRegistry registry(Consumer<SymbolRegistry.Builder> builtIn, List<TachyonAddon> accepted,
                                           TachyonAddon extra) {
        SymbolRegistry.Builder builder = SymbolRegistry.builder();
        builtIn.accept(builder);
        for (TachyonAddon addon : accepted) {
            addon.declare(builder);
        }
        if (extra != null) {
            extra.declare(builder);
        }
        return builder.build();
    }

    /**
     * The addon's bindings must implement exactly what it added: every new operation and
     * type, and nothing else (in particular, no existing operation).
     */
    private static String checkImplementations(SymbolRegistry before, SymbolRegistry after, Bindings own) {
        Set<String> existing = new HashSet<>();
        for (NativeDeclaration declaration : before.natives()) {
            existing.add(declaration.key());
        }
        Set<String> added = new HashSet<>();
        List<String> missing = new ArrayList<>();
        for (NativeDeclaration declaration : after.natives()) {
            if (!existing.contains(declaration.key())) {
                added.add(declaration.key());
                if (own.lookup(declaration).isEmpty()) {
                    missing.add(declaration.key());
                }
            }
        }
        Set<ClassType> addedTypes = new HashSet<>();
        for (ClassType type : after.types()) {
            if (before.type(type.name()).isEmpty()) {
                addedTypes.add(type);
                if (own.typeClass(type).isEmpty()) {
                    missing.add("type " + type.name());
                }
            }
        }
        if (!missing.isEmpty()) {
            return "no implementation for " + String.join(", ", missing);
        }
        List<String> foreign = new ArrayList<>();
        for (String key : own.boundKeys()) {
            if (!added.contains(key)) {
                foreign.add(key);
            }
        }
        for (ClassType type : own.boundTypes()) {
            if (!addedTypes.contains(type)) {
                foreign.add("type " + type.name());
            }
        }
        if (!foreign.isEmpty()) {
            return "binds " + String.join(", ", foreign) + ", which this addon does not declare";
        }
        return null;
    }

    private static String checkEvents(SymbolRegistry before, SymbolRegistry after, Map<EventDeclaration, Class<?>> classes,
                                      Predicate<Class<?>> isEventClass) {
        for (EventDeclaration event : after.events()) {
            if (before.event(event.name()).isPresent()) {
                continue;
            }
            Class<?> eventClass = classes.get(event);
            if (eventClass == null) {
                return "no platform event class for event '" + event.name() + "'";
            }
            if (!isEventClass.test(eventClass)) {
                return eventClass.getName() + " cannot be used as the event class of '" + event.name() + "'";
            }
        }
        for (EventDeclaration event : classes.keySet()) {
            if (before.event(event.name()).isPresent() || after.event(event.name()).orElse(null) != event) {
                return "event class given for '" + event.name() + "', which this addon does not declare";
            }
        }
        return null;
    }

    private static String safeName(TachyonAddon addon) {
        try {
            String name = addon.name();
            return name == null || name.isBlank() ? addon.getClass().getName() : name;
        } catch (RuntimeException e) {
            return addon.getClass().getName();
        }
    }
}
