package dev.tachyonscript.engine;

import dev.tachyonscript.language.source.SourceFile;

import java.io.IOException;
import java.util.List;

/** Supplies the current script files, e.g. from the scripts directory. */
@FunctionalInterface
public interface ScriptSource {

    List<SourceFile> read() throws IOException;
}
