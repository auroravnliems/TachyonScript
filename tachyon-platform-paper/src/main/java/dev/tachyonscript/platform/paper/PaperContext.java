package dev.tachyonscript.platform.paper;

import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.natives.ScriptFunction;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.platform.paper.lib.ScriptFiles;
import dev.tachyonscript.platform.paper.lib.WebClient;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What the standard library bindings need from the running server: the plugin, the thread
 * rules of Paper and Folia, callbacks into scripts and script-owned resources.
 *
 * <p>Writes use the {@code for*} methods: they run at once on the owning thread and are
 * forwarded otherwise. Operations that return a value use the {@code call*} methods: they
 * run at once on the owning thread, wait for the owner when called from an asynchronous
 * thread, and fail with a clear error when called from another tick thread (Folia), where
 * waiting could deadlock.
 */
public final class PaperContext {

    /** How long an asynchronous caller waits for the server thread. */
    private static final long WAIT_SECONDS = 10;

    private final Plugin plugin;
    private final Threading threads;
    private final ScriptFiles files;
    private final WebClient web;
    private final dev.tachyonscript.platform.paper.lib.TransientMetadata metadata = new dev.tachyonscript.platform.paper.lib.TransientMetadata();
    private volatile ScriptEngine engine;

    PaperContext(Plugin plugin, Threading threads) {
        this.plugin = plugin;
        this.threads = threads;
        this.files = new ScriptFiles(plugin.getDataFolder().toPath().resolve("files"));
        this.web = new WebClient(this);
    }

    /** Connects the engine that runs callbacks (done once the engine exists). */
    void attach(ScriptEngine engine) {
        this.engine = engine;
    }

    Threading threads() {
        return threads;
    }

    public Plugin plugin() {
        return plugin;
    }

    /** Whether regions tick in parallel (Folia). */
    public boolean folia() {
        return threads.folia();
    }

    public ScriptFiles files() {
        return files;
    }

    public WebClient web() {
        return web;
    }
    public dev.tachyonscript.platform.paper.lib.TransientMetadata metadata() { return metadata; }
    public dev.tachyonscript.security.SecurityOptions securityOptions() {
        ScriptEngine current = engine;
        return current == null ? dev.tachyonscript.security.SecurityOptions.defaults() : current.security().options();
    }

    // ------------------------------------------------------------------ threads

    public void forEntity(Entity entity, Runnable action) {
        threads.forEntity(entity, guarded(action));
    }

    /** Runs on the thread owning a location, block, chunk or entity. */
    public void forRegion(Object target, Runnable action) {
        if (target instanceof Entity entity) {
            threads.forEntity(entity, guarded(action));
        } else {
            threads.forRegion(location(target), guarded(action));
        }
    }

    public void global(Runnable action) {
        threads.global(guarded(action));
    }

    private static Runnable guarded(Runnable action) {
        Object owner = dev.tachyonscript.runtime.interpreter.ExecutionStack.current().owner();
        if (!(owner instanceof dev.tachyonscript.runtime.interpreter.ExecutionGuard guard)) return action;
        return () -> { if (!guard.securityRevoked()) action.run(); };
    }

    /** Runs on the thread owning an inventory's holder (a player, a block); at once for virtual inventories. */
    public void forInventory(Inventory inventory, Runnable action) {
        Object owner = owner(inventory);
        if (owner == null) {
            action.run();
        } else {
            forRegion(owner, action);
        }
    }

    public <T> T callEntity(Entity entity, Supplier<T> action) {
        if (threads.ownsEntity(entity)) {
            return action.get();
        }
        return await(action, runner -> threads.forEntity(entity, runner), entity.getName());
    }

    public <T> T callRegion(Object target, Supplier<T> action) {
        if (target instanceof Entity entity) {
            return callEntity(entity, action);
        }
        Location location = location(target);
        if (threads.ownsRegion(location)) {
            return action.get();
        }
        return await(action, runner -> threads.forRegion(location, runner), describe(location));
    }

    public <T> T callGlobal(Supplier<T> action) {
        if (threads.ownsGlobal()) {
            return action.get();
        }
        return await(action, threads::global, "the server");
    }

    public <T> T callInventory(Inventory inventory, Supplier<T> action) {
        Object owner = owner(inventory);
        return owner == null ? action.get() : callRegion(owner, action);
    }

    private <T> T await(Supplier<T> action, Consumer<Runnable> schedule, String what) {
        if (threads.onTickThread()) {
            throw new ScriptError("This must run on the thread that owns " + what + " (on Folia every region has its "
                    + "own thread). Run it from a handler of that region, or inside 'after 1 tick for <entity> { }'.");
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Object scriptOwner = dev.tachyonscript.runtime.interpreter.ExecutionStack.current().owner();
        schedule.accept(() -> {
            try {
                if (scriptOwner instanceof dev.tachyonscript.runtime.interpreter.ExecutionGuard guard && guard.securityRevoked())
                    throw new dev.tachyonscript.runtime.error.ScriptRuntimeException(
                            dev.tachyonscript.runtime.error.ScriptRuntimeException.Kind.SECURITY_REVOKED,
                            "Script security approval was revoked before its queued platform operation.", null);
                result.complete(action.get());
            } catch (RuntimeException | Error e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScriptError("Interrupted while waiting for the server thread.");
        } catch (TimeoutException e) {
            throw new ScriptError("The server thread did not run the operation within " + WAIT_SECONDS + " seconds.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ScriptError("The operation failed: " + e.getCause());
        }
    }

    private static Location location(Object target) {
        return switch (target) {
            case Location location -> location;
            case Block block -> block.getLocation();
            case Chunk chunk -> chunk.getBlock(8, 64, 8).getLocation();
            case BlockState state -> state.getLocation();
            default -> throw new IllegalArgumentException("Not a place: " + target);
        };
    }

    private static Object owner(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof Entity entity) {
            return entity;
        }
        if (holder instanceof BlockState state) {
            return state.getLocation();
        }
        return null;
    }

    private static String describe(Location location) {
        return "the location " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    // ------------------------------------------------------------------ scripts

    /**
     * Calls a script function value from code that runs outside scripts (a menu click, a web
     * response), if its script is still loaded; script errors are reported, never thrown.
     */
    public Object callback(ScriptFunction function, Object... arguments) {
        ScriptEngine current = engine;
        if (current == null) {
            return null;
        }
        return current.callback(function, arguments);
    }

    /** Whether the script that created a function value is still loaded. */
    public boolean isLoaded(ScriptFunction function) {
        return ScriptEngine.isLoaded(function);
    }

    /**
     * Ties a resource the running script creates to that script: {@code release} runs when the
     * script is reloaded or unloaded (the resource is held weakly; release must not reference it).
     */
    public <T> T own(T resource, Consumer<Object> release) {
        ScriptEngine.ownResource(resource, release);
        return resource;
    }
}
