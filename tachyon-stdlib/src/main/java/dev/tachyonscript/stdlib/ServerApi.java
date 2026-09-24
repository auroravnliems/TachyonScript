package dev.tachyonscript.stdlib;

import dev.tachyonscript.api.declaration.Effect;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.doc.Documentation;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.type.Types;

import java.util.ArrayList;
import java.util.List;

import static dev.tachyonscript.stdlib.MinecraftTypes.PLAYER;
import static dev.tachyonscript.stdlib.MinecraftTypes.WORLD;

/** The {@code server} namespace, {@code broadcast} and the {@code log} functions. */
public final class ServerApi {

    public static final PropertyDeclaration PLAYERS = PropertyDeclaration.global("server.players", Types.list(PLAYER))
            .getterEffects(Effect.READS_WORLD)
            .documentation(new Documentation("A snapshot of the online players.", "",
                    List.of("for player in server.players {\n    player.send(\"Hello!\")\n}"), "0.1.0"))
            .build();

    public static final FunctionDeclaration PLAYER_BY_NAME = FunctionDeclaration.global("server.player")
            .parameter("name", Types.STRING).returns(Types.nullable(PLAYER))
            .effects(Effect.READS_WORLD)
            .doc("The online player with this exact name, or null.").build();

    public static final PropertyDeclaration ONLINE_COUNT = PropertyDeclaration.global("server.onlineCount", Types.INT)
            .doc("Number of online players.").build();

    public static final PropertyDeclaration MAX_PLAYERS = PropertyDeclaration.global("server.maxPlayers", Types.INT)
            .doc("The player limit.").build();

    public static final PropertyDeclaration WORLDS = PropertyDeclaration.global("server.worlds", Types.list(WORLD))
            .doc("The loaded worlds.").build();

    public static final FunctionDeclaration WORLD_BY_NAME = FunctionDeclaration.global("server.world")
            .parameter("name", Types.STRING).returns(Types.nullable(WORLD))
            .doc("The loaded world with this name, or null.").build();

    public static final FunctionDeclaration BROADCAST = FunctionDeclaration.global("broadcast")
            .parameter("message", Types.COMPONENT)
            .doc("Sends a message to every online player and the console.").build();

    /** {@code log}, {@code log.info}, {@code log.warn}, {@code log.error}, {@code log.debug} with string and any overloads. */
    public static final List<FunctionDeclaration> LOG;

    public static final FunctionDeclaration LOG_STRING;
    public static final FunctionDeclaration LOG_INFO;
    public static final FunctionDeclaration LOG_WARN;
    public static final FunctionDeclaration LOG_ERROR;
    public static final FunctionDeclaration LOG_DEBUG;

    static {
        List<FunctionDeclaration> log = new ArrayList<>();
        LOG_STRING = log(log, "log", Types.STRING, "Writes a message to the server log.");
        log(log, "log", Types.nullable(Types.ANY), "Writes a value to the server log.");
        LOG_INFO = log(log, "log.info", Types.STRING, "Writes an information message to the server log.");
        log(log, "log.info", Types.nullable(Types.ANY), "Writes a value to the server log.");
        LOG_WARN = log(log, "log.warn", Types.STRING, "Writes a warning to the server log.");
        log(log, "log.warn", Types.nullable(Types.ANY), "Writes a value as a warning.");
        LOG_ERROR = log(log, "log.error", Types.STRING, "Writes an error to the server log.");
        log(log, "log.error", Types.nullable(Types.ANY), "Writes a value as an error.");
        LOG_DEBUG = log(log, "log.debug", Types.STRING, "Writes a debug message (only shown when debug is enabled).");
        log(log, "log.debug", Types.nullable(Types.ANY), "Writes a value as a debug message.");
        LOG = List.copyOf(log);
    }

    private static FunctionDeclaration log(List<FunctionDeclaration> into, String name, Type type, String summary) {
        FunctionDeclaration declaration = FunctionDeclaration.global(name).parameter("message", type).doc(summary).build();
        into.add(declaration);
        return declaration;
    }

    public static final List<PropertyDeclaration> PROPERTIES = List.of(PLAYERS, ONLINE_COUNT, MAX_PLAYERS, WORLDS);

    public static final List<FunctionDeclaration> FUNCTIONS = List.of(PLAYER_BY_NAME, WORLD_BY_NAME, BROADCAST);

    private ServerApi() {
    }
}
