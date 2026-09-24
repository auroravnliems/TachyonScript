package dev.tachyonscript.platform.paper;

import dev.tachyonscript.engine.spi.EngineLogger;

import java.util.logging.Logger;

/** Engine log output through the plugin logger (prefixed with {@code [TachyonScript]} by Paper). */
public final class PaperLogger implements EngineLogger {

    private final Logger logger;

    public PaperLogger(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message) {
        logger.warning(message);
    }

    @Override
    public void error(String message) {
        logger.severe(message);
    }
}
