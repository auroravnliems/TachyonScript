package dev.tachyonscript.engine;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/** Operator switches, independent of code validity and security approvals. */
public final class ScriptControls {
    private record State(boolean all, Set<String> paths, long revision) { }

    private final Path file;
    private volatile State state = new State(false, Set.of(), 0);

    public ScriptControls() { file = null; }

    public ScriptControls(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        if (!Files.exists(file)) return;
        Properties saved = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { saved.load(reader); }
        Set<String> paths = new TreeSet<>();
        for (String key : saved.stringPropertyNames()) {
            if (key.startsWith("script.")) { if (saved.getProperty(key).equals("true")) paths.add(key.substring(7)); }
            else if (!key.equals("all")) throw new IOException("Unknown script control: " + key);
            if (!saved.getProperty(key).equals("true") && !saved.getProperty(key).equals("false"))
                throw new IOException("Invalid script control value: " + key);
        }
        state = new State(Boolean.parseBoolean(saved.getProperty("all", "false")), Set.copyOf(paths), 0);
    }

    public boolean allowed(String path) { State snapshot = state; return !snapshot.all() && !snapshot.paths().contains(path); }
    public boolean allDisabled() { return state.all(); }
    public Set<String> disabled() { return state.paths(); }
    public long revision() { return state.revision(); }

    /** The in-memory gate closes before disk I/O; even a failed write leaves execution stopped. */
    public synchronized void disable(Set<String> paths, boolean all) throws IOException {
        Set<String> disabled = new TreeSet<>(state.paths());
        disabled.addAll(paths);
        state = new State(state.all() || all, Set.copyOf(disabled), state.revision() + 1);
        save();
    }

    public synchronized void enable(Set<String> paths, boolean all) throws IOException {
        if (state.all() && !all) throw new IllegalStateException("All scripts are disabled. Use /tys enable all first.");
        Set<String> disabled = new TreeSet<>(state.paths());
        if (all) disabled.clear(); else disabled.removeAll(paths);
        State previous = state;
        state = new State(false, Set.copyOf(disabled), state.revision() + 1);
        try { save(); }
        catch (IOException error) { state = new State(previous.all(), previous.paths(), state.revision() + 1); throw error; }
    }

    private void save() throws IOException {
        if (file == null) return;
        Files.createDirectories(file.getParent());
        Properties saved = new Properties();
        saved.setProperty("all", Boolean.toString(state.all()));
        for (String path : state.paths()) saved.setProperty("script." + path, "true");
        Path temporary = Files.createTempFile(file.getParent(), "script-controls-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                saved.store(writer, "Managed by /tys disable and /tys enable");
            }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
