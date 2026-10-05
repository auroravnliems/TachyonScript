package dev.tachyonscript.plugin;

import dev.tachyonscript.engine.ScriptSource;
import dev.tachyonscript.language.source.SourceFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads {@code .tys} files from the scripts directory, recursively. Files or directories
 * whose name starts with {@code -} are disabled and skipped.
 */
final class ScriptDirectory implements ScriptSource {

    /** Refuse to read absurdly large files instead of loading them into memory. */
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    private final Path root;

    ScriptDirectory(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    @Override
    public List<SourceFile> read() throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("The scripts root cannot be a symbolic link");
        if (!Files.isDirectory(root)) {
            Files.createDirectories(root);
            return List.of();
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".tys"))
                    .filter(this::enabled)
                    .sorted()
                    .toList();
        }
        return readPaths(paths);
    }

    @Override
    public List<SourceFile> read(Set<String> selected) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (String name : selected.stream().sorted().toList()) {
            Path path = root.resolve(name).normalize();
            if (!path.startsWith(root) || !name.endsWith(".tys"))
                throw new IOException("Invalid script path: " + name);
            if (enabled(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) paths.add(path);
        }
        return readPaths(paths);
    }

    private List<SourceFile> readPaths(List<Path> paths) throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("The scripts root cannot be a symbolic link");
        List<SourceFile> files = new ArrayList<>(paths.size());
        for (Path path : paths) {
            if (!path.toRealPath().startsWith(root.toRealPath()) || Files.isSymbolicLink(path))
                throw new IOException("Script path escapes the registered source directory");
            String relative = root.relativize(path).toString().replace('\\', '/');
            if (Files.size(path) > MAX_FILE_BYTES) {
                throw new IOException(relative + " is larger than " + (MAX_FILE_BYTES / 1024 / 1024) + " MB");
            }
            files.add(new SourceFile(relative, Files.readString(path)));
        }
        return files;
    }

    private boolean enabled(Path path) {
        for (Path part : root.relativize(path)) {
            if (part.toString().startsWith("-")) {
                return false;
            }
        }
        return true;
    }
}
