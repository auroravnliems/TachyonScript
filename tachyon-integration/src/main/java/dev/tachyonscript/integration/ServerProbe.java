package dev.tachyonscript.integration;

import dev.tachyonscript.api.TachyonVersion;
import dev.tachyonscript.api.natives.ScriptFunction;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.platform.paper.PlatformCapabilities;
import dev.tachyonscript.runtime.error.ScriptRuntimeException;
import dev.tachyonscript.runtime.interpreter.Interpreter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Runs only in disposable test servers; never package this probe in the production plugin. */
public final class ServerProbe extends JavaPlugin {
    private final Map<String, Boolean> checks = Collections.synchronizedMap(new LinkedHashMap<>());
    private final AtomicBoolean finished = new AtomicBoolean();
    private ScriptEngine engine;
    private boolean folia;
    private String source;
    private ScriptFunction retiredCallback;

    private record Site(Cow entity, Block block, Location location) { }
    private record Sites(Site local, Site remote) { }

    @Override
    public void onEnable() {
        folia = PlatformCapabilities.detect().folia();
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> awaitScripts(0), 2);
    }

    private void awaitScripts(int attempt) {
        try {
            var plugin = Bukkit.getPluginManager().getPlugin("TachyonScript");
            if (plugin == null || !plugin.isEnabled()) throw new IllegalStateException("TachyonScript is not enabled");
            // White-box access belongs to this test artifact, not the public addon API.
            Field field = plugin.getClass().getDeclaredField("engine");
            field.setAccessible(true);
            engine = (ScriptEngine) field.get(plugin);
            if (engine == null || !engine.generation().scripts().containsKey("integration.tys")) {
                if (attempt >= 120) throw new IllegalStateException("Scripts did not activate; inspect server.log");
                Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> awaitScripts(attempt + 1), 10);
                return;
            }
            source = Files.readString(Path.of("plugins/TachyonScript/scripts/integration.tys"));
            check("platform-detection", folia == Boolean.getBoolean("tachyon.integration.folia"));
            check("plugin-script-activation", engine.generation().scripts().size() == 1);
            World world = Bukkit.getWorlds().getFirst();
            prepare(world, 0).thenCombine(prepare(world, 2048), Sites::new)
                    .thenCompose(this::ownedAndCrossRegion)
                    .thenCompose(this::asyncReadAndTeleport)
                    .thenCompose(this::scheduledAndAsync)
                    .thenCompose(this::reloadAndRetirement)
                    .thenCompose(ignored -> async(() -> {
                        try {
                            call("spin()");
                            throw new AssertionError("watchdog did not fire");
                        } catch (ScriptRuntimeException error) {
                            check("watchdog", error.kind() == ScriptRuntimeException.Kind.TIMEOUT);
                        }
                        return null;
                    }))
                    .thenCompose(ignored -> global(() -> {
                        check("stack-recovers-after-timeout", Integer.valueOf(5).equals(call("savedValue()")));
                        check("script-active-before-server-stop", engine.generation().scripts().size() == 1);
                        return null;
                    }))
                    .orTimeout(120, TimeUnit.SECONDS)
                    .whenComplete((ignored, failure) -> finish(failure));
        } catch (ReflectiveOperationException | IOException | RuntimeException | AssertionError error) {
            finish(error);
        }
    }

    private CompletableFuture<Site> prepare(World world, int x) {
        Location location = new Location(world, x + 0.5, 65, 0.5);
        return world.getChunkAtAsync(x >> 4, 0, true).thenCompose(chunk -> region(location, () -> {
            chunk.addPluginChunkTicket(this);
            Cow entity = world.spawn(location, Cow.class, cow -> {
                cow.setAI(false);
                cow.setGravity(false);
                cow.setInvulnerable(true);
            });
            return new Site(entity, world.getBlockAt(x, 63, 0), location);
        }));
    }

    private CompletableFuture<Sites> ownedAndCrossRegion(Sites sites) {
        return entity(sites.local().entity(), 1, () -> {
            check("entity-owner-thread", Bukkit.isOwnedByCurrentRegion(sites.local().entity()));
            check("separate-regions", !folia || !Bukkit.isOwnedByCurrentRegion(sites.remote().entity()));
            check("owned-read-write", Boolean.TRUE.equals(call("owned(LivingEntity, Block)",
                    sites.local().entity(), sites.local().block())));
            call("deferred(LivingEntity, Block)", sites.remote().entity(), sites.remote().block());
            if (folia) {
                expectOwnershipError("cross-region-read-rejected", "readHealth(LivingEntity)", sites.remote().entity());
                expectOwnershipError("cross-region-name-rejected", "readName(Entity)", sites.remote().entity());
                expectOwnershipError("cross-region-location-rejected", "readLocation(Entity)", sites.remote().entity());
                expectOwnershipError("cross-region-spawn-rejected", "spawnAt(Location)", sites.remote().location());
            } else {
                check("paper-shared-owner-read", Double.valueOf(6).equals(call("readHealth(LivingEntity)", sites.remote().entity())));
            }
            Entity spawned = (Entity) call("spawnAt(Location)", sites.local().location());
            check("owned-spawn", Bukkit.isOwnedByCurrentRegion(spawned) && spawned.isValid());
            spawned.remove();
            Entity dropped = (Entity) call("dropAt(Location)", sites.local().location());
            check("owned-drop", Bukkit.isOwnedByCurrentRegion(dropped) && dropped.isValid());
            dropped.remove();
            return sites;
        }).thenCompose(value -> entity(value.remote().entity(), 3, () -> {
            check("deferred-entity-write", value.remote().entity().getHealth() == 6);
            check("deferred-block-write", value.remote().block().getType() == Material.GOLD_BLOCK);
            return value;
        }));
    }

    private CompletableFuture<Sites> asyncReadAndTeleport(Sites sites) {
        Location destination = sites.remote().location().clone().add(8, 0, 0);
        return async(() -> {
            check("async-read-on-entity-owner", Double.valueOf(6).equals(call("readHealth(LivingEntity)", sites.remote().entity())));
            return sites;
        }).thenCompose(value -> global(() -> {
            if (folia) expectOwnershipError("global-read-rejected", "readHealth(LivingEntity)", value.local().entity());
            // The native forwards a write from the global thread to the entity owner.
            call("move(Entity, Location)", value.local().entity(), destination);
            return value;
        })).thenCompose(value -> awaitTeleport(value, destination, 0));
    }

    private CompletableFuture<Sites> awaitTeleport(Sites sites, Location destination, int attempt) {
        return entity(sites.local().entity(), 2, () -> sites.local().entity().getLocation().distanceSquared(destination) < 0.01)
                .thenCompose(arrived -> {
                    if (!arrived && attempt < 50) return awaitTeleport(sites, destination, attempt + 1);
                    check("cross-region-teleport-and-entity-scheduler", arrived);
                    return CompletableFuture.completedFuture(sites);
                });
    }

    private CompletableFuture<Sites> scheduledAndAsync(Sites sites) {
        return global(() -> {
            check("real-spawn-events", ((Integer) call("spawnCount()")) >= 3);
            check("script-command", Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tsprobe 4")
                    && Integer.valueOf(5).equals(call("savedValue()")));
            call("later(LivingEntity)", sites.local().entity());
            return sites;
        }).thenCompose(value -> entity(value.local().entity(), 5, () -> {
            check("entity-scheduled-block", value.local().entity().getHealth() == 7);
            call("roundTrip(LivingEntity)", value.local().entity());
            return value;
        })).thenCompose(value -> entity(value.local().entity(), 8, () -> {
            check("async-global-entity-handoff", value.local().entity().getHealth() == 9);
            return value;
        }));
    }

    private CompletableFuture<Sites> reloadAndRetirement(Sites sites) {
        return global(() -> {
            retiredCallback = (ScriptFunction) call("makeCallback()");
            call("obsolete(LivingEntity)", sites.local().entity());
            var old = engine.generation().scripts().get("integration.tys");
            var result = engine.load(() -> List.of(new SourceFile("integration.tys", source + "\n// replacement\n")));
            check("transactional-reload", result.activated() && result.failed().isEmpty()
                    && engine.generation().scripts().get("integration.tys") != old);
            check("persistent-state-after-reload", Integer.valueOf(5).equals(call("savedValue()")));
            check("retired-callback", engine.callback(retiredCallback) == null
                    && Integer.valueOf(5).equals(call("savedValue()")));
            var broken = engine.load(() -> List.of(new SourceFile("integration.tys", "function broken( {")));
            check("reload-rollback", broken.keptPrevious().contains("integration.tys")
                    && Integer.valueOf(5).equals(call("savedValue()")));
            return sites;
        }).thenCompose(value -> entity(value.local().entity(), 25, () -> {
            check("retired-scheduled-block", value.local().entity().getHealth() == 9);
            return value;
        }));
    }

    private void expectOwnershipError(String name, String function, Object argument) {
        try {
            call(function, argument);
            throw new AssertionError(name + ": unsafe operation succeeded");
        } catch (ScriptRuntimeException error) {
            check(name, error.kind() == ScriptRuntimeException.Kind.SCRIPT
                    && error.getMessage().contains("thread that owns"));
        }
    }

    private Object call(String key, Object... arguments) {
        var script = engine.generation().scripts().get("integration.tys");
        return Interpreter.call(script.linked().function(key).orElseThrow(() -> new IllegalStateException(key)), arguments);
    }

    private <T> CompletableFuture<T> global(Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().run(this, task -> complete(future, action));
        return future;
    }

    private <T> CompletableFuture<T> async(Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(this, task -> complete(future, action));
        return future;
    }

    private <T> CompletableFuture<T> region(Location location, Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(this, location, () -> complete(future, action));
        return future;
    }

    private <T> CompletableFuture<T> entity(Entity entity, long delay, Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        var task = entity.getScheduler().runDelayed(this, scheduled -> complete(future, action),
                () -> future.completeExceptionally(new IllegalStateException("Test entity retired")), delay);
        if (task == null) future.completeExceptionally(new IllegalStateException("Test entity is unavailable"));
        return future;
    }

    private static <T> void complete(CompletableFuture<T> future, Supplier<T> action) {
        try {
            future.complete(action.get());
        } catch (Throwable failure) {
            future.completeExceptionally(failure);
        }
    }

    private void check(String name, boolean passed) {
        checks.put(name, passed);
        if (!passed) throw new AssertionError(name);
        getLogger().info("PASS " + name);
    }

    private void finish(Throwable failure) {
        if (!finished.compareAndSet(false, true)) return;
        if (failure != null) failure.printStackTrace();
        StringBuilder json = new StringBuilder("{\n  \"success\": ").append(failure == null)
                .append(",\n  \"version\": ").append(quote(TachyonVersion.RUNTIME))
                .append(",\n  \"server\": ").append(quote(Bukkit.getVersion()))
                .append(",\n  \"folia\": ").append(folia).append(",\n  \"checks\": {");
        synchronized (checks) {
            String separator = "\n";
            for (var check : checks.entrySet()) {
                json.append(separator).append("    ").append(quote(check.getKey())).append(": ").append(check.getValue());
                separator = ",\n";
            }
        }
        json.append("\n  },\n  \"error\": ").append(quote(failure == null ? "" : failure.toString())).append("\n}\n");
        try {
            Files.writeString(Path.of("probe-result.json"), json, StandardCharsets.UTF_8);
        } catch (IOException error) {
            getLogger().severe("Cannot write probe result: " + error);
        }
        getLogger().info("TACHYON_INTEGRATION_FINISHED success=" + (failure == null));
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + '"';
    }
}
