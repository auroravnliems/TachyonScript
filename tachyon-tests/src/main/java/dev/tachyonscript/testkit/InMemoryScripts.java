package dev.tachyonscript.testkit;

import dev.tachyonscript.engine.ScriptSource;
import dev.tachyonscript.language.source.SourceFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A mutable in-memory scripts directory. */
public final class InMemoryScripts implements ScriptSource {

    private final Map<String, String> files = new TreeMap<>();

    public InMemoryScripts put(String path, String content) {
        files.put(path, content);
        return this;
    }

    /** The content of a script (tests edit scripts by modifying it). */
    public String get(String path) {
        return files.get(path);
    }

    public InMemoryScripts remove(String path) {
        files.remove(path);
        return this;
    }

    @Override
    public List<SourceFile> read() {
        List<SourceFile> sources = new ArrayList<>();
        files.forEach((path, content) -> sources.add(new SourceFile(path, content)));
        return sources;
    }
}
