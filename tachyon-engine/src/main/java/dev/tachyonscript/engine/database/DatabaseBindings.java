package dev.tachyonscript.engine.database;

import dev.tachyonscript.api.natives.Arguments;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.natives.ScriptFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.engine.LoadedScript;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.stdlib.DatabaseApi;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The engine's implementation of {@link DatabaseApi}. Asynchronous statements run on the
 * database's threads; their functions are then called on the global region thread, only if
 * the script that asked is still loaded. Failures are logged with the script's path.
 */
public final class DatabaseBindings {

    /** How long a synchronous statement may take. */
    private static final long SYNC_TIMEOUT_SECONDS = 30;

    private enum Result {
        NONE,
        COUNT,
        ROWS,
        FIRST
    }

    private DatabaseBindings() {
    }

    public static Bindings create(ScriptEngine engine, Databases databases) {
        Bindings.Builder b = Bindings.builder();
        b.bindType(DatabaseApi.DATABASE, ScriptDatabase.class);
        b.bindType(DatabaseApi.ROW, DatabaseRow.class);
        b.bindType(DatabaseApi.SQL, String.class);
        b.bind(DatabaseApi.OPEN, (NativeFunction.OfRef) a -> databases.named(a.getString(0)));
        b.bind(DatabaseApi.SQLITE, (NativeFunction.OfRef) a -> databases.sqlite(a.getString(0)));
        b.bindGetter(DatabaseApi.NAME, (NativeFunction.OfRef) a -> database(a).name());
        b.bind(DatabaseApi.EXECUTE, (NativeFunction.OfVoid) a ->
                background(engine, database(a), a.getString(1), List.of(), null, Result.NONE));
        b.bind(DatabaseApi.EXECUTE_WITH, (NativeFunction.OfVoid) a ->
                background(engine, database(a), a.getString(1), parameters(a, 2), null, Result.NONE));
        b.bind(DatabaseApi.UPDATE, (NativeFunction.OfVoid) a ->
                background(engine, database(a), a.getString(1), parameters(a, 2), function(a, 3), Result.COUNT));
        b.bind(DatabaseApi.QUERY, (NativeFunction.OfVoid) a ->
                background(engine, database(a), a.getString(1), parameters(a, 2), function(a, 3), Result.ROWS));
        b.bind(DatabaseApi.QUERY_ALL, (NativeFunction.OfVoid) a ->
                background(engine, database(a), a.getString(1), List.of(), function(a, 2), Result.ROWS));
        b.bind(DatabaseApi.QUERY_FIRST, (NativeFunction.OfVoid) a ->
                background(engine, database(a), a.getString(1), parameters(a, 2), function(a, 3), Result.FIRST));
        b.bind(DatabaseApi.QUERY_SYNC, (NativeFunction.OfRef) a -> {
            ScriptDatabase database = database(a);
            String sql = a.getString(1);
            List<Object> values = parameters(a, 2);
            return waitFor(engine, "querySync", database.submit(() -> database.query(sql, values)));
        });
        b.bind(DatabaseApi.UPDATE_SYNC, (NativeFunction.OfInt) a -> {
            ScriptDatabase database = database(a);
            String sql = a.getString(1);
            List<Object> values = parameters(a, 2);
            return waitFor(engine, "updateSync", database.submit(() -> database.update(sql, values)));
        });

        b.bindGetter(DatabaseApi.ROW_COLUMNS, (NativeFunction.OfRef) a -> row(a).columns());
        b.bind(DatabaseApi.ROW_GET, (NativeFunction.OfRef) a -> row(a).get(a.getString(1)));
        b.bind(DatabaseApi.ROW_STRING, (NativeFunction.OfRef) a -> row(a).string(a.getString(1)));
        b.bind(DatabaseApi.ROW_INT, (NativeFunction.OfRef) a -> row(a).intValue(a.getString(1)));
        b.bind(DatabaseApi.ROW_LONG, (NativeFunction.OfRef) a -> row(a).longValue(a.getString(1)));
        b.bind(DatabaseApi.ROW_DOUBLE, (NativeFunction.OfRef) a -> row(a).doubleValue(a.getString(1)));
        b.bind(DatabaseApi.ROW_BOOL, (NativeFunction.OfRef) a -> row(a).boolValue(a.getString(1)));
        b.bind(DatabaseApi.ROW_UUID, (NativeFunction.OfRef) a -> row(a).uuid(a.getString(1)));
        b.bind(DatabaseApi.ROW_INSTANT, (NativeFunction.OfRef) a -> row(a).longValue(a.getString(1)));
        b.bind(DatabaseApi.ROW_TO_STRING, (NativeFunction.OfRef) a -> row(a).toString());
        return b.build();
    }

    private static ScriptDatabase database(Arguments arguments) {
        return (ScriptDatabase) arguments.getRef(0);
    }

    private static DatabaseRow row(Arguments arguments) {
        return (DatabaseRow) arguments.getRef(0);
    }

    private static ScriptFunction function(Arguments arguments, int index) {
        return (ScriptFunction) arguments.getRef(index);
    }

    /** A copy of the parameter list, taken on the calling thread (the script may change its list later). */
    private static List<Object> parameters(Arguments arguments, int index) {
        List<?> list = (List<?>) arguments.getRef(index);
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    private static void background(ScriptEngine engine, ScriptDatabase database, String sql, List<Object> parameters,
                                   ScriptFunction then, Result result) {
        LoadedScript script = ScriptEngine.runningScript();
        String origin = script == null ? "a script" : script.path();
        database.submit(() -> {
            Object value;
            try {
                value = switch (result) {
                    case NONE, COUNT -> database.update(sql, parameters);
                    case ROWS -> database.query(sql, parameters);
                    case FIRST -> {
                        List<Object> rows = database.query(sql, parameters);
                        yield rows.isEmpty() ? null : rows.getFirst();
                    }
                };
            } catch (SQLException | RuntimeException e) {
                engine.platform().logger().warn("Database '" + database.name() + "': a statement from " + origin
                        + " failed: " + e.getMessage() + " (SQL: " + abbreviate(sql) + ")");
                return null;
            }
            if (then != null) {
                engine.platform().scheduler().runGlobal(() -> engine.callback(then, value));
            }
            return null;
        });
    }

    private static <T> T waitFor(ScriptEngine engine, String what, Future<T> future) {
        if (engine.platform().scheduler().isTickThread()) {
            future.cancel(false);
            throw new ScriptError(what + " would freeze the server while the database works. Use the version with a "
                    + "function (query, update), or call it inside async { }.");
        }
        try {
            return future.get(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScriptError(what + " was interrupted.");
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ScriptError(what + " took longer than " + SYNC_TIMEOUT_SECONDS + " seconds.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ScriptError error) {
                throw error;
            }
            throw new ScriptError(what + " failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
        }
    }

    private static String abbreviate(String sql) {
        String line = sql.replaceAll("\\s+", " ").strip();
        return line.length() <= 120 ? line : line.substring(0, 117) + "...";
    }
}
