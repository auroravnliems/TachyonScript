package dev.tachyonscript.engine;

import dev.tachyonscript.compiler.CompiledModule;
import dev.tachyonscript.runtime.link.LinkedModule;

/**
 * A script that is part of a generation.
 *
 * @param path     path relative to the scripts directory
 * @param hash     content hash of the compiled source
 * @param compiled compiler output
 * @param linked   executable module
 */
public record LoadedScript(String path, String hash, CompiledModule compiled, LinkedModule linked) {
}
