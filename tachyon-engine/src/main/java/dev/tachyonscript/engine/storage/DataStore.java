package dev.tachyonscript.engine.storage;

import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.engine.spi.EngineLogger;
import dev.tachyonscript.engine.spi.PlayerDirectory;
import dev.tachyonscript.ir.GlobalRef;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;
import dev.tachyonscript.runtime.interpreter.Interpreter;
import dev.tachyonscript.runtime.value.GlobalCell;
import dev.tachyonscript.runtime.value.PlayerDataSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The saved variables of every script: {@code persistent var} values and the
 * {@code playerdata var} values of players.
 *
 * <p>Scripts always work on values in memory; nothing waits for the database while a script
 * runs. Values are written on a background thread every flush interval (and when the server
 * stops): each flush encodes the current values and writes the ones whose encoding changed,
 * so values changed in place (a list or map that was modified) are saved too. A player's data
 * is loaded before they finish joining (from the platform's pre-login event) and dropped
 * after they leave and their data was written.
 *
 * <p>Values survive script reloads: a reloaded script restores its {@code persistent} variables
 * from memory, and player values are kept by variable name.
 */
public final class DataStore implements AutoCloseable {

    private static final String SERVER = "";
    private static final long SLOW_LOAD_NANOS = 50_000_000L;

    private final StorageBackend backend;
    private final ValueCodec codec;
    private final EngineLogger logger;
    private final ScheduledExecutorService thread;
    private final long flushIntervalMillis;

    /** Encoded values of persistent variables, by {@code scope::name}; loaded once, then kept in memory. */
    private final Map<String, String> persistent = new ConcurrentHashMap<>();
    /** What the database holds for each persistent key (for change detection). */
    private final Map<String, String> savedPersistent = new ConcurrentHashMap<>();
    /** Cells of the persistent variables of active scripts, by key. */
    private final Map<String, GlobalCell> live = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerRecord> players = new ConcurrentHashMap<>();
    private final Object persistentLoad = new Object();
    private volatile boolean persistentLoaded;
    private volatile long lastFailureLog;
    private volatile boolean closed;

    public DataStore(StorageBackend backend, ValueCodec codec, EngineLogger logger, long flushIntervalMillis) {
        this.backend = backend;
        this.codec = codec;
        this.logger = logger;
        this.flushIntervalMillis = flushIntervalMillis;
        this.thread = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread worker = new Thread(runnable, "TachyonScript storage");
            worker.setDaemon(true);
            return worker;
        });
        if (flushIntervalMillis > 0) {
            thread.scheduleWithFixedDelay(this::flushQuietly, flushIntervalMillis, flushIntervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    public StorageBackend backend() {
        return backend;
    }

    public ValueCodec codec() {
        return codec;
    }

    // ================================================================= persistent variables

    /** The hook through which a persistent variable's cell restores its saved value. */
    public GlobalCell.Persistence persistence(ValueCodec.RecordResolver records) {
        return cell -> restore(cell, records);
    }

    private boolean restore(GlobalCell cell, ValueCodec.RecordResolver records) {
        loadPersistent();
        String key = cell.ref().key();
        live.put(key, cell);
        String text = persistent.get(key);
        if (text == null) {
            return false;
        }
        try {
            cell.setBoxed(codec.decode(text, cell.ref().type(), records));
            return true;
        } catch (RuntimeException e) {
            logger.warn("The saved value of " + cell.ref().module() + "." + cell.ref().name()
                    + " cannot be loaded as " + cell.ref().type().displayName() + " (" + e.getMessage()
                    + "); the variable starts with its initial value. Saved text: " + abbreviate(text));
            return false;
        }
    }

    /** Starts saving a persistent variable of an active script (its initial value if nothing was saved). */
    public void track(GlobalCell cell) {
        live.put(cell.ref().key(), cell);
    }

    /**
     * Keeps the current value of a persistent variable whose script is being unloaded or
     * reloaded, so the next version restores it; the value is written with the next flush.
     */
    public void release(GlobalCell cell) {
        String key = cell.ref().key();
        encodeInto(cell);
        live.remove(key, cell);
    }

    private void encodeInto(GlobalCell cell) {
        Object value = cell.boxed();
        if (value == null && !cell.ref().type().isNullable()) {
            return; // never initialized (the script failed before its initializer ran)
        }
        try {
            persistent.put(cell.ref().key(), codec.encode(value, cell.ref().type()));
        } catch (RuntimeException e) {
            logger.warn("Cannot save " + cell.ref().module() + "." + cell.ref().name() + ": " + e.getMessage());
        }
    }

    private void loadPersistent() {
        if (persistentLoaded) {
            return;
        }
        synchronized (persistentLoad) {
            if (persistentLoaded) {
                return;
            }
            try {
                Map<String, String> saved = backend.load(SERVER);
                saved.forEach((key, value) -> {
                    persistent.putIfAbsent(key, value);
                    savedPersistent.put(key, value);
                });
            } catch (StorageBackend.StorageException e) {
                logger.error(e.getMessage() + " Persistent variables start with their initial values.");
            }
            persistentLoaded = true;
        }
    }

    // ================================================================= player data

    /**
     * The slot of a {@code playerdata var}. {@code initial} gives the function computing the
     * initial value (resolved after linking).
     */
    public PlayerDataSlot playerData(GlobalRef ref, Supplier<CompiledFunction> initial, ValueCodec.RecordResolver records,
                                     PlayerDirectory directory) {
        return new Slot(ref, initial, records, directory);
    }

    /** Loads a player's saved values (blocking); called on a background thread before the player joins. */
    public void preload(UUID player) {
        PlayerRecord record = players.get(player);
        if (record != null) {
            record.leaving = false;
            return;
        }
        PlayerRecord loaded = load(player);
        PlayerRecord existing = players.putIfAbsent(player, loaded);
        if (existing != null) {
            existing.leaving = false;
        }
    }

    /** The player left: their data is written with the next flush and then dropped from memory. */
    public void unload(UUID player) {
        PlayerRecord record = players.get(player);
        if (record != null) {
            record.leaving = true;
        }
    }

    private PlayerRecord record(UUID player) {
        PlayerRecord record = players.get(player);
        if (record != null) {
            return record;
        }
        // An offline player, or data used before the pre-login load finished: load it now.
        long start = System.nanoTime();
        PlayerRecord loaded = load(player);
        long elapsed = System.nanoTime() - start;
        if (elapsed > SLOW_LOAD_NANOS) {
            logger.warn(String.format(java.util.Locale.ROOT,
                    "Loading the saved data of %s took %.1f ms while a script waited for it.", player, elapsed / 1e6));
        }
        loaded.leaving = true; // not online: drop it again after the next flush
        PlayerRecord existing = players.putIfAbsent(player, loaded);
        return existing != null ? existing : loaded;
    }

    private PlayerRecord load(UUID player) {
        PlayerRecord record = new PlayerRecord(player);
        try {
            Map<String, String> saved = backend.load(player.toString());
            record.raw.putAll(saved);
            record.saved.putAll(saved);
        } catch (StorageBackend.StorageException e) {
            logger.error(e.getMessage() + " The player's values start from their initial values.");
        }
        return record;
    }

    /** A decoded value and the type it was decoded as. */
    private record Value(Type type, Object value) {
    }

    private static final class PlayerRecord {
        final UUID id;
        /** Saved encodings not decoded yet (the type is only known when a script reads them). */
        final Map<String, String> raw = new ConcurrentHashMap<>();
        final Map<String, Value> values = new ConcurrentHashMap<>();
        /** What the database holds, by key. */
        final Map<String, String> saved = new ConcurrentHashMap<>();
        volatile boolean leaving;

        PlayerRecord(UUID id) {
            this.id = id;
        }
    }

    private final class Slot implements PlayerDataSlot {
        private final GlobalRef ref;
        private final String key;
        private final Supplier<CompiledFunction> initial;
        private final ValueCodec.RecordResolver records;
        private final PlayerDirectory directory;

        Slot(GlobalRef ref, Supplier<CompiledFunction> initial, ValueCodec.RecordResolver records, PlayerDirectory directory) {
            this.ref = ref;
            this.key = ref.key();
            this.initial = initial;
            this.records = records;
            this.directory = directory;
        }

        @Override
        public GlobalRef ref() {
            return ref;
        }

        @Override
        public Object get(Object player) {
            PlayerRecord record = record(directory.id(Objects.requireNonNull(player, "player")));
            Value value = record.values.get(key);
            if (value != null && value.type().equals(ref.type())) {
                return value.value();
            }
            Object decoded = decodeExisting(record, value);
            if (decoded != NOT_FOUND) {
                record.values.put(key, new Value(ref.type(), decoded));
                return decoded;
            }
            // Script code runs outside any map operation: the initial value may read other data.
            Object fresh = Interpreter.call(initial.get());
            Value previous = record.values.putIfAbsent(key, new Value(ref.type(), fresh));
            return previous != null && previous.type().equals(ref.type()) ? previous.value() : fresh;
        }

        /** The saved value (or a value of an older type, converted), or {@link #NOT_FOUND}. */
        private Object decodeExisting(PlayerRecord record, Value old) {
            String text = null;
            if (old != null) {
                try {
                    text = codec.encode(old.value(), old.type());
                } catch (RuntimeException ignored) {
                    text = null;
                }
            }
            if (text == null) {
                text = record.raw.get(key);
            }
            if (text == null) {
                return NOT_FOUND;
            }
            try {
                return codec.decode(text, ref.type(), records);
            } catch (RuntimeException e) {
                logger.warn("The saved value of " + ref.module() + "." + ref.name() + " for player "
                        + record.id + " cannot be loaded as " + ref.type().displayName()
                        + "; using the initial value.");
                return NOT_FOUND;
            }
        }

        @Override
        public void set(Object player, Object value) {
            PlayerRecord record = record(directory.id(Objects.requireNonNull(player, "player")));
            record.values.put(key, new Value(ref.type(), value));
        }

        @Override
        public void add(Object player, long deltaBits) {
            PlayerRecord record = record(directory.id(Objects.requireNonNull(player, "player")));
            get(player); // make sure the current (or initial) value is in memory
            record.values.compute(key, (k, current) -> {
                Object base = current.value();
                Object sum = switch ((PrimitiveType) ref.type()) {
                    case INT -> (Integer) base + (int) deltaBits;
                    case LONG -> (Long) base + deltaBits;
                    default -> (Double) base + Double.longBitsToDouble(deltaBits);
                };
                return new Value(ref.type(), sum);
            });
        }
    }

    private static final Object NOT_FOUND = new Object();

    // ================================================================= flushing

    /** Writes every changed value now (blocking the caller until done). */
    public void flush() {
        Future<?> done = thread.submit(this::flushQuietly);
        try {
            done.get(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Saving script data failed: " + e);
        }
    }

    /** Writes every changed value on the storage thread. */
    public void flushLater() {
        if (!thread.isShutdown()) {
            thread.execute(this::flushQuietly);
        }
    }

    private void flushQuietly() {
        try {
            flushNow();
        } catch (RuntimeException e) {
            logger.error("Saving script data failed: " + e);
        }
    }

    /** Runs on the storage thread. */
    private void flushNow() {
        List<StorageBackend.Row> rows = new ArrayList<>();
        Map<String, String> writtenPersistent = new java.util.HashMap<>();
        for (GlobalCell cell : live.values()) {
            encodeInto(cell);
        }
        for (Map.Entry<String, String> entry : persistent.entrySet()) {
            if (!entry.getValue().equals(savedPersistent.get(entry.getKey()))) {
                String[] parts = split(entry.getKey());
                rows.add(new StorageBackend.Row(parts[0], SERVER, parts[1], entry.getValue()));
                writtenPersistent.put(entry.getKey(), entry.getValue());
            }
        }
        Map<PlayerRecord, Map<String, String>> writtenPlayers = new java.util.HashMap<>();
        for (PlayerRecord record : players.values()) {
            Map<String, String> written = new java.util.HashMap<>();
            for (Map.Entry<String, Value> entry : record.values.entrySet()) {
                String text;
                try {
                    Object value = entry.getValue().value();
                    text = value == null && !entry.getValue().type().isNullable() ? null
                            : codec.encode(value, entry.getValue().type());
                } catch (RuntimeException e) {
                    logger.warn("Cannot save " + entry.getKey() + " of player " + record.id + ": " + e.getMessage());
                    continue;
                }
                if (text != null && !text.equals(record.saved.get(entry.getKey()))) {
                    String[] parts = split(entry.getKey());
                    rows.add(new StorageBackend.Row(parts[0], record.id.toString(), parts[1], text));
                    written.put(entry.getKey(), text);
                }
            }
            writtenPlayers.put(record, written);
        }
        try {
            backend.save(rows);
        } catch (StorageBackend.StorageException e) {
            long now = System.nanoTime();
            if (now - lastFailureLog > 60_000_000_000L) {
                lastFailureLog = now;
                logger.error(e.getMessage() + " Values stay in memory and are written again later.");
            }
            return;
        }
        savedPersistent.putAll(writtenPersistent);
        writtenPlayers.forEach((record, written) -> {
            record.saved.putAll(written);
            record.raw.putAll(written);
            if (record.leaving) {
                players.remove(record.id, record);
            }
        });
    }

    private static String[] split(String key) {
        int separator = key.indexOf("::");
        return new String[] {key.substring(0, separator), key.substring(separator + 2)};
    }

    private static String abbreviate(String text) {
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }

    /** Writes everything and closes the database. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        flush();
        thread.shutdown();
        try {
            thread.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        backend.close();
    }

    /** Executor for other blocking storage work (script database queries use their own). */
    ExecutorService executor() {
        return thread;
    }

    /** The flush interval in milliseconds (0 = only on shutdown). */
    public long flushIntervalMillis() {
        return flushIntervalMillis;
    }
}
