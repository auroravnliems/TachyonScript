package dev.tachyonscript.engine.spi;

/** Log output of the engine; the platform maps it to its logger. */
public interface EngineLogger {

    void info(String message);

    void warn(String message);

    void error(String message);

    /** Collects nothing (tests and tools). */
    EngineLogger SILENT = new EngineLogger() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message) {
        }
    };
}
