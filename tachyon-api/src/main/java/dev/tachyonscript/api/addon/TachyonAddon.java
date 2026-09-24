package dev.tachyonscript.api.addon;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;

import java.util.Map;

/**
 * An extension of the language: types, functions, properties and events that scripts can
 * use, together with their implementations.
 *
 * <p>An addon is registered through {@link AddonRegistrar} before scripts are first
 * loaded. TachyonScript then calls {@link #declare} and {@link #bind} once. An addon that
 * fails — a declaration conflicting with an existing one, a declared operation without an
 * implementation, an exception — is rejected as a whole with a message naming it; it never
 * affects the standard library or other addons.
 *
 * <p>Example:
 *
 * <pre>{@code
 * public final class CoinsAddon implements TachyonAddon {
 *     static final PropertyDeclaration COINS = PropertyDeclaration
 *             .member(MinecraftTypes.PLAYER, "coins", Types.INT).mutable().doc("Coins of the player.").build();
 *
 *     public String name() { return "Coins"; }
 *
 *     public void declare(SymbolRegistry.Builder registry) { registry.property(COINS); }
 *
 *     public void bind(Bindings.Builder bindings) {
 *         bindings.bindGetter(COINS, (NativeFunction.OfInt) a -> coins.get((Player) a.getRef(0)));
 *         bindings.bindSetter(COINS, a -> coins.set((Player) a.getRef(0), a.getInt(1)));
 *     }
 * }
 * }</pre>
 */
public interface TachyonAddon {

    /** Unique name, used in messages ({@code "Addon 'Coins' was rejected: ..."}). */
    String name();

    /**
     * Registers the declarations of this addon. It may be called more than once (each
     * addon is checked against fresh registries) and must register the same declaration
     * instances every time, typically constants of the addon class.
     */
    void declare(SymbolRegistry.Builder registry);

    /**
     * Implements every operation {@link #declare} registered and gives the Java class of
     * every type it registered.
     */
    void bind(Bindings.Builder bindings);

    /**
     * The platform's event class for each event this addon declares (on Paper, a subclass
     * of {@code org.bukkit.event.Event}). The platform listens to an event only while a
     * script handles it.
     */
    default Map<EventDeclaration, Class<?>> eventClasses() {
        return Map.of();
    }
}
