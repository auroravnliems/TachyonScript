package dev.tachyonscript.platform.paper;

import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.compiler.InternalErrorHandler;
import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.spi.ArgumentTypes;
import dev.tachyonscript.engine.spi.CommandRegistry;
import dev.tachyonscript.engine.spi.EngineLogger;
import dev.tachyonscript.engine.spi.EventBridge;
import dev.tachyonscript.engine.spi.Platform;
import dev.tachyonscript.engine.spi.PlayerDirectory;
import dev.tachyonscript.engine.spi.Scheduler;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.platform.paper.lib.MenuListener;
import dev.tachyonscript.platform.paper.lib.ScriptMenu;
import dev.tachyonscript.runtime.spi.TextService;
import dev.tachyonscript.stdlib.StandardLibrary;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Real Bukkit events, registered bridge executors, MenuListener, compiler and runtime;
 * only the server, inventories and player are faked. Vanilla movement is checked by the
 * cancellation contract here and by the separate live Paper probe. */
@ResourceLock("Bukkit.server")
class MenuEventIsolationTest {
    private static final SymbolRegistry REGISTRY = StandardLibrary.registry();
    private final List<String> logs = new ArrayList<>();
    private final List<ScriptMenu> menus = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Runnable> queued = new ArrayList<>();
    private final Logger logger = Logger.getLogger("MenuEventIsolationTest");
    private final ItemStack icon = new TestItem(Material.DIAMOND);
    private Field serverField;
    private Object previousServer;
    private ScriptEngine engine;
    private PaperEventBridge bridge;
    private Player player;
    private InventoryView view;
    private ItemStack cursor;
    private boolean screenClosed;

    /** Item identity without a CraftBukkit item factory. */
    private static final class TestItem extends ItemStack {
        private final Material material;
        TestItem(Material material) { this.material = material; }
        @Override public Material getType() { return material; }
        @Override public int getAmount() { return 1; }
        @Override public boolean isEmpty() { return false; }
        @Override public ItemStack clone() { return new TestItem(material); }
    }

    @BeforeEach
    void setUp() throws Exception {
        logger.setUseParentHandlers(false);
        for (var handler : logger.getHandlers()) logger.removeHandler(handler);
        logger.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { logs.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        Plugin plugin = BukkitFakes.fake(Plugin.class, Map.of(
                "getName", a -> "TachyonScript", "getDataFolder", a -> new java.io.File("build/tmp/menu-tests"),
                "getLogger", a -> logger, "isEnabled", a -> true));
        PluginManager manager = BukkitFakes.fake(PluginManager.class, Map.of("registerEvent", a -> {
            register((Class<?>) a[0], (Listener) a[1], (EventPriority) a[2], (EventExecutor) a[3], (Plugin) a[4], (Boolean) a[5]);
            return null;
        }));
        Server server = BukkitFakes.fake(Server.class, Map.of(
                "getPluginManager", a -> manager, "getLogger", a -> logger,
                "createInventory", a -> {
                    ScriptMenu menu = (ScriptMenu) a[0];
                    InventoryType type = a[1] instanceof InventoryType t ? t : InventoryType.CHEST;
                    int size = a[1] instanceof Integer slots ? slots : type.getDefaultSize();
                    menus.add(menu);
                    return inventory(menu, type, size);
                }));
        serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        previousServer = serverField.get(null);
        serverField.set(null, server);
        var scheduler = BukkitFakes.fake(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class,
                Map.of("run", a -> { queued.add(() -> {
                    @SuppressWarnings("unchecked")
                    var action = (java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>) a[1];
                    action.accept(null);
                }); return null; }));
        player = BukkitFakes.fake(Player.class, Map.of(
                "getName", a -> "Tester", "getUniqueId", a -> new UUID(0, 1),
                "getScheduler", a -> scheduler, "getOpenInventory", a -> view,
                "closeInventory", a -> { screenClosed = true; return null; }));
        PaperContext context = new PaperContext(plugin, new BukkitFakes.InlineThreading(false));
        Bindings bindings = PaperPlatform.builtInBindings(context, context.threads(), logger, () -> false);
        bridge = new PaperEventBridge(plugin, REGISTRY, PaperEvents.all(Map.of()));
        EngineLogger log = new EngineLogger() {
            @Override public void info(String text) { logs.add(text); }
            @Override public void warn(String text) { logs.add("WARN " + text); }
            @Override public void error(String text) { logs.add("ERROR " + text); }
        };
        Platform platform = new Platform() {
            @Override public Bindings bindings() { return bindings; }
            @Override public TextService text() { return new AdventureTextService(); }
            @Override public EventBridge events() { return bridge; }
            @Override public EngineLogger logger() { return log; }
            @Override public Scheduler scheduler() { return InlineScheduler.INSTANCE; }
            @Override public CommandRegistry commands() { return InlineScheduler.NO_COMMANDS; }
            @Override public ArgumentTypes arguments() { return new PaperArgumentTypes(REGISTRY, bindings); }
            @Override public PlayerDirectory players() { return new PlayerDirectory() {
                @Override public UUID id(Object value) { return ((Player) value).getUniqueId(); }
                @Override public String name(Object value) { return ((Player) value).getName(); }
            }; }
        };
        engine = new ScriptEngine(REGISTRY, platform, EngineOptions.DEFAULT, InternalErrorHandler.IGNORE);
        context.attach(engine);
        bridge.attach(engine);
        // Register the real menu annotations exactly as Paper does.
        MenuListener menuListener = new MenuListener();
        for (var method : MenuListener.class.getDeclaredMethods()) {
            EventHandler annotation = method.getAnnotation(EventHandler.class);
            if (annotation == null) continue;
            register(method.getParameterTypes()[0], menuListener, annotation.priority(), (ignored, fired) -> {
                try { method.invoke(menuListener, fired); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            }, plugin, annotation.ignoreCancelled());
        }
    }

    private void register(Class<?> type, Listener listener, EventPriority priority, EventExecutor executor, Plugin plugin, boolean ignore) {
        try {
            HandlerList handlers = (HandlerList) type.getMethod("getHandlerList").invoke(null);
            handlers.register(new RegisteredListener(listener, executor, priority, plugin, ignore));
            listeners.add(listener);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (engine != null) engine.shutdown();
            if (bridge != null) bridge.unregisterAll();
            listeners.forEach(HandlerList::unregisterAll);
        } finally { if (serverField != null) serverField.set(null, previousServer); }
    }

    private Inventory inventory(InventoryHolder holder, InventoryType type, int size) {
        ItemStack[] contents = new ItemStack[size];
        List<HumanEntity> viewers = new ArrayList<>();
        Map<String, BukkitFakes.Answer> answers = new HashMap<>();
        answers.put("getHolder", a -> holder);
        answers.put("getType", a -> type);
        answers.put("getSize", a -> size);
        answers.put("getViewers", a -> viewers);
        answers.put("getItem", a -> contents[(Integer) a[0]]);
        answers.put("setItem", a -> { contents[(Integer) a[0]] = (ItemStack) a[1]; return null; });
        answers.put("clear", a -> { java.util.Arrays.fill(contents, null); return null; });
        return BukkitFakes.fake(Inventory.class, answers);
    }

    private void open(Inventory top) {
        Inventory bottom = inventory(null, InventoryType.PLAYER, 36);
        top.getViewers().add(player);
        Map<String, BukkitFakes.Answer> answers = new HashMap<>();
        answers.put("getTopInventory", a -> top);
        answers.put("getBottomInventory", a -> bottom);
        answers.put("getPlayer", a -> player);
        answers.put("getType", a -> top.getType());
        answers.put("getTitle", a -> "Same title for owned and external inventories");
        answers.put("convertSlot", a -> (Integer) a[0] < top.getSize() ? a[0] : (Integer) a[0] - top.getSize());
        answers.put("getCursor", a -> cursor);
        answers.put("setCursor", a -> { cursor = (ItemStack) a[0]; return null; });
        answers.put("getInventory", a -> (Integer) a[0] < 0 ? null : (Integer) a[0] < top.getSize() ? top : bottom);
        answers.put("getItem", a -> { int slot = (Integer) a[0]; return slot < 0 ? null : slot < top.getSize() ? top.getItem(slot) : bottom.getItem(slot - top.getSize()); });
        answers.put("setItem", a -> { int slot = (Integer) a[0]; (slot < top.getSize() ? top : bottom).setItem(slot < top.getSize() ? slot : slot - top.getSize(), (ItemStack) a[1]); return null; });
        view = BukkitFakes.fake(InventoryView.class, answers);
    }

    private void load(String source) {
        var report = engine.load(() -> List.of(new SourceFile("menu-test.tys", source)));
        assertTrue(report.activated() && report.failed().isEmpty(), () -> report.toString());
        assertTrue(engine.errors().sites().isEmpty(), () -> logs.toString());
    }

    private static String menu(boolean taking, String callback) {
        return "let menu = Menu(1, \"Test\")\non load { menu.allowTaking = " + taking
                + "\nmenu.set(0, null, click => { " + callback + " }) }\n";
    }

    private void fire(Event event) throws Exception {
        for (RegisteredListener listener : event.getHandlers().getRegisteredListeners()) listener.callEvent(event);
        assertTrue(engine.errors().sites().isEmpty(), () -> logs.toString());
    }

    private InventoryClickEvent click(ClickType type, int slot) {
        InventoryAction action = switch (type) {
            case SHIFT_LEFT, SHIFT_RIGHT -> InventoryAction.MOVE_TO_OTHER_INVENTORY;
            case NUMBER_KEY, SWAP_OFFHAND -> InventoryAction.HOTBAR_SWAP;
            case DOUBLE_CLICK -> InventoryAction.COLLECT_TO_CURSOR;
            case DROP -> InventoryAction.DROP_ONE_SLOT;
            case CONTROL_DROP -> InventoryAction.DROP_ALL_SLOT;
            default -> InventoryAction.PICKUP_ALL;
        };
        return new InventoryClickEvent(view, slot < 0 ? InventoryType.SlotType.OUTSIDE : InventoryType.SlotType.CONTAINER,
                slot, type, action, type == ClickType.NUMBER_KEY ? 2 : -1);
    }

    @ParameterizedTest @EnumSource(EventPriority.class)
    void scriptMenusNeverReachGenericClickOrDragAtAnyPriority(EventPriority priority) throws Exception {
        load(menu(false, "log(\"callback\")") + "@priority(" + priority + ")\nevent player.inventoryClick {\n"
                + "log(\"generic click\")\nevent.cancel()\nif slot >= 0 && item != null { event.cancelled = false }\n}\n"
                + "@priority(" + priority + ")\nevent player.inventoryDrag { log(\"generic drag\"); event.uncancel() }\n");
        ScriptMenu menu = menus.getLast();
        open(menu.inventory());
        menu.inventory().setItem(0, icon);
        for (ClickType type : List.of(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT,
                ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK, ClickType.DROP, ClickType.CONTROL_DROP, ClickType.SWAP_OFFHAND)) {
            long before = logs.stream().filter("callback"::equals).count();
            InventoryClickEvent event = click(type, 0);
            fire(event);
            assertTrue(event.isCancelled(), type.toString());
            assertEquals(before + 1, logs.stream().filter("callback"::equals).count());
            assertSame(icon, menu.inventory().getItem(0));
            assertNull(cursor);
        }
        InventoryDragEvent drag = new InventoryDragEvent(view, null, icon, false, Map.of(0, icon, 10, icon));
        fire(drag);
        assertTrue(drag.isCancelled());
        assertFalse(logs.stream().anyMatch(line -> line.startsWith("generic")), logs::toString);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void takingPolicyAndBottomInventoryTransfersArePreserved(boolean taking) throws Exception {
        load(menu(taking, "log(\"callback\")") + "event player.inventoryClick { log(\"generic\"); event.cancelled = false }\n"
                + "event player.inventoryDrag { log(\"generic\"); event.cancelled = false }\n");
        ScriptMenu menu = menus.getLast(); open(menu.inventory());
        menu.inventory().setItem(0, icon);
        for (ClickType type : List.of(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT,
                ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK, ClickType.DROP, ClickType.CONTROL_DROP, ClickType.SWAP_OFFHAND)) {
            InventoryClickEvent event = click(type, 0); fire(event);
            assertEquals(!taking, event.isCancelled(), type.toString());
        }
        for (ClickType type : List.of(ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT, ClickType.DOUBLE_CLICK)) {
            InventoryClickEvent event = click(type, 10); fire(event);
            assertEquals(!taking, event.isCancelled(), "bottom " + type);
        }
        InventoryClickEvent bottom = click(ClickType.LEFT, 10); fire(bottom); assertFalse(bottom.isCancelled());
        InventoryClickEvent outside = click(ClickType.LEFT, -999); fire(outside); assertFalse(outside.isCancelled());
        for (boolean right : List.of(false, true)) {
            InventoryDragEvent top = new InventoryDragEvent(view, null, icon, right, Map.of(0, icon)); fire(top);
            assertEquals(!taking, top.isCancelled());
            InventoryDragEvent bottomDrag = new InventoryDragEvent(view, null, icon, right, Map.of(10, icon)); fire(bottomDrag);
            assertFalse(bottomDrag.isCancelled());
        }
        assertFalse(logs.contains("generic"));
    }

    @Test
    void menuCallbackCanExplicitlyAllowOneClick() throws Exception {
        load(menu(false, "click.cancelled = false; log(\"callback\")"));
        open(menus.getLast().inventory());
        InventoryClickEvent event = click(ClickType.NUMBER_KEY, 0); fire(event);
        assertFalse(event.isCancelled());
        assertEquals(List.of("callback"), logs);
    }

    @ParameterizedTest @EnumSource(value = InventoryType.class, names = {"PLAYER", "CHEST", "FURNACE", "HOPPER"})
    void ordinaryAndExternalInventoriesKeepBindingsAndUncancelSemantics(InventoryType type) throws Exception {
        load("event player.inventoryClick {\n"
                + "log(\"{player.name}:{slot}:{rawSlot}:{click}:{item != null}:{cursor != null}:{inventory?.size}:{topInventory.size}\")\n"
                + "event.cancel()\nevent.uncancel()\nevent.cancelled = true\nevent.cancelled = false\n}\n"
                + "event player.inventoryDrag { log(\"drag\"); event.uncancel() }\n");
        // External holder with the same title as ScriptMenu must never be filtered.
        InventoryHolder external = BukkitFakes.fake(InventoryHolder.class, Map.of());
        Inventory top = inventory(external, type, 9); top.setItem(0, icon); open(top); cursor = icon;
        InventoryClickEvent event = click(ClickType.LEFT, 0); event.setCancelled(true); fire(event);
        assertFalse(event.isCancelled());
        assertEquals(List.of("Tester:0:0:left:true:true:9:9"), logs);
        InventoryDragEvent drag = new InventoryDragEvent(view, null, icon, false, Map.of(0, icon));
        drag.setCancelled(true); fire(drag); assertFalse(drag.isCancelled());
        assertEquals("drag", logs.getLast());
        open(inventory(null, type, 9));
        InventoryClickEvent noHolder = click(ClickType.RIGHT, 0); fire(noHolder);
        assertFalse(noHolder.isCancelled());
    }

    @Test
    void prioritiesAndIgnoreCancelledStillApplyOutsideScriptMenus() throws Exception {
        load("@priority(LOWEST)\nevent player.inventoryClick { log(\"lowest\"); event.cancel() }\n"
                + "@priority(LOW)\n@ignoreCancelled\nevent player.inventoryClick { log(\"skipped\") }\n"
                + "@priority(NORMAL)\nevent player.inventoryClick { log(\"normal\"); event.uncancel() }\n"
                + "@priority(HIGH)\n@ignoreCancelled\nevent player.inventoryClick { log(\"high\") }\n"
                + "@priority(HIGHEST)\nevent player.inventoryClick { log(\"highest\") }\n"
                + "@priority(MONITOR)\nevent player.inventoryClick { log(\"monitor {event.cancelled}\") }\n");
        open(inventory(null, InventoryType.CHEST, 9)); fire(click(ClickType.LEFT, 0));
        assertEquals(List.of("lowest", "normal", "high", "highest", "monitor false"), logs);
    }

    @Test
    void reloadingClosesOldMenuAndNeverRunsItsRetiredCallback() throws Exception {
        load(menu(false, "log(\"old callback\")"));
        ScriptMenu old = menus.getLast(); open(old.inventory());
        fire(click(ClickType.LEFT, 0)); assertEquals(List.of("old callback"), logs);
        load(menu(false, "log(\"new callback\")") + "event player.inventoryClick { log(\"generic\"); event.uncancel() }\n");
        assertTrue(screenClosed);
        InventoryClickEvent stale = click(ClickType.LEFT, 0); fire(stale); assertTrue(stale.isCancelled());
        assertEquals(List.of("old callback"), logs);
        open(menus.getLast().inventory()); fire(click(ClickType.LEFT, 0));
        assertEquals(List.of("old callback", "new callback"), logs);
    }
}
