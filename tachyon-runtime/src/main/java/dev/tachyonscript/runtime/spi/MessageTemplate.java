package dev.tachyonscript.runtime.spi;

import dev.tachyonscript.api.natives.Arguments;

/**
 * A message template compiled once by the platform {@link TextService}.
 */
@FunctionalInterface
public interface MessageTemplate {

    /**
     * Renders the template. Each argument is a {@code String} (inserted as plain text, never
     * parsed as MiniMessage) or a component (inserted as is).
     */
    Object render(Arguments arguments);
}
