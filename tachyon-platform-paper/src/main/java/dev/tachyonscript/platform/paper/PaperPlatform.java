package dev.tachyonscript.platform.paper;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.spi.EngineLogger;
import dev.tachyonscript.engine.spi.EventBridge;
import dev.tachyonscript.engine.spi.Platform;
import dev.tachyonscript.runtime.spi.TextService;
import dev.tachyonscript.stdlib.StandardLibrary;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The Paper and Folia platform. The same instance works on both: behaviour that differs
 * (where a write may run) is decided per call by {@link Threading} from the detected
 * {@link PlatformCapabilities}.
 *
 * <p>Creating the platform takes two steps so that addons can be checked against the
 * built-in bindings first: {@link #builtInBindings} gives the implementations of the
 * standard library, and the constructor takes the final registry and bindings.
 */
public final class PaperPlatform implements Platform {

    private final PlatformCapabilities capabilities;
    private final Bindings bindings;
    private final AdventureTextService text = new AdventureTextService();
    private final PaperEventBridge events;
    private final PaperLogger logger;

    /**
     * @param registry     the registry scripts are compiled against
     * @param bindings     implementations of everything in the registry
     * @param addonEvents  Bukkit event classes of events declared by addons
     */
    public PaperPlatform(Plugin plugin, PlatformCapabilities capabilities, SymbolRegistry registry, Bindings bindings,
                         Map<EventDeclaration, Class<?>> addonEvents) {
        this.capabilities = capabilities;
        this.bindings = bindings;
        this.events = new PaperEventBridge(plugin, registry, addonEvents);
        this.logger = new PaperLogger(plugin.getLogger());
    }

    /** Implementations of the standard library on this server. */
    public static Bindings builtInBindings(Plugin plugin, PlatformCapabilities capabilities, BooleanSupplier debug) {
        Threading threads = new BukkitThreading(plugin, capabilities.folia());
        return Bindings.builder()
                .include(StandardLibrary.coreBindings())
                .include(new PaperBindings(threads, plugin.getLogger(), debug).create())
                .build();
    }

    /** Whether {@code type} can be the event class of an addon event. */
    public static boolean isEventClass(Class<?> type) {
        return Event.class.isAssignableFrom(type) && type != Event.class;
    }

    /** Connects the event bridge to the engine that should receive events. */
    public void attach(ScriptEngine engine) {
        events.attach(engine);
    }

    /** Removes every listener (plugin disable). */
    public void shutdown() {
        events.unregisterAll();
    }

    public PlatformCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public Bindings bindings() {
        return bindings;
    }

    @Override
    public TextService text() {
        return text;
    }

    @Override
    public EventBridge events() {
        return events;
    }

    @Override
    public EngineLogger logger() {
        return logger;
    }
}
