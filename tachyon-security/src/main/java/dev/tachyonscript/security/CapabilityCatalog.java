package dev.tachyonscript.security;

import dev.tachyonscript.api.declaration.Effect;
import dev.tachyonscript.api.declaration.NativeDeclaration;

import java.util.Set;

/** Classification is based on the bound declaration, never an unresolved source name. */
public final class CapabilityCatalog {
    private static final Set<String> CONSOLE = Set.of("server.dispatch", "CommandSender.dispatch", "server.consoleCommand");
    private static final Set<String> PLAYER = Set.of("Player.performCommand", "Player.chat");
    private static final Set<String> PRIVILEGE = Set.of("Player.addPermission", "Player.removePermission",
            "OfflinePlayer.op:set", "Player.op:set", "CommandSender.op:set");
    private static final Set<String> READ = Set.of("files.read", "files.lines", "files.exists", "files.list");
    private static final Set<String> QUERY = Set.of("Database.execute", "Database.update", "Database.query",
            "Database.queryOne", "Database.scalar");
    private static final Set<String> SCHEDULING = Set.of("$after", "$every", "$async", "$sync");

    private CapabilityCatalog() { }

    public static Capability resolve(NativeDeclaration declaration, SecurityOptions options) {
        Capability override = options.additionalCapabilities().get(declaration.key());
        if (override != null) return override;
        String name = name(declaration.key());
        if (CONSOLE.contains(name)) return Capability.CONSOLE_COMMAND;
        if (PLAYER.contains(name)) return Capability.PLAYER_COMMAND;
        if (PRIVILEGE.contains(name)) return Capability.PRIVILEGE;
        if (name.equals("server.shutdown")) return Capability.SERVER_CONTROL;
        if (name.equals("web.get") || name.equals("web.post")) return Capability.NETWORK;
        if (READ.contains(name)) return Capability.FILE_READ;
        if (name.equals("files.write") || name.equals("files.append")) return Capability.FILE_WRITE;
        if (name.equals("files.delete")) return Capability.FILE_DELETE;
        if (QUERY.contains(name)) return Capability.DATABASE_QUERY;
        if (name.equals("Database") || name.equals("Database.sqlite")) return Capability.DATABASE_OPEN;
        if (name.startsWith("$sched.") || SCHEDULING.contains(name)) return Capability.SCHEDULE;
        if (name.endsWith(".hasPermission")) return Capability.PERMISSION_CHECK;
        if (name.endsWith("Event.cancelled:set") || name.endsWith("Event.cancel") || name.endsWith("Event.uncancel")) return Capability.EVENT_CANCEL;
        if (name.startsWith("DatabaseRow.") || name.startsWith("Row.") || name.endsWith(".tag") || name.equals("papi.parse"))
            return Capability.EXTERNAL_DATA;
        if (declaration.isPure()) return Capability.PURE;
        if (declaration.effects().contains(Effect.IO)) return Capability.IO;
        if (declaration.effects().contains(Effect.MODIFIES_WORLD)) return Capability.WORLD_WRITE;
        if (declaration.effects().contains(Effect.READS_WORLD)) return Capability.WORLD_READ;
        return Capability.UNKNOWN;
    }

    public static String name(String key) {
        int at = key.indexOf('(');
        return at < 0 ? key : key.substring(0, at);
    }
}
