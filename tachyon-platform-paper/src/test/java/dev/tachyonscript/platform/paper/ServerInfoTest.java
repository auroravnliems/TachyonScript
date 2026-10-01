package dev.tachyonscript.platform.paper;

import dev.tachyonscript.platform.paper.lib.ServerInfo;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Permission broadcasts must ask players, rather than use Bukkit permission subscriptions. */
class ServerInfoTest {

    private final List<Component> staffMessages = new ArrayList<>();
    private final List<Component> visitorMessages = new ArrayList<>();
    private final List<Component> consoleMessages = new ArrayList<>();
    private final List<BroadcastMessageEvent> events = new ArrayList<>();
    private Consumer<BroadcastMessageEvent> listener = event -> { };
    private boolean primaryThread = true;
    private Field serverField;
    private Object previousServer;
    private Player staff;
    private Player visitor;

    @BeforeEach
    void installServer() throws ReflectiveOperationException {
        staff = player(true, staffMessages);
        visitor = player(false, visitorMessages);
        ConsoleCommandSender console = BukkitFakes.fake(ConsoleCommandSender.class,
                Map.of("sendMessage/1", args -> {
                    consoleMessages.add((Component) args[0]);
                    return null;
                }));
        PluginManager plugins = BukkitFakes.fake(PluginManager.class, Map.of("callEvent", args -> {
            BroadcastMessageEvent event = (BroadcastMessageEvent) args[0];
            events.add(event);
            listener.accept(event);
            return null;
        }));
        Server server = BukkitFakes.fake(Server.class, Map.of(
                "getOnlinePlayers", args -> List.of(staff, visitor),
                "getConsoleSender", args -> console,
                "getPluginManager", args -> plugins,
                "isPrimaryThread", args -> primaryThread,
                // No sender subscribed to this unregistered permission: the old implementation
                // called this method and therefore reached nobody, despite hasPermission=true.
                "broadcast", args -> 0));
        serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        previousServer = serverField.get(null);
        serverField.set(null, server);
    }

    @AfterEach
    void restoreServer() throws IllegalAccessException {
        serverField.set(null, previousServer);
    }

    private static Player player(boolean permitted, List<Component> messages) {
        return BukkitFakes.fake(Player.class, Map.of(
                "hasPermission", args -> permitted && args[0].equals("staff.unregistered"),
                "sendMessage/1", args -> {
                    messages.add((Component) args[0]);
                    return null;
                }));
    }

    @Test
    void unregisteredPermissionReachesStaffAndConsoleButNotVisitors() {
        Component message = Component.text("Private staff alert");
        ServerInfo.broadcast(message, "staff.unregistered");
        assertEquals(List.of(message), staffMessages);
        assertEquals(List.of(message), consoleMessages);
        assertEquals(List.of(), visitorMessages);
        assertEquals(1, events.size());
        assertFalse(events.getFirst().isAsynchronous());
    }

    @Test
    void cancelledBroadcastReachesNobody() {
        listener = event -> event.setCancelled(true);
        ServerInfo.broadcast(Component.text("Cancelled"), "staff.unregistered");
        assertEquals(1, events.size());
        assertEquals(List.of(), staffMessages);
        assertEquals(List.of(), consoleMessages);
        assertEquals(List.of(), visitorMessages);
    }

    @Test
    void listenerCanChangeTheMessageAndRecipients() {
        Component replacement = Component.text("Edited by a chat bridge");
        listener = event -> {
            event.message(replacement);
            event.getRecipients().remove(staff);
            event.getRecipients().add(visitor);
        };
        ServerInfo.broadcast(Component.text("Original"), "staff.unregistered");
        assertEquals(List.of(), staffMessages);
        assertEquals(List.of(replacement), consoleMessages);
        assertEquals(List.of(replacement), visitorMessages);
    }

    @Test
    void broadcastOffThePrimaryThreadFiresAnAsynchronousEvent() {
        primaryThread = false;
        ServerInfo.broadcast(Component.text("Background alert"), "staff.unregistered");
        assertEquals(1, events.size());
        assertTrue(events.getFirst().isAsynchronous());
    }
}
