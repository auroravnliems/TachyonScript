package dev.tachyonscript.platform.paper;

import dev.tachyonscript.api.natives.Arguments;
import dev.tachyonscript.runtime.spi.MessageTemplate;
import dev.tachyonscript.runtime.spi.TextService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * MiniMessage-based text for Paper.
 *
 * <p>Templates are compiled once: the MiniMessage text is parsed with a marker component in
 * place of each argument, and rendering replaces the markers in the parsed tree (untouched
 * subtrees are shared), so no MiniMessage parsing happens per message. Tags that transform
 * their content character by character, such as {@code <gradient>} around an argument,
 * cannot be pre-parsed that way; such templates fall back to parsing with placeholder
 * resolvers on each render, which is still correct. Arguments are always inserted as
 * unparsed text (or as components), never as MiniMessage.
 */
public final class AdventureTextService implements TextService {

    private static final String TAG_PREFIX = "tys_arg_";
    /** Private-use characters that cannot appear in script text by accident. */
    private static final char MARKER_OPEN = '\uE000';
    private static final char MARKER_CLOSE = '\uE001';

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    /**
     * Parses templates without the default post-processing: compacting would merge each
     * marker into the neighbouring text and defeat the pre-parsing.
     */
    private final MiniMessage templateParser = MiniMessage.builder().postProcessor(UnaryOperator.identity()).build();

    @Override
    public Object parse(String text) {
        return miniMessage.deserialize(text);
    }

    @Override
    public MessageTemplate compile(List<String> segments) {
        if (segments.size() == 1) {
            Component constant = miniMessage.deserialize(segments.getFirst());
            return arguments -> constant;
        }
        int count = segments.size() - 1;
        StringBuilder source = new StringBuilder(segments.getFirst());
        for (int i = 0; i < count; i++) {
            source.append('<').append(TAG_PREFIX).append(i).append('>').append(segments.get(i + 1));
        }
        String text = source.toString();
        TagResolver.Builder markers = TagResolver.builder();
        for (int i = 0; i < count; i++) {
            markers.tag(TAG_PREFIX + i, Tag.selfClosingInserting(Component.text(marker(i))));
        }
        Component tree = templateParser.deserialize(text, markers.build());
        if (markersIntact(tree, count)) {
            return new TreeTemplate(tree);
        }
        return new ParsingTemplate(miniMessage, text, count);
    }

    static String marker(int index) {
        return MARKER_OPEN + Integer.toString(index) + MARKER_CLOSE;
    }

    /** Whether every marker survived as exactly one whole, unstyled text component. */
    private static boolean markersIntact(Component tree, int count) {
        int[] found = new int[count];
        boolean[] broken = {false};
        visit(tree, component -> {
            if (component instanceof TextComponent text) {
                String content = text.content();
                int index = markerIndex(content);
                if (index >= 0 && index < count && text.children().isEmpty() && text.style().isEmpty()) {
                    found[index]++;
                } else if (content.indexOf(MARKER_OPEN) >= 0 || content.indexOf(MARKER_CLOSE) >= 0) {
                    broken[0] = true;
                }
            }
        });
        if (broken[0]) {
            return false;
        }
        for (int occurrences : found) {
            if (occurrences != 1) {
                return false;
            }
        }
        return true;
    }

    private static void visit(Component component, Consumer<Component> visitor) {
        visitor.accept(component);
        for (Component child : component.children()) {
            visit(child, visitor);
        }
    }

    static int markerIndex(String content) {
        if (content.length() < 3 || content.charAt(0) != MARKER_OPEN || content.charAt(content.length() - 1) != MARKER_CLOSE) {
            return -1;
        }
        try {
            return Integer.parseInt(content, 1, content.length() - 1, 10);
        } catch (NumberFormatException notMarker) {
            return -1;
        }
    }

    static Component argument(Object value) {
        if (value instanceof Component component) {
            return component;
        }
        return Component.text(String.valueOf(value));
    }

    /** Pre-parsed template: rendering substitutes marker components. */
    private static final class TreeTemplate implements MessageTemplate {
        private final Component tree;

        TreeTemplate(Component tree) {
            this.tree = tree;
        }

        @Override
        public Object render(Arguments arguments) {
            return substitute(tree, arguments);
        }

        private static Component substitute(Component node, Arguments arguments) {
            if (node instanceof TextComponent text && node.children().isEmpty()) {
                int index = markerIndex(text.content());
                return index >= 0 ? argument(arguments.getRef(index)) : node;
            }
            List<Component> children = node.children();
            if (children.isEmpty()) {
                return node;
            }
            List<Component> replaced = null;
            for (int i = 0; i < children.size(); i++) {
                Component child = children.get(i);
                Component updated = substitute(child, arguments);
                if (updated != child && replaced == null) {
                    replaced = new ArrayList<>(children);
                }
                if (replaced != null) {
                    replaced.set(i, updated);
                }
            }
            return replaced == null ? node : node.children(replaced);
        }
    }

    /** Fallback: parses with placeholder resolvers on every render. */
    private record ParsingTemplate(MiniMessage miniMessage, String text, int count) implements MessageTemplate {
        @Override
        public Object render(Arguments arguments) {
            TagResolver.Builder resolvers = TagResolver.builder();
            for (int i = 0; i < count; i++) {
                resolvers.tag(TAG_PREFIX + i, Tag.selfClosingInserting(argument(arguments.getRef(i))));
            }
            return miniMessage.deserialize(text, resolvers.build());
        }
    }
}
