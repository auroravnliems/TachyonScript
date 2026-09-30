package dev.tachyonscript.stdlib;

import dev.tachyonscript.api.declaration.Effect;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.declaration.ThreadingRequirement;
import dev.tachyonscript.api.doc.Documentation;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.type.Types;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL databases used directly by scripts: {@code Database("main")} (configured by the server
 * owner) or {@code Database.sqlite("shop.db")}. Queries run on background threads and call back
 * on the server thread. SQL text has the type {@link #SQL}, which only accepts text written in
 * the script, so player input can never become SQL: values are passed separately for the
 * {@code ?} placeholders.
 *
 * <p>These declarations are implemented by the engine (not by platforms), which owns the
 * connections and the threads.
 */
public final class DatabaseApi {

    public static final ClassType SQL = ClassType.builder("Sql").constantText()
            .documentation(new Documentation("SQL text. It must be written in the script (a text literal or a const): "
                    + "values go in '?' placeholders and are passed separately, so they can never change the query.",
                    "", List.of(), "0.2.0")).build();

    public static final ClassType DATABASE = ClassType.builder("Database")
            .doc("An SQL database (SQLite or MySQL). Queries run in the background and call back on the server thread.")
            .build();

    public static final ClassType ROW = ClassType.builder("Row")
            .doc("One row of a query result; read columns by name.").build();

    /** The types, in registration order. */
    public static final List<ClassType> TYPES = List.of(SQL, DATABASE, ROW);

    private static final Type PARAMETERS = Types.list(Types.nullable(Types.ANY));

    // ---------------------------------------------------------------- opening

    public static final FunctionDeclaration OPEN = FunctionDeclaration.global("Database")
            .parameter("name", Types.STRING).returns(DATABASE)
            .documentation(new Documentation("A database configured in config.yml (databases: ...).",
                    "SQLite databases are files; MySQL and MariaDB databases are servers. The server owner configures "
                            + "them, so scripts never contain passwords.",
                    List.of("let shop = Database(\"main\")"), "0.2.0"))
            .build();

    public static final FunctionDeclaration SQLITE = FunctionDeclaration.global("Database.sqlite")
            .parameter("file", Types.STRING).returns(DATABASE)
            .documentation(new Documentation("An SQLite database file in plugins/TachyonScript/databases (created if "
                    + "missing).", "", List.of("let homes = Database.sqlite(\"homes.db\")"), "0.2.0"))
            .build();

    // ---------------------------------------------------------------- Database

    public static final PropertyDeclaration NAME = PropertyDeclaration.member(DATABASE, "name", Types.STRING)
            .doc("The database's name.").build();

    public static final FunctionDeclaration EXECUTE = FunctionDeclaration.method(DATABASE, "execute")
            .parameter("sql", SQL).effects(Effect.IO)
            .documentation(new Documentation("Runs a statement in the background (CREATE TABLE, INSERT, ...).",
                    "Errors are reported in the server log with the script location.",
                    List.of("let db = Database.sqlite(\"shop.db\")\ndb.execute(\"CREATE TABLE IF NOT EXISTS sales "
                            + "(player TEXT, item TEXT, price REAL)\")"), "0.2.0"))
            .build();

    public static final FunctionDeclaration EXECUTE_WITH = FunctionDeclaration.method(DATABASE, "execute")
            .parameter("sql", SQL).parameter("parameters", PARAMETERS).effects(Effect.IO)
            .documentation(new Documentation("Runs a statement with values for its '?' placeholders, in the background.",
                    "", List.of("let db = Database.sqlite(\"shop.db\")\ndb.execute(\"INSERT INTO sales VALUES (?, ?, ?)\", "
                            + "[player.uuid, \"bread\", 2.5])"), "0.2.0"))
            .build();

    public static final FunctionDeclaration UPDATE = FunctionDeclaration.method(DATABASE, "update")
            .parameter("sql", SQL).parameter("parameters", PARAMETERS)
            .parameter("then", Types.function(Types.VOID, Types.INT)).effects(Effect.IO)
            .documentation(new Documentation("Runs a statement in the background, then calls the function with the "
                    + "number of changed rows (on the server thread).", "",
                    List.of("let db = Database.sqlite(\"shop.db\")\ndb.update(\"DELETE FROM sales WHERE player = ?\", "
                            + "[player.uuid], count => {\n    player.send(\"Removed {count} sales\")\n})"), "0.2.0"))
            .build();

    public static final FunctionDeclaration QUERY = FunctionDeclaration.method(DATABASE, "query")
            .parameter("sql", SQL).parameter("parameters", PARAMETERS)
            .parameter("then", Types.function(Types.VOID, Types.list(ROW))).effects(Effect.IO)
            .documentation(new Documentation("Runs a query in the background, then calls the function with the rows "
                    + "(on the server thread).", "The function only runs if the script is still loaded.",
                    List.of("let db = Database.sqlite(\"shop.db\")\ndb.query(\"SELECT item, price FROM sales WHERE player = ?\", "
                            + "[player.uuid], rows => {\n    for row in rows {\n        let item = row.string(\"item\")\n"
                            + "        player.send(\"You bought {item}\")\n    }\n})"), "0.2.0"))
            .build();

    public static final FunctionDeclaration QUERY_ALL = FunctionDeclaration.method(DATABASE, "query")
            .parameter("sql", SQL).parameter("then", Types.function(Types.VOID, Types.list(ROW))).effects(Effect.IO)
            .doc("Runs a query without parameters in the background, then calls the function with the rows.").build();

    public static final FunctionDeclaration QUERY_FIRST = FunctionDeclaration.method(DATABASE, "queryFirst")
            .parameter("sql", SQL).parameter("parameters", PARAMETERS)
            .parameter("then", Types.function(Types.VOID, Types.nullable(ROW))).effects(Effect.IO)
            .doc("Runs a query in the background, then calls the function with the first row, or null if there is none.")
            .build();

    public static final FunctionDeclaration QUERY_SYNC = FunctionDeclaration.method(DATABASE, "querySync")
            .parameter("sql", SQL).parameter("parameters", PARAMETERS).returns(Types.list(ROW))
            .effects(Effect.IO).threading(ThreadingRequirement.ASYNC)
            .documentation(new Documentation("Runs a query and waits for the rows. Only allowed off the server thread "
                    + "(inside async { } or an asynchronous event).", "",
                    List.of("async {\n    let db = Database.sqlite(\"shop.db\")\n    let rows = db.querySync(\"SELECT COUNT(*) "
                            + "AS total FROM sales\", [])\n    let total = rows[0].long(\"total\")\n    log(\"sales: {total}\")\n}"),
                    "0.2.0"))
            .build();

    public static final FunctionDeclaration UPDATE_SYNC = FunctionDeclaration.method(DATABASE, "updateSync")
            .parameter("sql", SQL).parameter("parameters", PARAMETERS).returns(Types.INT)
            .effects(Effect.IO).threading(ThreadingRequirement.ASYNC)
            .doc("Runs a statement, waits, and returns the number of changed rows. Only allowed off the server thread.")
            .build();

    // ---------------------------------------------------------------- Row

    public static final PropertyDeclaration ROW_COLUMNS = PropertyDeclaration.member(ROW, "columns", Types.list(Types.STRING))
            .doc("The column names, in query order.").build();

    public static final FunctionDeclaration ROW_GET = rowGetter("get", Types.nullable(Types.ANY),
            "The value of a column (text, long, double, bool, or null).");
    public static final FunctionDeclaration ROW_STRING = rowGetter("string", Types.nullable(Types.STRING),
            "A column as text, or null.");
    public static final FunctionDeclaration ROW_INT = rowGetter("int", Types.nullable(Types.INT),
            "A column as an int, or null.");
    public static final FunctionDeclaration ROW_LONG = rowGetter("long", Types.nullable(Types.LONG),
            "A column as a long, or null.");
    public static final FunctionDeclaration ROW_DOUBLE = rowGetter("double", Types.nullable(Types.DOUBLE),
            "A column as a double, or null.");
    public static final FunctionDeclaration ROW_BOOL = rowGetter("bool", Types.nullable(Types.BOOL),
            "A column as a bool (numbers: 0 is false), or null.");
    public static final FunctionDeclaration ROW_UUID = rowGetter("uuid", Types.nullable(MinecraftTypes.UUID),
            "A column holding a UUID (as text), or null.");
    public static final FunctionDeclaration ROW_INSTANT = rowGetter("instant", Types.nullable(Types.INSTANT),
            "A column holding a time in milliseconds (as saved from an Instant), or null.");
    public static final FunctionDeclaration ROW_TO_STRING = FunctionDeclaration.method(ROW, "toString")
            .returns(Types.STRING).doc("Text such as '{item: bread, price: 2.5}'.").build();

    public static final List<PropertyDeclaration> PROPERTIES = List.of(NAME, ROW_COLUMNS);
    public static final List<FunctionDeclaration> FUNCTIONS = List.of(OPEN, SQLITE, EXECUTE, EXECUTE_WITH, UPDATE, QUERY,
            QUERY_ALL, QUERY_FIRST, QUERY_SYNC, UPDATE_SYNC, ROW_GET, ROW_STRING, ROW_INT, ROW_LONG, ROW_DOUBLE, ROW_BOOL,
            ROW_UUID, ROW_INSTANT, ROW_TO_STRING);

    private DatabaseApi() {
    }

    private static FunctionDeclaration rowGetter(String name, Type type, String summary) {
        return FunctionDeclaration.method(ROW, name).parameter("column", Types.STRING).returns(type).doc(summary).build();
    }

    /** Every operation of this API (the engine implements them). */
    public static List<NativeDeclaration> natives() {
        List<NativeDeclaration> natives = new ArrayList<>();
        for (PropertyDeclaration property : PROPERTIES) {
            natives.add(property.getter());
        }
        for (FunctionDeclaration function : FUNCTIONS) {
            natives.add(function.invocable());
        }
        return natives;
    }
}
