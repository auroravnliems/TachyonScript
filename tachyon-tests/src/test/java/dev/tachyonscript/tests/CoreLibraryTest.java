package dev.tachyonscript.tests;

import dev.tachyonscript.engine.LoadReport;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.testkit.Fakes;
import dev.tachyonscript.testkit.InMemoryScripts;
import dev.tachyonscript.testkit.TestPlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server-independent part of the generated standard library, run through real scripts.
 * Dynamic text is sent with templates ({@code "{value}"}), which insert values as plain text.
 */
class CoreLibraryTest {

    private TestPlatform platform;
    private ScriptEngine engine;
    private InMemoryScripts scripts;

    @BeforeEach
    void setUp() {
        platform = new TestPlatform();
        engine = platform.engine();
        scripts = new InMemoryScripts();
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    /** Runs {@code body} as the command /t of a player and returns what it sent. */
    private List<Object> run(String body) {
        StringBuilder script = new StringBuilder("@playerOnly\ncommand t() {\n");
        body.lines().forEach(line -> script.append("    ").append(line).append('\n'));
        scripts.put("test.tys", script.append("}\n").toString());
        LoadReport report = engine.load(scripts);
        StringBuilder rendered = new StringBuilder();
        report.diagnostics().forEach(d -> rendered.append(DiagnosticRenderer.plain().render(d)).append('\n'));
        assertTrue(report.activated() && report.failed().isEmpty(), () -> rendered + " " + report.linkProblems());
        Fakes.Player player = platform.onlinePlayers().isEmpty() ? platform.join("Alex") : platform.onlinePlayers().get(0);
        player.messages().clear();
        platform.command(player, "t");
        assertTrue(platform.logs().stream().noneMatch(line -> line.startsWith("ERROR")), () -> platform.logs().toString());
        return List.copyOf(player.messages());
    }

    @Test
    void formatsNumbersAndDurations() {
        assertEquals(List.of("1,234,567", "1,234.50", "2.50", "1.5K", "2.3M", "25.6%", "XIV", "22nd", "1 hour, 30 minutes",
                        "01:05", "1:02:03", "1.5 KB", "3 apples"),
                run("""
                        player.send("{format.number(1234567)}")
                        player.send("{format.money(1234.5)}")
                        player.send("{format.decimal(2.5, 2)}")
                        player.send("{format.compact(1500)}")
                        player.send("{format.compact(2345678)}")
                        player.send("{format.percent(0.256)}")
                        player.send("{format.roman(14)}")
                        player.send("{format.ordinal(22)}")
                        player.send("{format.duration(90 minutes)}")
                        player.send("{format.timer(65 seconds)}")
                        player.send("{format.timer(3723 seconds)}")
                        player.send("{format.bytes(1536)}")
                        player.send("{format.plural(3, "apple", "apples")}")
                        """));
    }

    @Test
    void manipulatesText() {
        assertEquals(List.of("[a, b, c]", "[a, b,c]", "007", "ab--", "Diamond Sword", "olleh", "2", "true", "[12, 7]",
                        "x-y-z", "hello...", "[h, i]", "3"),
                run("""
                        player.send("{"a,b,c".split(",")}")
                        player.send("{"a,b,c".split(",", 2)}")
                        player.send("{"7".padLeft(3, "0")}")
                        player.send("{"ab".padRight(4, "-")}")
                        player.send("{"diamond_sword".titleCase()}")
                        player.send("{"hello".reversed()}")
                        player.send("{"banana".count("an")}")
                        player.send("{"Hello".containsIgnoreCase("ELL")}")
                        player.send("{"a12b7".findAll("[0-9]+")}")
                        player.send("{"x y z".replaceAll(" ", "-")}")
                        player.send("{"hello world".truncate(8)}")
                        player.send("{"hi".chars()}")
                        player.send("{"abc".length}")
                        """));
    }

    @Test
    void readsAndWritesJson() {
        assertEquals(List.of("{\"name\":\"Alex\",\"level\":5,\"tags\":[\"a\",true,null]}", "Alex", "5", "3", "null"),
                run("""
                        let data = json.parseMap("\\{\\"name\\": \\"Alex\\", \\"level\\": 5, \\"tags\\": [\\"a\\", true, null]}")
                        player.send("{json.stringify(data)}")
                        player.send("{json.asString(data["name"])}")
                        player.send("{json.asNumber(data["level"])}")
                        let tags = json.asList(data["tags"]) ?? []
                        player.send("{tags.size}")
                        player.send("{json.asMap(data["name"])}")
                        """));
    }

    @Test
    void castsUnknownValuesToListsAndMaps() {
        assertEquals(List.of("Alex", "2", "true", "true"),
                run("""
                        let data = json.parse("\\{\\"name\\": \\"Alex\\", \\"scores\\": [1, 2]}") as Map<string, any?>
                        player.send("{data["name"]}")
                        let scores = data["scores"] as List<any?>
                        player.send("{scores.size}")
                        player.send("{(data["name"] as? List<any?>) == null}")
                        player.send("{(data["scores"] as? Map<string, any?>) == null}")
                        """));
    }

    @Test
    void randomNumbersStayInRange() {
        List<Object> messages = run("""
                var ok = true
                for i in 0..<1000 {
                    let roll = random.int(1, 6)
                    if roll < 1 || roll > 6 {
                        ok = false
                    }
                    let fraction = random.double(2.0, 3.0)
                    if fraction < 2.0 || fraction >= 3.0 {
                        ok = false
                    }
                }
                player.send("{ok}")
                player.send("{random.string(8).length}")
                player.send("{random.weighted([0.0, 1.0, 0.0])}")
                """);
        assertEquals(List.of("true", "8", "1"), messages);
    }

    @Test
    void timeAndUuidHelpers() {
        List<Object> messages = run("""
                let start = time.of(2026, 9, 30, 14, 5)
                player.send("{time.format(start, "yyyy-MM-dd HH:mm")}")
                player.send("{time.date(start)}")
                player.send("{time.clock(start)}")
                let parsed = time.parse("2026-12-31 23:59", "yyyy-MM-dd HH:mm")
                player.send("{parsed != null}")
                player.send("{time.parse("not a date", "yyyy-MM-dd") == null}")
                player.send("{time.since(time.now) <= 1 second}")
                player.send("{UUID.fromString("00000000-0000-0000-0000-000000000001") != null}")
                player.send("{UUID.fromString("nope") == null}")
                player.send("{math.roundTo(3.14159, 2)} {math.floorMod(-1, 5)} {math.sign(-4.0)} {math.log(8, 2)}")
                """);
        assertEquals(List.of("2026-09-30 14:05", "2026-09-30", "14:05", "true", "true", "true", "true", "true",
                "3.14 4 -1 3"), messages);
    }
}
