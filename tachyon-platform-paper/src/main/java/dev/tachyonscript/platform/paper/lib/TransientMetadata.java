package dev.tachyonscript.platform.paper.lib;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.engine.ScriptEngine;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Temporary values with cleanup on script retirement, entity removal, and chunk/world unload. */
public final class TransientMetadata implements Listener {
    private record Target(String kind, UUID id, UUID world, int x, int y, int z) { }
    private record Key(Target target, String name) { }
    private record ChunkKey(UUID world, int x, int z) { }
    private static final class Entry {
        final Key key;
        final Object value;
        Entry(Key key, Object value) { this.key = key; this.value = value; }
    }
    private static final int MAX_ENTRIES = 100_000;
    private final Map<Key, Entry> entries = new HashMap<>();
    private final Map<Target, Set<Key>> targets = new HashMap<>();
    private final Map<ChunkKey, Set<Target>> chunks = new HashMap<>();
    private final Map<UUID, Set<Target>> worlds = new HashMap<>();

    public synchronized Object get(Object target, String name) {
        Entry entry = entries.get(key(target, name));
        return entry == null ? null : entry.value;
    }

    public void set(Object target, String name, Object value) {
        Key key = key(target, name);
        Entry entry = new Entry(key, value);
        synchronized (this) {
            if (value == null) { removeKey(key); return; }
            if (entries.size() >= MAX_ENTRIES && !entries.containsKey(key))
                throw new ScriptError("Temporary metadata has reached its 100000-entry limit; remove unused values.");
            entries.put(key, entry);
            targets.computeIfAbsent(key.target(), ignored -> new HashSet<>()).add(key);
            if (key.target().world() != null) worlds.computeIfAbsent(key.target().world(), ignored -> new HashSet<>()).add(key.target());
            if (key.target().kind().equals("block")) chunks.computeIfAbsent(chunk(key.target()), ignored -> new HashSet<>()).add(key.target());
        }
        // Register outside the store lock to avoid reversing the retirement lock order.
        ScriptEngine.ownResource(entry, resource -> remove((Entry) resource));
    }

    private synchronized void remove(Entry entry) { if (entries.get(entry.key) == entry) removeKey(entry.key); }
    public synchronized int size() { return entries.size(); }
    public synchronized void clear() { entries.clear(); targets.clear(); chunks.clear(); worlds.clear(); }

    private void removeKey(Key key) {
        if (entries.remove(key) == null) return;
        Set<Key> keys = targets.get(key.target());
        keys.remove(key);
        if (keys.isEmpty()) removeTarget(key.target());
    }

    private void removeTarget(Target target) {
        Set<Key> keys = targets.remove(target);
        if (keys != null) keys.forEach(entries::remove);
        if (target.world() != null) removeIndex(worlds, target.world(), target);
        if (target.kind().equals("block")) removeIndex(chunks, chunk(target), target);
    }

    private static ChunkKey chunk(Target target) { return new ChunkKey(target.world(), target.x() >> 4, target.z() >> 4); }
    private static <K> void removeIndex(Map<K, Set<Target>> index, K key, Target target) {
        Set<Target> group = index.get(key);
        if (group != null) { group.remove(target); if (group.isEmpty()) index.remove(key); }
    }

    private static Key key(Object holder, String name) {
        if (name.isBlank() || name.length() > 128) throw new ScriptError("Metadata keys must contain 1 to 128 characters.");
        Target target;
        if (holder instanceof Entity entity) target = new Target("entity", entity.getUniqueId(), null, 0, 0, 0);
        else if (holder instanceof Block block) target = new Target("block", null, block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        else if (holder instanceof World world) target = new Target("world", world.getUID(), world.getUID(), 0, 0, 0);
        else throw new ScriptError("Metadata needs an entity, block or world.");
        return new Key(target, name);
    }

    @EventHandler
    public synchronized void entityRemoved(EntityRemoveFromWorldEvent event) {
        UUID id = event.getEntity().getUniqueId();
        removeTarget(new Target("entity", id, null, 0, 0, 0));
    }

    @EventHandler
    public synchronized void chunkUnloaded(ChunkUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        int x = event.getChunk().getX(), z = event.getChunk().getZ();
        Set<Target> group = chunks.get(new ChunkKey(world, x, z));
        if (group != null) Set.copyOf(group).forEach(this::removeTarget);
    }

    @EventHandler(ignoreCancelled = true)
    public synchronized void worldUnloaded(WorldUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        Set<Target> group = worlds.get(world);
        if (group != null) Set.copyOf(group).forEach(this::removeTarget);
    }
}
