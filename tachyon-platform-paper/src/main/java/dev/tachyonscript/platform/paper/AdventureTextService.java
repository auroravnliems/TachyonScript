package dev.tachyonscript.platform.paper;

import dev.tachyonscript.api.natives.Arguments;
import dev.tachyonscript.runtime.spi.MessageTemplate;
import dev.tachyonscript.runtime.spi.TextService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

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
 * unparsed text (or as components), never as MiniMessage. MiniMessage keeps the arguments of
 * tags as text, so a value inside a click action or an insertion
 * ({@code <click:run_command:'/tpaccept {player.name}'>}) is filled in when rendering, as
 * plain text.
 */
public final class AdventureTextService implements TextService {

    private static final String TAG_PREFIX = "tys_arg_";
    /** Private-use characters that cannot appear in script text by accident. */
    private static final char MARKER_OPEN = '\uE000';
    private static final char MARKER_CLOSE = '\uE001';
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

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
        // MiniMessage keeps tag arguments as text, so a value inside '<click:run_command:...>' or
        // '<insert:...>' is still its argument tag there; rendering puts the value in as plain text.
        boolean[] inPayload = new boolean[count];
        visit(tree, component -> markPayloadArguments(component, inPayload));
        boolean payloads = false;
        for (boolean used : inPayload) {
            payloads |= used;
        }
        if (markersIntact(tree, count, inPayload)) {
            return new TreeTemplate(tree, payloads);
        }
        return new ParsingTemplate(miniMessage, text, count, payloads);
    }

    static String marker(int index) {
        return MARKER_OPEN + Integer.toString(index) + MARKER_CLOSE;
    }

    /** Whether every marker survived as exactly one whole, unstyled text component (or sits in a click payload). */
    private static boolean markersIntact(Component tree, int count, boolean[] inPayload) {
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
        for (int i = 0; i < count; i++) {
            if (found[i] != (inPayload[i] ? 0 : 1)) {
                return false;
            }
        }
        return true;
    }

    /** Marks the arguments whose tag is in the click action or insertion text of a component. */
    private static void markPayloadArguments(Component component, boolean[] inPayload) {
        for (String payload : new String[] {clickText(component), component.insertion()}) {
            if (payload == null) {
                continue;
            }
            for (int from = payload.indexOf("<" + TAG_PREFIX); from >= 0; from = payload.indexOf("<" + TAG_PREFIX, from + 1)) {
                int index = argumentTagIndex(payload, from, inPayload.length);
                if (index >= 0) {
                    inPayload[index] = true;
                }
            }
        }
    }

    /** The text of a component's click action (command, URL, copied text...), or null. */
    private static String clickText(Component component) {
        ClickEvent click = component.clickEvent();
        return click != null && click.payload() instanceof ClickEvent.Payload.Text text ? text.value() : null;
    }

    /** The index of the argument tag starting at {@code from}, or -1 if there is none. */
    private static int argumentTagIndex(String payload, int from, int count) {
        int start = from + 1 + TAG_PREFIX.length();
        int end = payload.indexOf('>', start);
        if (end <= start) {
            return -1;
        }
        try {
            int index = Integer.parseInt(payload, start, end, 10);
            return index < count ? index : -1;
        } catch (NumberFormatException notAnArgument) {
            return -1;
        }
    }

    /** Replaces the argument tags in a click action or insertion text by the arguments' plain text. */
    private static String fillPayload(String payload, Arguments arguments) {
        StringBuilder filled = new StringBuilder();
        int copied = 0;
        for (int from = payload.indexOf("<" + TAG_PREFIX); from >= 0; from = payload.indexOf("<" + TAG_PREFIX, from + 1)) {
            int index = argumentTagIndex(payload, from, arguments.count());
            if (index < 0) {
                continue;
            }
            Object value = arguments.getRef(index);
            filled.append(payload, copied, from)
                    .append(value instanceof Component component ? PLAIN.serialize(component) : String.valueOf(value));
            copied = payload.indexOf('>', from) + 1;
        }
        return filled.append(payload, copied, payload.length()).toString();
    }

    /** A copy of the tree whose click actions and insertions hold the arguments' values. */
    static Component fillPayloads(Component node, Arguments arguments) {
        Component result = node;
        String click = clickText(node);
        if (click != null && click.contains("<" + TAG_PREFIX)) {
            result = result.clickEvent(ClickEvent.clickEvent(node.clickEvent().action(),
                    ClickEvent.Payload.string(fillPayload(click, arguments))));
        }
        String insertion = node.insertion();
        if (insertion != null && insertion.contains("<" + TAG_PREFIX)) {
            result = result.insertion(fillPayload(insertion, arguments));
        }
        List<Component> children = result.children();
        List<Component> replaced = null;
        for (int i = 0; i < children.size(); i++) {
            Component child = children.get(i);
            Component updated = fillPayloads(child, arguments);
            if (updated != child && replaced == null) {
                replaced = new ArrayList<>(children);
            }
            if (replaced != null) {
                replaced.set(i, updated);
            }
        }
        return replaced == null ? result : result.children(replaced);
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

    /** Pre-parsed template: rendering substitutes marker components (and fills click payloads). */
    private static final class TreeTemplate implements MessageTemplate {
        private final Component tree;
        private final boolean payloads;

        TreeTemplate(Component tree, boolean payloads) {
            this.tree = tree;
            this.payloads = payloads;
        }

        @Override
        public Object render(Arguments arguments) {
            Component rendered = substitute(tree, arguments);
            return payloads ? fillPayloads(rendered, arguments) : rendered;
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
    private record ParsingTemplate(MiniMessage miniMessage, String text, int count, boolean payloads)
            implements MessageTemplate {
        @Override
        public Object render(Arguments arguments) {
            TagResolver.Builder resolvers = TagResolver.builder();
            for (int i = 0; i < count; i++) {
                resolvers.tag(TAG_PREFIX + i, Tag.selfClosingInserting(argument(arguments.getRef(i))));
            }
            Component rendered = miniMessage.deserialize(text, resolvers.build());
            return payloads ? fillPayloads(rendered, arguments) : rendered;
        }
    }
}
