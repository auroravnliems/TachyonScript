package dev.tachyonscript.stdlib;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.stdlib.generated.GeneratedLibrary;

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
        GeneratedLibrary.registerTypes(builder);
        DatabaseApi.TYPES.forEach(builder::type);
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
        SchedulingApi.FUNCTIONS.forEach(builder::function);
        SchedulingApi.PROPERTIES.forEach(builder::property);
        DatabaseApi.PROPERTIES.forEach(builder::property);
        DatabaseApi.FUNCTIONS.forEach(builder::function);
        GeneratedLibrary.registerMembers(builder);
    }

    /**
     * Declarations the engine implements itself (databases), so platforms must not bind them.
     * Together with {@link #coreBindings()} and a platform's bindings they cover the whole library.
     */
    public static List<NativeDeclaration> engineDeclarations() {
        return DatabaseApi.natives();
    }

    /** Types whose values the engine creates ({@code Database}, {@code Row}, {@code Sql}). */
    public static List<ClassType> engineTypes() {
        return DatabaseApi.TYPES;
    }

    /** A registry containing only the standard library. */
    public static SymbolRegistry registry() {
        SymbolRegistry.Builder builder = SymbolRegistry.builder();
        register(builder);
        return builder.build();
    }

    /** Implementations that need no server: {@code math}, {@code string}, time, formatting, random numbers, JSON. */
    public static Bindings coreBindings() {
        Bindings.Builder builder = Bindings.builder();
        MathApi.bind(builder);
        StringApi.bind(builder);
        SchedulingApi.bind(builder);
        builder.bindType(dev.tachyonscript.api.type.Types.STRING, String.class);
        builder.bindType(MinecraftTypes.UUID, java.util.UUID.class);
        builder.bindType(dev.tachyonscript.api.type.Types.EXCEPTION, dev.tachyonscript.api.value.ScriptFailure.class);
        builder.bindCodec(MinecraftTypes.UUID, new dev.tachyonscript.api.storage.Codec() {
            @Override
            public String encode(Object value) {
                return value.toString();
            }

            @Override
            public Object decode(String text) {
                return java.util.UUID.fromString(text);
            }
        });
        builder.bind(EntityApi.UUID_TO_STRING, (dev.tachyonscript.api.natives.NativeFunction.OfRef) a -> a.getRef(0).toString());
        GeneratedLibrary.bindCore(builder);
        return builder.build();
    }

    /** Declarations the platform must bind (everything neither the core bindings nor the engine cover). */
    public static List<NativeDeclaration> platformDeclarations() {
        Bindings core = coreBindings();
        java.util.Set<String> engine = new java.util.HashSet<>();
        engineDeclarations().forEach(declaration -> engine.add(declaration.key()));
        List<NativeDeclaration> result = new ArrayList<>();
        for (NativeDeclaration declaration : registry().natives()) {
            if (!declaration.isIntrinsic() && core.lookup(declaration).isEmpty() && !engine.contains(declaration.key())) {
                result.add(declaration);
            }
        }
        return result;
    }
}
