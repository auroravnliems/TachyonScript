package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptError;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Text files for scripts, confined to one folder ({@code plugins/TachyonScript/files}): paths
 * are relative to it and cannot leave it, so scripts cannot read or overwrite other files.
 */
public final class ScriptFiles {

    /** Largest file scripts may read at once. */
    private static final int MAX_READ = 16 * 1024 * 1024;

    private final Path root;

    public ScriptFiles(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    private Path resolve(String path) {
        String cleaned = path.strip().replace('\\', '/');
        if (cleaned.isEmpty() || cleaned.startsWith("/") || cleaned.contains(":")) {
            throw new ScriptError("Invalid file path '" + path + "': use a path such as 'logs/shop.log'.");
        }
        Path resolved = root.resolve(cleaned).normalize();
        if (!resolved.startsWith(root)) {
            throw new ScriptError("The file path '" + path + "' leaves the files folder.");
        }
        confined(resolved);
        return resolved;
    }

    private static void noLinks(Path path) {
        for (Path current = path; current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new ScriptError("Symbolic links are not permitted in the script files directory.");
    }

    private void confined(Path path) {
        noLinks(path);
        try {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
            Path realRoot = root.toRealPath();
            // Real paths also reject Windows directory junctions inside the files root.
            for (Path existing = path; existing != null && existing.startsWith(root); existing = existing.getParent()) {
                if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                    if (!existing.toRealPath().startsWith(realRoot))
                        throw new ScriptError("The file path leaves the files folder through a filesystem link.");
                    return;
                }
            }
        } catch (IOException e) {
            throw new ScriptError("Cannot validate the script file path.");
        }
    }

    public String read(String path) {
        Path file = resolve(path);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            if (Files.size(file) > MAX_READ) {
                throw new ScriptError("The file '" + path + "' is larger than 16 MB.");
            }
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAX_READ + 1);
                if (bytes.length > MAX_READ) throw new ScriptError("The file is larger than 16 MB.");
                return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            }
        } catch (IOException e) {
            throw new ScriptError("Cannot read '" + path + "': " + e.getMessage());
        }
    }

    public List<Object> lines(String path) {
        String text = read(path);
        return text == null ? new ArrayList<>() : new ArrayList<>(text.lines().toList());
    }

    public void write(String path, String text, boolean append) {
        Path file = resolve(path);
        try {
            Files.createDirectories(file.getParent());
            confined(file);
            if (append) {
                Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
            } else {
                Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            }
        } catch (IOException e) {
            throw new ScriptError("Cannot write '" + path + "': " + e.getMessage());
        }
    }

    public boolean exists(String path) {
        return Files.exists(resolve(path));
    }

    public boolean delete(String path) {
        try {
            return Files.deleteIfExists(resolve(path));
        } catch (IOException e) {
            throw new ScriptError("Cannot delete '" + path + "': " + e.getMessage());
        }
    }

    public List<Object> list(String folder) {
        Path directory = folder.isBlank() ? root : resolve(folder);
        confined(directory);
        List<Object> names = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return names;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            entries.sorted().forEach(entry -> names.add(entry.getFileName().toString()));
        } catch (IOException e) {
            throw new ScriptError("Cannot list '" + folder + "': " + e.getMessage());
        }
        return names;
    }
}
