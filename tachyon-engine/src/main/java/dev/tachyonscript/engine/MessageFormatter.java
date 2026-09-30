package dev.tachyonscript.engine;

import dev.tachyonscript.api.natives.Arguments;
import dev.tachyonscript.runtime.spi.MessageTemplate;
import dev.tachyonscript.runtime.spi.TextService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders configured MiniMessage messages with named placeholders ({@code <usage>}). The
 * placeholder values are inserted as plain text through the text service's templates, never
 * parsed, so values from players cannot add tags. Compiled messages are cached.
 */
final class MessageFormatter {

    private static final Pattern TAG = Pattern.compile("<([a-z][a-z0-9-]*)>");

    private record Compiled(MessageTemplate template, List<String> names) {
    }

    private final TextService text;
    private final Map<String, Compiled> cache = new ConcurrentHashMap<>();

    MessageFormatter(TextService text) {
        this.text = text;
    }

    /** The message as a component, with each {@code <name>} of {@code values} replaced by its plain text. */
    Object format(String message, Map<String, String> values) {
        Compiled compiled = cache.computeIfAbsent(message + "\u0000" + String.join(",", values.keySet()),
                key -> compile(message, values));
        Object[] arguments = new Object[compiled.names().size()];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = values.getOrDefault(compiled.names().get(i), "");
        }
        return compiled.template().render(new ObjectArguments(arguments));
    }

    private Compiled compile(String message, Map<String, String> values) {
        List<String> segments = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Matcher matcher = TAG.matcher(message);
        int last = 0;
        while (matcher.find()) {
            if (!values.containsKey(matcher.group(1))) {
                continue; // an ordinary MiniMessage tag
            }
            segments.add(message.substring(last, matcher.start()));
            names.add(matcher.group(1));
            last = matcher.end();
        }
        segments.add(message.substring(last));
        return new Compiled(text.compile(segments), names);
    }

    /** Arguments backed by an array of reference values. */
    static final class ObjectArguments implements Arguments {
        private final Object[] values;

        ObjectArguments(Object[] values) {
            this.values = values;
        }

        @Override
        public int count() {
            return values.length;
        }

        @Override
        public Object getRef(int index) {
            return values[index];
        }

        @Override
        public int getInt(int index) {
            return (Integer) values[index];
        }

        @Override
        public long getLong(int index) {
            return (Long) values[index];
        }

        @Override
        public float getFloat(int index) {
            return (Float) values[index];
        }

        @Override
        public double getDouble(int index) {
            return (Double) values[index];
        }

        @Override
        public boolean getBool(int index) {
            return (Boolean) values[index];
        }
    }
}
