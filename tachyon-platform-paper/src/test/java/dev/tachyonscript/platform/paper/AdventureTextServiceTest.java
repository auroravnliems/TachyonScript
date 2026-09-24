package dev.tachyonscript.platform.paper;

import dev.tachyonscript.api.natives.Arguments;
import dev.tachyonscript.runtime.spi.MessageTemplate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdventureTextServiceTest {

    private final AdventureTextService text = new AdventureTextService();

    private record Values(Object... values) implements Arguments {
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
            throw new UnsupportedOperationException();
        }

        @Override
        public long getLong(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public float getFloat(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double getDouble(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean getBool(int index) {
            throw new UnsupportedOperationException();
        }
    }

    private static String plain(Object component) {
        return PlainTextComponentSerializer.plainText().serialize((Component) component);
    }

    @Test
    void constantTemplatesAreParsedOnce() {
        MessageTemplate template = text.compile(List.of("<green>Hello!"));
        Object first = template.render(new Values());
        assertSame(first, template.render(new Values()));
        assertEquals(MiniMessage.miniMessage().deserialize("<green>Hello!"), first);
    }

    @Test
    void preParsesTemplatesWithArguments() {
        for (List<String> segments : List.of(
                List.of("<green>Welcome, ", "!"),
                List.of("", " joined"),
                List.of("<gold>[Server]</gold> <gray>", " has ", " coins"),
                List.of("<hover:show_text:'<red>Hi'>Name: ", "</hover> and ", ""))) {
            MessageTemplate template = text.compile(segments);
            assertTrue(template.getClass().getSimpleName().equals("TreeTemplate"),
                    () -> segments + " fell back to " + template.getClass().getSimpleName());
        }
    }

    @Test
    void rendersLikeMiniMessage() {
        MessageTemplate template = text.compile(List.of("<gold>[Server]</gold> <gray>", " has ", " coins"));
        Object rendered = template.render(new Values("Steve", "42"));
        assertEquals("[Server] Steve has 42 coins", plain(rendered));
        Component expected = MiniMessage.miniMessage().deserialize("<gold>[Server]</gold> <gray>Steve has 42 coins");
        assertEquals(plain(expected), plain(rendered));
    }

    @Test
    void fallsBackForTagsThatTransformArguments() {
        MessageTemplate template = text.compile(List.of("<gradient:red:blue>Hi ", "</gradient>"));
        assertEquals("ParsingTemplate", template.getClass().getSimpleName());
        assertEquals("Hi Steve", plain(template.render(new Values("Steve"))));
    }

    @Test
    void neverParsesArguments() {
        MessageTemplate template = text.compile(List.of("<green>Welcome, ", "!"));
        Component rendered = (Component) template.render(new Values("<red><bold>Mallory"));
        assertEquals("Welcome, <red><bold>Mallory!", plain(rendered));
        Component gradient = (Component) text.compile(List.of("<gradient:red:blue>", "</gradient>"))
                .render(new Values("<click:run_command:'/op me'>x"));
        assertEquals("<click:run_command:'/op me'>x", plain(gradient));
    }

    @Test
    void insertsComponentsAsIs() {
        MessageTemplate template = text.compile(List.of("Name: ", ""));
        Component name = Component.text("Steve", NamedTextColor.AQUA);
        Component rendered = (Component) template.render(new Values(name));
        assertEquals("Name: Steve", plain(rendered));
    }

    @Test
    void survivesMarkerCharactersInScriptText() {
        String marker = AdventureTextService.marker(0);
        MessageTemplate template = text.compile(List.of("literal " + marker + " then ", "!"));
        assertEquals("literal " + marker + " then Steve!", plain(template.render(new Values("Steve"))));
        MessageTemplate own = text.compile(List.of("<red>" + marker + "</red>", ""));
        assertEquals(marker + "Steve", plain(own.render(new Values("Steve"))));
    }
}
