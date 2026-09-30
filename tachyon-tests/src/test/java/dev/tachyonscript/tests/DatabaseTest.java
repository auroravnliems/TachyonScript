package dev.tachyonscript.tests;

import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.LoadReport;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.database.DatabaseConfig;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.testkit.Fakes;
import dev.tachyonscript.testkit.InMemoryScripts;
import dev.tachyonscript.testkit.TestPlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Databases used by scripts: real SQLite files, background queries, callbacks on the server thread. */
class DatabaseTest {

    @TempDir
    Path folder;

    private TestPlatform platform;
    private ScriptEngine engine;
    private InMemoryScripts scripts;

    @BeforeEach
    void setUp() {
        platform = new TestPlatform();
        engine = platform.engine(EngineOptions.DEFAULT.withDatabases(
                Map.of("main", DatabaseConfig.sqlite("main", folder.resolve("main.db"))), folder.resolve("databases")));
        scripts = new InMemoryScripts();
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    private LoadReport load() {
        LoadReport report = engine.load(scripts);
        StringBuilder rendered = new StringBuilder();
        report.diagnostics().forEach(d -> rendered.append(DiagnosticRenderer.plain().render(d)).append('\n'));
        assertTrue(report.activated() && report.failed().isEmpty(), () -> rendered + " " + report.linkProblems()
                + " " + report.failure() + " " + platform.logs());
        return report;
    }

    /** Ticks the server until {@code condition} holds (database work happens on real threads). */
    private void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, () -> "timed out; logs: " + platform.logs());
            platform.scheduler().tick(1);
            Thread.sleep(5);
        }
    }

    @Test
    void scriptsStoreAndQueryRowsInSqlite() throws InterruptedException {
        scripts.put("shop.tys", """
                let shop = Database.sqlite("shop.db")

                on load {
                    shop.execute("CREATE TABLE IF NOT EXISTS sales (player TEXT, item TEXT, price REAL)")
                }

                @playerOnly
                command buy(item: string, price: double) {
                    shop.update("INSERT INTO sales VALUES (?, ?, ?)", [player.uuid, item, price], count => {
                        player.send("saved {count}")
                    })
                }

                @playerOnly
                command history() {
                    shop.query("SELECT item, price FROM sales WHERE player = ? ORDER BY price", [player.uuid], rows => {
                        for row in rows {
                            let item = row.string("item")
                            let price = row.double("price")
                            player.send("{item} {price}")
                        }
                        player.send("{rows.size} sales")
                    })
                }
                """);
        load();
        Fakes.Player alex = platform.join("Alex");
        platform.command(alex, "buy bread 2.5");
        platform.command(alex, "buy apple 1");
        awaitTrue(() -> alex.messages().size() >= 2);
        assertEquals(List.of("saved 1", "saved 1"), alex.messages());

        platform.command(alex, "history");
        awaitTrue(() -> alex.messages().size() >= 5);
        assertEquals(List.of("apple 1", "bread 2.5", "2 sales"), alex.messages().subList(2, 5));
        assertTrue(Files.exists(folder.resolve("databases").resolve("shop.db")));
    }

    @Test
    void configuredDatabasesAndFirstRows() throws InterruptedException {
        scripts.put("stats.tys", """
                let db = Database("main")

                on load {
                    db.execute("CREATE TABLE IF NOT EXISTS stats (name TEXT PRIMARY KEY, joins INTEGER)")
                }

                event player.join {
                    db.execute("INSERT INTO stats VALUES (?, 1) ON CONFLICT(name) DO UPDATE SET joins = joins + 1", [player.name])
                    db.queryFirst("SELECT joins FROM stats WHERE name = ?", [player.name], row => {
                        if row != null {
                            let joins = row.long("joins")
                            player.send("joins: {joins}")
                        }
                    })
                }
                """);
        load();
        Fakes.Player sam = platform.join("Sam");
        platform.fire(engine, dev.tachyonscript.stdlib.EventApi.PLAYER_JOIN, new Fakes.JoinEvent(sam));
        awaitTrue(() -> !sam.messages().isEmpty());
        assertEquals("joins: 1", sam.messages().get(0));
        platform.fire(engine, dev.tachyonscript.stdlib.EventApi.PLAYER_JOIN, new Fakes.JoinEvent(sam));
        awaitTrue(() -> sam.messages().size() >= 2);
        assertEquals("joins: 2", sam.messages().get(1));
    }

    @Test
    void sqlBuiltFromValuesIsACompileError() {
        scripts.put("unsafe.tys", """
                let db = Database("main")

                command find(name: string) {
                    db.query("SELECT * FROM stats WHERE name = '" + name + "'", [], rows => {
                        log(rows.size)
                    })
                }
                """);
        LoadReport report = engine.load(scripts);
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.code() == DiagnosticCode.CONSTANT_TEXT_REQUIRED),
                () -> report.diagnostics().toString());
    }

    @Test
    void failedStatementsAreLoggedWithTheScript() throws InterruptedException {
        scripts.put("broken.tys", """
                let db = Database.sqlite("broken.db")

                on load {
                    db.execute("SELECT * FROM missing_table")
                }
                """);
        load();
        awaitTrue(() -> platform.logs().stream().anyMatch(line -> line.contains("missing_table")));
        String warning = platform.logs().stream().filter(line -> line.contains("missing_table")).findFirst().orElseThrow();
        assertTrue(warning.startsWith("WARN") && warning.contains("broken.tys"), warning);
    }

    @Test
    void synchronousQueriesAreRefusedOnTheServerThread() {
        scripts.put("sync.tys", """
                let db = Database.sqlite("sync.db")

                command count() {
                    let rows = db.querySync("SELECT 1 AS one", [])
                    log(rows.size)
                }
                """);
        load();
        platform.command(platform.console(), "count");
        assertTrue(platform.logs().stream().anyMatch(line -> line.startsWith("ERROR") && line.contains("querySync")),
                () -> platform.logs().toString());
    }
}
