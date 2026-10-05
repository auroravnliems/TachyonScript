package dev.tachyonscript.engine;

import dev.tachyonscript.language.source.SourceFile;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Supplies the current script files, e.g. from the scripts directory. */
@FunctionalInterface
public interface ScriptSource {

    List<SourceFile> read() throws IOException;

    /** Reads only the requested paths. Filesystem sources should avoid opening other files. */
    default List<SourceFile> read(Set<String> paths) throws IOException {
        return read().stream().filter(file -> paths.contains(file.path())).toList();
    }
}
