package dev.tachyonscript.platform.paper;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.spi.ArgumentTypes;
import dev.tachyonscript.engine.spi.CommandRegistry;
import dev.tachyonscript.engine.spi.EngineLogger;
import dev.tachyonscript.engine.spi.EventBridge;
import dev.tachyonscript.engine.spi.Platform;
import dev.tachyonscript.engine.spi.PlayerDirectory;
import dev.tachyonscript.engine.spi.Scheduler;
import dev.tachyonscript.platform.paper.generated.GeneratedBindings;
import dev.tachyonscript.platform.paper.lib.MenuListener;
import dev.tachyonscript.runtime.spi.TextService;
import dev.tachyonscript.stdlib.StandardLibrary;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

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

    /** The binding context of each plugin instance, shared by its bindings and its platform. */
    private static final Map<Plugin, PaperContext> CONTEXTS = Collections.synchronizedMap(new WeakHashMap<>());

    private final Plugin plugin;
    private final PaperContext context;
    private final PlatformCapabilities capabilities;
    private final Bindings bindings;
    private final AdventureTextService text = new AdventureTextService();
    private final PaperEventBridge events;
    private final PaperLogger logger;
    private final PaperScheduler scheduler;
    private final PaperCommandRegistry commands;
    private final PaperArgumentTypes arguments;
    private final PlayerDirectory players = new PlayerDirectory() {
        @Override
        public UUID id(Object player) {
            return ((OfflinePlayer) player).getUniqueId();
        }

        @Override
        public String name(Object player) {
            OfflinePlayer offline = (OfflinePlayer) player;
            return offline.getName() != null ? offline.getName() : offline.getUniqueId().toString();
        }
    };

    /**
     * @param registry     the registry scripts are compiled against
     * @param bindings     implementations of everything in the registry
     * @param addonEvents  Bukkit event classes of events declared by addons
     */
    public PaperPlatform(Plugin plugin, PlatformCapabilities capabilities, SymbolRegistry registry, Bindings bindings,
                         Map<EventDeclaration, Class<?>> addonEvents) {
        this.plugin = plugin;
        this.context = context(plugin, capabilities);
        this.capabilities = capabilities;
        this.bindings = bindings;
        this.events = new PaperEventBridge(plugin, registry, PaperEvents.all(addonEvents));
        this.logger = new PaperLogger(plugin.getLogger());
        this.scheduler = new PaperScheduler(plugin, capabilities.folia());
        this.commands = new PaperCommandRegistry(plugin);
        this.arguments = new PaperArgumentTypes(registry, bindings);
    }

    /** Implementations of the standard library on this server. */
    public static Bindings builtInBindings(Plugin plugin, PlatformCapabilities capabilities, BooleanSupplier debug) {
        PaperContext context = context(plugin, capabilities);
        return builtInBindings(context, context.threads(), plugin.getLogger(), debug);
    }

    static Bindings builtInBindings(PaperContext context, Threading threads, Logger logger, BooleanSupplier debug) {
        Bindings.Builder builder = Bindings.builder()
                .include(StandardLibrary.coreBindings())
                .include(new PaperBindings(threads, logger, debug).create());
        GeneratedBindings.bind(builder, context);
        return builder.build();
    }

    private static PaperContext context(Plugin plugin, PlatformCapabilities capabilities) {
        return CONTEXTS.computeIfAbsent(plugin, p -> new PaperContext(p, new BukkitThreading(p, capabilities.folia())));
    }

    /**
     * Replaces the built-in constant tables (Material, Sound, ...) with the contents of the
     * server's registries, so constants added by data packs can be used too. Call it on the
     * registry builder after registering the standard library.
     */
    public static void useServerKeys(SymbolRegistry.Builder builder) {
        GeneratedBindings.liveKeys(builder);
    }

    /** Whether {@code type} can be the event class of an addon event. */
    public static boolean isEventClass(Class<?> type) {
        return Event.class.isAssignableFrom(type) && type != Event.class;
    }

    /** Connects the event bridge, menus and callbacks to the engine that runs the scripts. */
    public void attach(ScriptEngine engine) {
        events.attach(engine);
        context.attach(engine);
        Bukkit.getPluginManager().registerEvents(new MenuListener(), plugin);
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

    @Override
    public Scheduler scheduler() {
        return scheduler;
    }

    @Override
    public CommandRegistry commands() {
        return commands;
    }

    @Override
    public ArgumentTypes arguments() {
        return arguments;
    }

    @Override
    public PlayerDirectory players() {
        return players;
    }
}
