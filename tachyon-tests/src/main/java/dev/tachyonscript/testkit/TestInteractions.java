package dev.tachyonscript.testkit;

import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.natives.ScriptFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.stdlib.generated.DataApi;
import dev.tachyonscript.stdlib.generated.EntitiesApi;
import dev.tachyonscript.stdlib.generated.GeneratedTypes;
import dev.tachyonscript.stdlib.generated.ItemsApi;
import dev.tachyonscript.stdlib.generated.PlayersApi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Observable chest menus and memory-only files/HTTP. This class never opens a network connection. */
public final class TestInteractions {
    public static final class Item {
        private Fakes.Keyed type;
        private int amount;
        private Object name;
        private List<Object> lore;

        Item(Fakes.Keyed type, int amount, Object name, List<?> lore) {
            this.type = type;
            this.amount = amount;
            this.name = name;
            this.lore = new ArrayList<>(lore);
        }
    }

    public static final class Menu {
        private final ScriptEngine owner;
        private final Object title;
        private final Item[] slots;
        private final Map<Integer, ScriptFunction> handlers = new HashMap<>();
        private ScriptFunction onOpen;
        private ScriptFunction onClose;
        private boolean allowTaking;
        private boolean retired;

        Menu(ScriptEngine owner, int rows, Object title) {
            if (rows < 1 || rows > 6) {
                throw new ScriptError("Menu rows must be between 1 and 6.");
            }
            this.owner = owner;
            this.title = title;
            slots = new Item[rows * 9];
        }

        private void slot(int slot) {
            if (slot < 0 || slot >= slots.length) {
                throw new ScriptError("Menu slot is outside the inventory: " + slot);
            }
        }
    }

    private static final class Click {
        final Fakes.Player player;
        final Menu menu;
        final int slot;
        final boolean right;
        final boolean shift;
        boolean cancelled;

        Click(Fakes.Player player, Menu menu, int slot, boolean right, boolean shift) {
            this.player = player;
            this.menu = menu;
            this.slot = slot;
            this.right = right;
            this.shift = shift;
            cancelled = !menu.allowTaking;
        }
    }

    public record Request(String url, String body, String contentType) {
    }

    /** Items given to a player, without claiming inventory stacking or dropped-item physics. */
    public record GivenItem(String material, int amount, Object name) {
    }

    private final TestPlatform platform;
    private final Map<Fakes.Player, Menu> opened = new LinkedHashMap<>();
    private final Map<Fakes.LivingEntity, Item> hands = new HashMap<>();
    private final Map<Fakes.Player, List<GivenItem>> given = new LinkedHashMap<>();
    private final Map<String, String> files = new LinkedHashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private ScriptEngine engine;
    private boolean clicking;
    private int status = 204;
    private String response = "";

    TestInteractions(TestPlatform platform) {
        this.platform = platform;
    }

    void engine(ScriptEngine engine) {
        this.engine = engine;
    }

    public Menu opened(Fakes.Player player) {
        return opened.get(player);
    }

    public List<GivenItem> given(Fakes.Player player) {
        return List.copyOf(given.getOrDefault(player, List.of()));
    }

    private void give(Fakes.Player player, Item item) {
        given.computeIfAbsent(player, ignored -> new ArrayList<>())
                .add(new GivenItem(item.type.text(), item.amount, item.name));
    }

    public String describe(Fakes.Player player) {
        Menu menu = opened.get(player);
        if (menu == null) {
            return "closed";
        }
        List<String> items = new ArrayList<>();
        for (int i = 0; i < menu.slots.length; i++) {
            Item item = menu.slots[i];
            if (item != null) {
                items.add(i + ":" + item.type.text() + ":" + item.name);
            }
        }
        return menu.title + " [" + String.join(", ", items) + "]";
    }

    public void hand(Fakes.Player player, String material) {
        if (material.equals("empty")) {
            hands.remove(player);
        } else {
            hands.put(player, new Item(new Fakes.Keyed("Material", "minecraft:" + material), 1, null, List.of()));
        }
    }

    public boolean click(Fakes.Player player, int slot, boolean right, boolean shift) {
        Menu menu = opened.get(player);
        if (menu == null || menu.retired || !player.valid()) {
            return false;
        }
        menu.slot(slot);
        Click click = new Click(player, menu, slot, right, shift);
        ScriptFunction handler = menu.handlers.get(slot);
        if (handler != null) {
            clicking = true;
            try {
                menu.owner.callback(handler, click);
            } finally {
                clicking = false;
            }
        }
        return click.cancelled;
    }

    public void close(Fakes.Player player) {
        defer(() -> closeNow(player));
    }

    private void closeNow(Fakes.Player player) {
        Menu old = opened.remove(player);
        if (old != null && old.onClose != null) {
            old.owner.callback(old.onClose, player);
        }
    }

    private void open(Menu menu, Fakes.Player player) {
        defer(() -> {
            if (menu.retired || !player.valid()) {
                return;
            }
            closeNow(player);
            opened.put(player, menu);
            player.show("menu", menu.title, null);
            if (menu.onOpen != null) {
                menu.owner.callback(menu.onOpen, player);
            }
        });
    }

    private void defer(Runnable action) {
        if (clicking) {
            platform.scheduler().runLater(1, action);
        } else {
            action.run();
        }
    }

    private List<Object> viewers(Menu menu) {
        return new ArrayList<>(opened.entrySet().stream().filter(e -> e.getValue() == menu)
                .map(Map.Entry::getKey).toList());
    }

    private void close(Menu menu) {
        for (Object player : viewers(menu)) {
            close((Fakes.Player) player);
        }
    }

    public void file(String path, String content) {
        files.put(path(path), content);
    }

    private static String path(String path) {
        String clean = path.strip().replace('\\', '/');
        if (clean.isEmpty() || clean.startsWith("/") || clean.contains(":")) {
            throw new ScriptError("Invalid file path '" + path + "'.");
        }
        String normalized = Path.of(clean).normalize().toString().replace('\\', '/');
        if (normalized.equals("..") || normalized.startsWith("../")) {
            throw new ScriptError("The file path '" + path + "' leaves the files folder.");
        }
        return normalized;
    }

    public void http(int status, String response) {
        this.status = status;
        this.response = response;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    private void request(String url, String body, String type, ScriptFunction callback) {
        requests.add(new Request(url, body, type));
        int capturedStatus = status;
        String capturedResponse = response;
        ScriptEngine owner = engine;
        platform.scheduler().runLater(1, () -> owner.callback(callback, capturedStatus, capturedResponse));
    }

    Bindings bindings() {
        Bindings.Builder b = Bindings.builder();
        b.bindType(GeneratedTypes.ITEM_STACK, Item.class);
        b.bindType(GeneratedTypes.MENU, Menu.class);
        b.bindType(GeneratedTypes.INVENTORY, Menu.class);
        b.bindType(GeneratedTypes.MENU_CLICK, Click.class);
        for (var constructor : List.of(ItemsApi.ITEM_STACK, ItemsApi.ITEM_STACK_2,
                ItemsApi.ITEM_STACK_3, ItemsApi.ITEM_STACK_4)) {
            b.bind(constructor, (NativeFunction.OfRef) a -> new Item((Fakes.Keyed) a.getRef(0),
                    a.count() > 1 ? a.getInt(1) : 1, a.count() > 2 ? a.getRef(2) : null,
                    a.count() > 3 ? (List<?>) a.getRef(3) : List.of()));
        }
        b.bindGetter(ItemsApi.ITEM_STACK_TYPE, (NativeFunction.OfRef) a -> ((Item) a.getRef(0)).type);
        b.bindSetter(ItemsApi.ITEM_STACK_TYPE, a -> ((Item) a.getRef(0)).type = (Fakes.Keyed) a.getRef(1));
        b.bindGetter(ItemsApi.ITEM_STACK_AMOUNT, (NativeFunction.OfInt) a -> ((Item) a.getRef(0)).amount);
        b.bindSetter(ItemsApi.ITEM_STACK_AMOUNT, a -> ((Item) a.getRef(0)).amount = a.getInt(1));
        b.bindGetter(ItemsApi.ITEM_STACK_NAME, (NativeFunction.OfRef) a -> ((Item) a.getRef(0)).name);
        b.bindSetter(ItemsApi.ITEM_STACK_NAME, a -> ((Item) a.getRef(0)).name = a.getRef(1));
        b.bindGetter(ItemsApi.ITEM_STACK_DISPLAY_NAME, (NativeFunction.OfRef) a -> {
            Item item = (Item) a.getRef(0);
            return item.name == null ? item.type.text() : item.name;
        });
        b.bindGetter(ItemsApi.ITEM_STACK_LORE, (NativeFunction.OfRef) a -> ((Item) a.getRef(0)).lore);
        b.bindSetter(ItemsApi.ITEM_STACK_LORE, a -> ((Item) a.getRef(0)).lore = new ArrayList<>((List<?>) a.getRef(1)));
        b.bindGetter(EntitiesApi.LIVING_ENTITY_MAIN_HAND, (NativeFunction.OfRef) a -> hands.get(a.getRef(0)));
        b.bindSetter(EntitiesApi.LIVING_ENTITY_MAIN_HAND, a -> hands.put((Fakes.LivingEntity) a.getRef(0), (Item) a.getRef(1)));
        b.bind(PlayersApi.PLAYER_GIVE, (NativeFunction.OfVoid) a ->
                give((Fakes.Player) a.getRef(0), (Item) a.getRef(1)));
        b.bind(PlayersApi.PLAYER_GIVE_2, (NativeFunction.OfVoid) a ->
                give((Fakes.Player) a.getRef(0), new Item((Fakes.Keyed) a.getRef(1), a.getInt(2), null, List.of())));
        b.bind(ItemsApi.MENU, (NativeFunction.OfRef) a -> {
            Menu menu = new Menu(engine, a.getInt(0), a.getRef(1));
            ScriptEngine.ownResource(menu, resource -> {
                Menu retired = (Menu) resource;
                retired.retired = true;
                close(retired);
            });
            return menu;
        });
        b.bind(ItemsApi.MENU_SLOT, (NativeFunction.OfInt) a -> a.getInt(0) * 9 + a.getInt(1));
        b.bindGetter(ItemsApi.MENU_TITLE, (NativeFunction.OfRef) a -> ((Menu) a.getRef(0)).title);
        b.bindGetter(ItemsApi.MENU_SIZE, (NativeFunction.OfInt) a -> ((Menu) a.getRef(0)).slots.length);
        b.bindGetter(ItemsApi.MENU_ROWS, (NativeFunction.OfInt) a -> ((Menu) a.getRef(0)).slots.length / 9);
        b.bindGetter(ItemsApi.MENU_INVENTORY, (NativeFunction.OfRef) a -> a.getRef(0));
        b.bindGetter(ItemsApi.MENU_VIEWERS, (NativeFunction.OfRef) a -> viewers((Menu) a.getRef(0)));
        b.bindGetter(ItemsApi.MENU_ALLOW_TAKING, (NativeFunction.OfBool) a -> ((Menu) a.getRef(0)).allowTaking);
        b.bindSetter(ItemsApi.MENU_ALLOW_TAKING, a -> ((Menu) a.getRef(0)).allowTaking = a.getBool(1));
        for (var set : List.of(ItemsApi.MENU_SET, ItemsApi.MENU_SET_2, ItemsApi.INVENTORY_SET)) {
            b.bind(set, (NativeFunction.OfVoid) a -> {
                Menu menu = (Menu) a.getRef(0);
                int slot = a.getInt(1);
                menu.slot(slot);
                menu.slots[slot] = (Item) a.getRef(2);
                if (set != ItemsApi.INVENTORY_SET) {
                    menu.handlers.remove(slot);
                    if (a.count() > 3) {
                        menu.handlers.put(slot, (ScriptFunction) a.getRef(3));
                    }
                }
            });
        }
        for (var get : List.of(ItemsApi.MENU_GET, ItemsApi.INVENTORY_GET)) {
            b.bind(get, (NativeFunction.OfRef) a -> {
                Menu menu = (Menu) a.getRef(0);
                menu.slot(a.getInt(1));
                return menu.slots[a.getInt(1)];
            });
        }
        b.bind(ItemsApi.MENU_ON_CLICK, (NativeFunction.OfVoid) a -> {
            Menu menu = (Menu) a.getRef(0);
            menu.slot(a.getInt(1));
            menu.handlers.put(a.getInt(1), (ScriptFunction) a.getRef(2));
        });
        for (var fill : List.of(ItemsApi.MENU_FILL, ItemsApi.MENU_FILL_BORDER)) {
            b.bind(fill, (NativeFunction.OfVoid) a -> {
                Menu menu = (Menu) a.getRef(0);
                for (int i = 0; i < menu.slots.length; i++) {
                    boolean border = i < 9 || i >= menu.slots.length - 9 || i % 9 == 0 || i % 9 == 8;
                    if (menu.slots[i] == null && (fill == ItemsApi.MENU_FILL || border)) {
                        menu.slots[i] = (Item) a.getRef(1);
                    }
                }
            });
        }
        b.bind(ItemsApi.MENU_CLEAR, (NativeFunction.OfVoid) a -> {
            Menu menu = (Menu) a.getRef(0);
            Arrays.fill(menu.slots, null);
            menu.handlers.clear();
        });
        b.bind(ItemsApi.MENU_ON_OPEN, (NativeFunction.OfVoid) a -> ((Menu) a.getRef(0)).onOpen = (ScriptFunction) a.getRef(1));
        b.bind(ItemsApi.MENU_ON_CLOSE, (NativeFunction.OfVoid) a -> ((Menu) a.getRef(0)).onClose = (ScriptFunction) a.getRef(1));
        b.bind(ItemsApi.MENU_OPEN, (NativeFunction.OfVoid) a -> open((Menu) a.getRef(0), (Fakes.Player) a.getRef(1)));
        b.bind(ItemsApi.MENU_CLOSE, (NativeFunction.OfVoid) a -> close((Menu) a.getRef(0)));
        b.bind(PlayersApi.PLAYER_CLOSE_INVENTORY, (NativeFunction.OfVoid) a -> close((Fakes.Player) a.getRef(0)));
        b.bind(PlayersApi.PLAYER_PERFORM_COMMAND, (NativeFunction.OfBool) a -> {
            platform.command(a.getRef(0), a.getString(1));
            return true;
        });
        b.bindGetter(ItemsApi.MENU_CLICK_PLAYER, (NativeFunction.OfRef) a -> ((Click) a.getRef(0)).player);
        b.bindGetter(ItemsApi.MENU_CLICK_MENU, (NativeFunction.OfRef) a -> ((Click) a.getRef(0)).menu);
        b.bindGetter(ItemsApi.MENU_CLICK_SLOT, (NativeFunction.OfInt) a -> ((Click) a.getRef(0)).slot);
        b.bindGetter(ItemsApi.MENU_CLICK_ITEM, (NativeFunction.OfRef) a -> {
            Click click = (Click) a.getRef(0);
            return click.menu.slots[click.slot];
        });
        b.bindGetter(ItemsApi.MENU_CLICK_RIGHT, (NativeFunction.OfBool) a -> ((Click) a.getRef(0)).right);
        b.bindGetter(ItemsApi.MENU_CLICK_LEFT, (NativeFunction.OfBool) a -> !((Click) a.getRef(0)).right);
        b.bindGetter(ItemsApi.MENU_CLICK_SHIFT, (NativeFunction.OfBool) a -> ((Click) a.getRef(0)).shift);
        b.bindGetter(ItemsApi.MENU_CLICK_CANCELLED, (NativeFunction.OfBool) a -> ((Click) a.getRef(0)).cancelled);
        b.bindSetter(ItemsApi.MENU_CLICK_CANCELLED, a -> ((Click) a.getRef(0)).cancelled = a.getBool(1));
        b.bind(ItemsApi.MENU_CLICK_CLOSE, (NativeFunction.OfVoid) a -> close(((Click) a.getRef(0)).player));
        b.bind(DataApi.FILES_READ, (NativeFunction.OfRef) a -> files.get(path(a.getString(0))));
        b.bind(DataApi.FILES_WRITE, (NativeFunction.OfVoid) a -> file(a.getString(0), a.getString(1)));
        b.bind(DataApi.FILES_APPEND, (NativeFunction.OfVoid) a -> files.merge(path(a.getString(0)), a.getString(1), String::concat));
        b.bind(DataApi.FILES_EXISTS, (NativeFunction.OfBool) a -> files.containsKey(path(a.getString(0))));
        b.bind(DataApi.FILES_DELETE, (NativeFunction.OfBool) a -> files.remove(path(a.getString(0))) != null);
        b.bind(DataApi.FILES_LINES, (NativeFunction.OfRef) a ->
                new ArrayList<>(files.getOrDefault(path(a.getString(0)), "").lines().toList()));
        b.bind(DataApi.WEB_GET, (NativeFunction.OfVoid) a -> request(a.getString(0), "", "", (ScriptFunction) a.getRef(1)));
        b.bind(DataApi.WEB_POST, (NativeFunction.OfVoid) a -> request(a.getString(0), a.getString(1), a.getString(2),
                (ScriptFunction) a.getRef(3)));
        return b.build();
    }
}
