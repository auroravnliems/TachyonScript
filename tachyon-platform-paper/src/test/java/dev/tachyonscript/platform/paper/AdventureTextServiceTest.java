package dev.tachyonscript.platform.paper;

import dev.tachyonscript.api.natives.Arguments;
import dev.tachyonscript.runtime.spi.MessageTemplate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
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

    /** The first click action of a component tree, or null. */
    private static ClickEvent clickOf(Component component) {
        if (component.clickEvent() != null) {
            return component.clickEvent();
        }
        for (Component child : component.children()) {
            ClickEvent click = clickOf(child);
            if (click != null) {
                return click;
            }
        }
        return null;
    }

    /** The first insertion text of a component tree, or null. */
    private static String insertionOf(Component component) {
        if (component.insertion() != null) {
            return component.insertion();
        }
        for (Component child : component.children()) {
            String insertion = insertionOf(child);
            if (insertion != null) {
                return insertion;
            }
        }
        return null;
    }

    @Test
    void putsValuesIntoClickActionsAsPlainText() {
        MessageTemplate template = text.compile(List.of("<click:run_command:'/tpaccept ", "'>[Accept ", "]</click>"));
        assertEquals("TreeTemplate", template.getClass().getSimpleName());
        Component rendered = (Component) template.render(new Values("Steve", "Steve"));
        assertEquals(ClickEvent.runCommand("/tpaccept Steve"), clickOf(rendered));
        assertEquals("[Accept Steve]", plain(rendered));
        // A value is data wherever it goes: tags in it are neither parsed nor able to end the argument.
        Component tricky = (Component) template.render(new Values("x'><click:run_command:'/op me'>", "y"));
        assertEquals(ClickEvent.runCommand("/tpaccept x'><click:run_command:'/op me'>"), clickOf(tricky));
        // Components go in as their plain text.
        Component named = (Component) template.render(new Values(Component.text("Alex", NamedTextColor.AQUA), "Alex"));
        assertEquals(ClickEvent.runCommand("/tpaccept Alex"), clickOf(named));
    }

    @Test
    void fillsSuggestionsLinksAndInsertionsToo() {
        MessageTemplate suggest = text.compile(List.of("<click:suggest_command:'/msg ", " '>Reply to ", "</click>"));
        assertEquals(ClickEvent.suggestCommand("/msg Steve "), clickOf((Component) suggest.render(new Values("Steve", "Steve"))));
        MessageTemplate link = text.compile(List.of("<click:open_url:'https://example.com/u/", "'>profile</click>"));
        assertEquals(ClickEvent.openUrl("https://example.com/u/Steve"), clickOf((Component) link.render(new Values("Steve"))));
        MessageTemplate insert = text.compile(List.of("<insert:'/tp ", "'>", "</insert>"));
        Component inserted = (Component) insert.render(new Values("Steve", "Steve"));
        assertEquals("/tp Steve", insertionOf(inserted));
        assertEquals("Steve", plain(inserted));
    }

    @Test
    void fillsClickActionsWhenTemplatesFallBack() {
        MessageTemplate template = text.compile(List.of("<gradient:red:blue><click:run_command:'/warp ", "'>Go to ", "</click></gradient>"));
        assertEquals("ParsingTemplate", template.getClass().getSimpleName());
        Component rendered = (Component) template.render(new Values("spawn", "spawn"));
        assertEquals(ClickEvent.runCommand("/warp spawn"), clickOf(rendered));
        assertEquals("Go to spawn", plain(rendered));
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
