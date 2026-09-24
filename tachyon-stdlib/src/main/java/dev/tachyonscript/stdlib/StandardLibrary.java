package dev.tachyonscript.stdlib;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.type.ClassType;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point of the standard library.
 *
 * <p>Declarations are registered by {@link #register}; {@link #coreBindings()} implements the
 * operations that need no server (math, strings). Everything else is bound by the platform;
 * {@link #platformDeclarations()} lists exactly what a platform must provide, so platforms
 * can test that their bindings are complete.
 */
public final class StandardLibrary {

    private StandardLibrary() {
    }

    /** Registers every standard declaration, in a fixed order. */
    public static void register(SymbolRegistry.Builder builder) {
        for (ClassType type : MinecraftTypes.ALL) {
            builder.type(type);
        }
        EntityApi.PROPERTIES.forEach(builder::property);
        EntityApi.FUNCTIONS.forEach(builder::function);
        WorldApi.PROPERTIES.forEach(builder::property);
        WorldApi.FUNCTIONS.forEach(builder::function);
        ServerApi.PROPERTIES.forEach(builder::property);
        ServerApi.FUNCTIONS.forEach(builder::function);
        ServerApi.LOG.forEach(builder::function);
        TextApi.FUNCTIONS.forEach(builder::function);
        MathApi.FUNCTIONS.forEach(builder::function);
        MathApi.PROPERTIES.forEach(builder::property);
        StringApi.PROPERTIES.forEach(builder::property);
        StringApi.FUNCTIONS.forEach(builder::function);
        EventApi.FUNCTIONS.forEach(builder::function);
        EventApi.PROPERTIES.forEach(builder::property);
        EventApi.EVENTS.forEach(builder::event);
    }

    /** A registry containing only the standard library. */
    public static SymbolRegistry registry() {
        SymbolRegistry.Builder builder = SymbolRegistry.builder();
        register(builder);
        return builder.build();
    }

    /** Implementations that need no server: {@code math} and {@code string} members. */
    public static Bindings coreBindings() {
        Bindings.Builder builder = Bindings.builder();
        MathApi.bind(builder);
        StringApi.bind(builder);
        builder.bindType(dev.tachyonscript.api.type.Types.STRING, String.class);
        return builder.build();
    }

    /** Declarations the platform must bind (everything the core bindings do not cover). */
    public static List<NativeDeclaration> platformDeclarations() {
        Bindings core = coreBindings();
        List<NativeDeclaration> result = new ArrayList<>();
        for (NativeDeclaration declaration : registry().natives()) {
            if (core.lookup(declaration).isEmpty()) {
                result.add(declaration);
            }
        }
        return result;
    }
}
