package dev.tachyonscript.runtime.spi;

import java.util.List;

/**
 * Platform text support. On Paper, components are Adventure components and parsing uses
 * MiniMessage; the runtime itself treats components as opaque objects.
 */
public interface TextService {

    /** Parses MiniMessage text into a component (used for runtime string → Component conversions). */
    Object parse(String miniMessage);

    /**
     * Compiles a template at link time. {@code segments} are MiniMessage text surrounding the
     * arguments ({@code segments.size() - 1} of them); the result is reused for every render.
     */
    MessageTemplate compile(List<String> segments);
}
