package dev.tachyonscript.cli;

import dev.tachyonscript.stdlib.StandardLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        Cli cli = new Cli(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), false);
        return cli.run(args);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private Path script(String name, String content) throws IOException {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void checksAValidScript() throws IOException {
        Path file = script("join.tys", "event player.join {\n    player.send(\"Hello {player.name}!\")\n}\n");
        assertEquals(Cli.OK, run("check", file.toString()));
        assertTrue(out().contains("Checked 1 file: 0 errors, 0 warnings"), out());
    }

    @Test
    void reportsErrorsWithLocationsAndSuggestions() throws IOException {
        Path file = script("shop.tys", "event player.join {\n    player.sned(\"hi\")\n}\n");
        assertEquals(Cli.COMPILE_ERRORS, run("check", file.toString()));
        String output = out();
        assertTrue(output.contains("shop.tys:2:12"), output);
        assertTrue(output.contains("sned"), output);
        assertTrue(output.contains("Did you mean"), output);
        assertTrue(output.contains("1 error"), output);
    }

    @Test
    void checksADirectoryLikeThePlugin() throws IOException {
        script("a.tys", "event player.join {\n    player.send(\"a\")\n}\n");
        script("sub/b.tys", "event player.quit {\n    player.send(\"b\")\n}\n");
        script("-disabled/c.tys", "this is not valid\n");
        script("-off.tys", "neither is this\n");
        assertEquals(Cli.OK, run("check", directory.toString()));
        assertTrue(out().contains("Checked 2 files: 0 errors"), out());
    }

    @Test
    void dumpsEveryStage() throws IOException {
        Path file = script("join.tys", "event player.join {\n    player.send(\"Hello {player.name}!\")\n}\n");
        assertEquals(Cli.OK, run("dump", "tokens", file.toString()));
        assertTrue(out().contains("IDENTIFIER") || out().contains("EVENT"), out());
        out.reset();
        assertEquals(Cli.OK, run("dump", "ast", file.toString()));
        assertTrue(out().contains("player.join"), out());
        out.reset();
        assertEquals(Cli.OK, run("dump", "bound", file.toString()));
        assertFalse(out().isBlank());
        out.reset();
        assertEquals(Cli.OK, run("dump", "ir", file.toString()));
        assertTrue(out().contains("CommandSender.send(Component)"), out());
        out.reset();
        assertEquals(Cli.OK, run("dump", "code", file.toString()));
        assertTrue(out().contains("CALL_NATIVE_V"), out());
        assertTrue(out().contains("RET_V"), out());
    }

    @Test
    void dumpOfABrokenScriptPrintsDiagnostics() throws IOException {
        Path file = script("broken.tys", "event player.join {\n    player.send(1 +)\n}\n");
        assertEquals(Cli.COMPILE_ERRORS, run("dump", "ir", file.toString()));
        assertTrue(err().contains("ERROR"), err());
    }

    @Test
    void referenceDocumentationIsUpToDate() throws IOException {
        Path reference = Path.of("../docs/language/reference.md");
        String generated = ReferenceGenerator.generate(StandardLibrary.registry());
        assertEquals(generated, Files.readString(reference),
                "docs/language/reference.md is out of date; regenerate it with "
                        + "./gradlew -q :tachyon-cli:run --args=docs > docs/language/reference.md");
        assertEquals(Cli.OK, run("docs"));
        assertEquals(generated, out());
    }

    @Test
    void rejectsBadUsage() {
        assertEquals(Cli.USAGE, run());
        assertEquals(Cli.USAGE, run("frobnicate"));
        assertEquals(Cli.USAGE, run("check"));
        assertEquals(Cli.USAGE, run("check", directory.resolve("missing.tys").toString()));
        assertTrue(err().contains("No such file"), err());
        assertEquals(Cli.USAGE, run("dump", "ir"));
        assertEquals(Cli.OK, run("version"));
        assertTrue(out().startsWith("tys "), out());
    }
}
