package dev.tachyonscript.engine.spi;

import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.runtime.spi.TextService;

/**
 * What the engine needs from a server platform: implementations of the declarations, text
 * support, an event bridge and a logger.
 */
public interface Platform {

    /** Implementations of every declaration scripts may use (standard library, platform, addons). */
    Bindings bindings();

    TextService text();

    EventBridge events();

    EngineLogger logger();
}
