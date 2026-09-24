package dev.tachyonscript.plugin;

import dev.tachyonscript.api.TachyonVersion;
import dev.tachyonscript.compiler.InternalErrorHandler;
import dev.tachyonscript.language.source.SourceFile;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * Writes the details of internal compiler errors to {@code logs/compiler-error-*.log}, so the
 * console only shows a short message. Script contents and the environment are not included.
 */
final class CrashReports implements InternalErrorHandler {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path directory;
    private final Logger logger;
    private final String serverVersion;

    CrashReports(Path directory, Logger logger, String serverVersion) {
        this.directory = directory;
        this.logger = logger;
        this.serverVersion = serverVersion;
    }

    @Override
    public void report(String phase, SourceFile file, Throwable error, String diagnosticId) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        String report = "TachyonScript internal compiler error\n"
                + "Diagnostic ID: " + diagnosticId + "\n"
                + "Time: " + LocalDateTime.now() + "\n"
                + "Compiler: " + TachyonVersion.RUNTIME + " (language level " + TachyonVersion.LANGUAGE_LEVEL
                + ", IR format " + TachyonVersion.IR_FORMAT + ")\n"
                + "Phase: " + phase + "\n"
                + "Script: " + file.path() + " (sha256 " + file.hash() + ")\n"
                + "Server: " + serverVersion + "\n"
                + "Java: " + System.getProperty("java.version") + "\n\n"
                + trace;
        try {
            Files.createDirectories(directory);
            Path path = directory.resolve("compiler-error-" + LocalDateTime.now().format(FILE_TIME) + "-" + diagnosticId + ".log");
            Files.writeString(path, report);
            logger.severe("Internal compiler error " + diagnosticId + " while " + phase + " " + file.path()
                    + "; details were written to " + path);
        } catch (IOException writeFailure) {
            logger.severe("Internal compiler error " + diagnosticId + " (the report could not be written: "
                    + writeFailure.getMessage() + ")\n" + report);
        }
    }
}
