package dev.tachyonscript.runtime.spi;

import dev.tachyonscript.api.natives.Arguments;

import java.util.List;

/**
 * Text service for tools and tests: a "component" is simply its MiniMessage source as a
 * {@code String}. Plain-text arguments have {@code <} escaped so the result stays faithful
 * to the insertion semantics (arguments never become tags).
 */
public final class SimpleTextService implements TextService {

    public static final SimpleTextService INSTANCE = new SimpleTextService();

    private SimpleTextService() {
    }

    @Override
    public Object parse(String miniMessage) {
        return miniMessage;
    }

    @Override
    public MessageTemplate compile(List<String> segments) {
        String[] parts = segments.toArray(String[]::new);
        return arguments -> render(parts, arguments);
    }

    private static String render(String[] parts, Arguments arguments) {
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            Object value = arguments.getRef(i - 1);
            out.append(value instanceof String text ? text.replace("<", "\\<") : String.valueOf(value)).append(parts[i]);
        }
        return out.toString();
    }
}
