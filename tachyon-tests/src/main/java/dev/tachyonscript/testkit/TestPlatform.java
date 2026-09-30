package dev.tachyonscript.testkit;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.storage.KeyedValues;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.value.Values;
import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.engine.spi.ArgumentTypes;
import dev.tachyonscript.engine.spi.CommandRegistry;
import dev.tachyonscript.engine.spi.EngineLogger;
import dev.tachyonscript.engine.spi.EventBridge;
import dev.tachyonscript.engine.spi.Platform;
import dev.tachyonscript.engine.spi.PlayerDirectory;
import dev.tachyonscript.compiler.InternalErrorHandler;
import dev.tachyonscript.runtime.spi.SimpleTextService;
import dev.tachyonscript.runtime.spi.TextService;
import dev.tachyonscript.stdlib.EntityApi;
import dev.tachyonscript.stdlib.EventApi;
import dev.tachyonscript.stdlib.MinecraftTypes;
import dev.tachyonscript.stdlib.ServerApi;
import dev.tachyonscript.stdlib.StandardLibrary;
import dev.tachyonscript.stdlib.TextApi;
import dev.tachyonscript.stdlib.WorldApi;
import dev.tachyonscript.stdlib.generated.EntitiesApi;
import dev.tachyonscript.stdlib.generated.WorldsApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A complete in-memory platform: every standard declaration is bound to the {@link Fakes},
 * events are fired by calling {@link #fire}, and messages, logs and active listeners are
 * recorded for assertions. Components are MiniMessage strings ({@link SimpleTextService}).
 */
public final class TestPlatform implements Platform {

    private final Fakes.World world = new Fakes.World("world");
    private final List<Fakes.Player> players = new ArrayList<>();
    /** Every player that ever joined (for offline player lookups). */
    private final List<Fakes.Player> known = new ArrayList<>();
    private final Fakes.Console console = new Fakes.Console();
    private final List<Object> broadcasts = new CopyOnWriteArrayList<>();
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private volatile Set<EventDeclaration> activeEvents = Set.of();
    private volatile Map<EventDeclaration, Set<Integer>> activePriorities = Map.of();
    private final Map<String, CommandRegistry.Command> commands = new LinkedHashMap<>();
    private final TestScheduler scheduler = new TestScheduler();
    private final Bindings bindings;
    private final SymbolRegistry registry = StandardLibrary.registry();
    private final EngineLogger logger = new EngineLogger() {
        @Override
        public void info(String message) {
            logs.add("INFO " + message);
        }

        @Override
        public void warn(String message) {
            logs.add("WARN " + message);
        }

        @Override
        public void error(String message) {
            logs.add("ERROR " + message);
        }
    };

    /** Keys of the declarations bound to a stub that fails when called. */
    private final Set<String> stubbed = new java.util.TreeSet<>();

    public TestPlatform() {
        Bindings.Builder all = Bindings.builder().include(StandardLibrary.coreBindings()).include(platformBindings());
        // Everything without a fake still links: calling it fails with a clear message. The engine
        // implements databases itself, so those are left to it.
        Set<String> engine = new java.util.HashSet<>();
        StandardLibrary.engineDeclarations().forEach(declaration -> engine.add(declaration.key()));
        for (NativeDeclaration declaration : registry.natives()) {
            if (!declaration.isIntrinsic() && !all.isBound(declaration) && !engine.contains(declaration.key())) {
                all.bind(declaration, stub(declaration));
                stubbed.add(declaration.key());
            }
        }
        this.bindings = all.build();
    }

    /** Keys of the declarations this platform does not fake (calling them is an error). */
    public Set<String> stubbed() {
        return Collections.unmodifiableSet(stubbed);
    }

    private static NativeFunction stub(NativeDeclaration declaration) {
        String message = declaration.key() + " is not available in the test platform.";
        return switch (declaration.returnRepresentation()) {
            case VOID -> (NativeFunction.OfVoid) a -> {
                throw new ScriptError(message);
            };
            case INT -> (NativeFunction.OfInt) a -> {
                throw new ScriptError(message);
            };
            case LONG -> (NativeFunction.OfLong) a -> {
                throw new ScriptError(message);
            };
            case FLOAT -> (NativeFunction.OfFloat) a -> {
                throw new ScriptError(message);
            };
            case DOUBLE -> (NativeFunction.OfDouble) a -> {
                throw new ScriptError(message);
            };
            case BOOL -> (NativeFunction.OfBool) a -> {
                throw new ScriptError(message);
            };
            case REF -> (NativeFunction.OfRef) a -> {
                throw new ScriptError(message);
            };
        };
    }

    /** An engine running on this platform. */
    public ScriptEngine engine(EngineOptions options) {
        return new ScriptEngine(registry, this, options, InternalErrorHandler.IGNORE);
    }

    public ScriptEngine engine() {
        return engine(EngineOptions.DEFAULT);
    }

    public SymbolRegistry registry() {
        return registry;
    }

    // ------------------------------------------------------------------ world state

    public Fakes.World world() {
        return world;
    }

    public Fakes.Player join(String name) {
        Fakes.Player player = new Fakes.Player(name, new Fakes.Location(world, 0.5, 64, 0.5, 0, 0));
        players.add(player);
        known.add(player);
        world.players().add(player);
        return player;
    }

    public void leave(Fakes.Player player) {
        players.remove(player);
        world.players().remove(player);
        player.invalidate();
    }

    public List<Fakes.Player> onlinePlayers() {
        return Collections.unmodifiableList(players);
    }

    public Fakes.Console console() {
        return console;
    }

    public List<Object> broadcasts() {
        return broadcasts;
    }

    public List<String> logs() {
        return logs;
    }

    public Set<EventDeclaration> activeEvents() {
        return activeEvents;
    }

    /** Fires an event through the engine, as the platform's event bridge would. */
    public void fire(ScriptEngine engine, EventDeclaration event, Object eventObject) {
        engine.dispatch(event, eventObject);
    }

    /** Priorities used by the handlers of each active event. */
    public Map<EventDeclaration, Set<Integer>> activePriorities() {
        return activePriorities;
    }

    /** Runs a command line such as {@code "warp set home"} as {@code sender}, like a player typing it. */
    public void command(Object sender, String line) {
        String[] words = line.strip().split(" +");
        CommandRegistry.Command command = commands.get(words[0].toLowerCase(Locale.ROOT));
        if (command == null) {
            for (CommandRegistry.Command candidate : commands.values()) {
                if (candidate.aliases().contains(words[0])) {
                    command = candidate;
                }
            }
        }
        if (command == null) {
            throw new IllegalArgumentException("Unknown command /" + words[0] + "; registered: " + commands.keySet());
        }
        command.execute(sender, words[0], java.util.Arrays.copyOfRange(words, 1, words.length));
    }

    /** Tab completion of a partial command line (a trailing space starts a new word). */
    public List<String> complete(Object sender, String line) {
        String[] words = line.split(" ", -1);
        CommandRegistry.Command command = commands.get(words[0]);
        if (command == null) {
            return List.of();
        }
        return command.complete(sender, words[0], java.util.Arrays.copyOfRange(words, 1, words.length));
    }

    /** Names of the commands scripts registered. */
    public Set<String> commandNames() {
        return Set.copyOf(commands.keySet());
    }

    /** What a help page shows for a registered root command. */
    public List<CommandRegistry.HelpEntry> helpEntries(String command) {
        CommandRegistry.Command registered = commands.get(command);
        if (registered == null) {
            throw new IllegalArgumentException("Unknown command /" + command + "; registered: " + commands.keySet());
        }
        return registered.helpEntries();
    }

    // ------------------------------------------------------------------ Platform

    @Override
    public Bindings bindings() {
        return bindings;
    }

    @Override
    public TextService text() {
        return SimpleTextService.INSTANCE;
    }

    @Override
    public EventBridge events() {
        return new EventBridge() {
            @Override
            public void activeEventsChanged(Map<EventDeclaration, Set<Integer>> priorities) {
                activePriorities = Map.copyOf(priorities);
                activeEvents = Set.copyOf(priorities.keySet());
            }

            @Override
            public boolean isCancelled(Object event) {
                return event instanceof Fakes.Cancellable cancellable && cancellable.isCancelled();
            }
        };
    }

    @Override
    public EngineLogger logger() {
        return logger;
    }

    /** The deterministic scheduler; advance time with {@code scheduler().tick(n)}. */
    @Override
    public TestScheduler scheduler() {
        return scheduler;
    }

    @Override
    public CommandRegistry commands() {
        return new CommandRegistry() {
            @Override
            public void update(List<Command> updated) {
                synchronized (commands) {
                    commands.clear();
                    updated.forEach(command -> commands.put(command.name(), command));
                }
            }

            @Override
            public boolean hasPermission(Object sender, String permission) {
                return ((Fakes.Sender) sender).hasPermission(permission);
            }

            @Override
            public Object asPlayer(Object sender) {
                return sender instanceof Fakes.Player player ? player : null;
            }

            @Override
            public String id(Object sender) {
                return sender instanceof Fakes.Player player ? player.uuid().toString() : ((Fakes.Sender) sender).name();
            }

            @Override
            public void send(Object sender, Object component) {
                ((Fakes.Sender) sender).send(component);
            }
        };
    }

    @Override
    public ArgumentTypes arguments() {
        return new ArgumentTypes() {
            @Override
            public boolean supports(ClassType type) {
                return type == MinecraftTypes.PLAYER || type == MinecraftTypes.WORLD || type == MinecraftTypes.GAME_MODE
                        || bindings.keyedValues(type).isPresent();
            }

            @Override
            public Object parse(ClassType type, String text, Object sender) throws InvalidArgument {
                if (type == MinecraftTypes.PLAYER) {
                    for (Fakes.Player player : players) {
                        if (player.name().equalsIgnoreCase(text)) {
                            return player;
                        }
                    }
                    throw new InvalidArgument("Player '" + text + "' is not online.");
                }
                if (type == MinecraftTypes.WORLD) {
                    if (world.name().equals(text)) {
                        return world;
                    }
                    throw new InvalidArgument("World '" + text + "' is not loaded.");
                }
                if (type == MinecraftTypes.GAME_MODE) {
                    try {
                        return Fakes.GameMode.valueOf(text.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new InvalidArgument("'" + text + "' is not a game mode.");
                    }
                }
                var values = bindings.keyedValues(type).orElseThrow(() -> new InvalidArgument("Unsupported " + type.name()));
                String key = text.contains(":") ? text.toLowerCase(Locale.ROOT) : "minecraft:" + text.toLowerCase(Locale.ROOT);
                Object value = values.resolve(key);
                if (value == null) {
                    throw new InvalidArgument("Unknown " + type.name() + " '" + text + "'.");
                }
                return value;
            }

            @Override
            public List<String> suggest(ClassType type, String prefix, Object sender) {
                if (type == MinecraftTypes.PLAYER) {
                    return players.stream().map(Fakes.Player::name)
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
                }
                if (type == MinecraftTypes.GAME_MODE) {
                    return java.util.Arrays.stream(Fakes.GameMode.values()).map(mode -> mode.name().toLowerCase(Locale.ROOT))
                            .filter(name -> name.startsWith(prefix)).toList();
                }
                return List.of();
            }
        };
    }

    @Override
    public PlayerDirectory players() {
        return new PlayerDirectory() {
            @Override
            public UUID id(Object player) {
                return ((Fakes.Entity) player).uuid();
            }

            @Override
            public String name(Object player) {
                return ((Fakes.Entity) player).name();
            }
        };
    }

    // ------------------------------------------------------------------ bindings

    private Bindings platformBindings() {
        Bindings.Builder b = Bindings.builder();
        b.bindType(MinecraftTypes.COMMAND_SENDER, Fakes.Sender.class)
                .bindType(MinecraftTypes.ENTITY, Fakes.Entity.class)
                .bindType(MinecraftTypes.LIVING_ENTITY, Fakes.LivingEntity.class)
                .bindType(MinecraftTypes.PLAYER, Fakes.Player.class)
                .bindType(MinecraftTypes.WORLD, Fakes.World.class)
                .bindType(MinecraftTypes.LOCATION, Fakes.Location.class)
                .bindType(MinecraftTypes.BLOCK, Fakes.Block.class)
                .bindType(MinecraftTypes.GAME_MODE, Fakes.GameMode.class)
                .bindType(MinecraftTypes.CANCELLABLE, Fakes.Cancellable.class);
        // Constants of keyed types (Material.DIAMOND, ...) are Fakes.Keyed values.
        for (ClassType type : registry.keyedTypes()) {
            String name = type.name();
            b.bindType(type, Fakes.Keyed.class);
            b.bindKeys(type, new KeyedValues() {
                @Override
                public Object resolve(String key) {
                    return registry.keys(type).name(key).isPresent() ? new Fakes.Keyed(name, key) : null;
                }

                @Override
                public String keyOf(Object value) {
                    return ((Fakes.Keyed) value).key();
                }
            });
            for (var method : registry.declaredMethods(type, "toString")) {
                if (method.parameters().isEmpty()) {
                    b.bind(method, (NativeFunction.OfRef) a -> ((Fakes.Keyed) a.getRef(0)).text());
                }
            }
            registry.declaredProperty(type, "key").ifPresent(key ->
                    b.bindGetter(key, (NativeFunction.OfRef) a -> ((Fakes.Keyed) a.getRef(0)).key()));
        }

        // CommandSender
        b.bindGetter(EntityApi.SENDER_NAME, (NativeFunction.OfRef) a -> ((Fakes.Sender) a.getRef(0)).name());
        b.bind(EntityApi.SEND, (NativeFunction.OfVoid) a -> ((Fakes.Sender) a.getRef(0)).send(a.getRef(1)));
        b.bind(EntityApi.HAS_PERMISSION, (NativeFunction.OfBool) a ->
                ((Fakes.Sender) a.getRef(0)).hasPermission(a.getString(1)));
        b.bindGetter(EntityApi.OP, (NativeFunction.OfBool) a -> ((Fakes.Sender) a.getRef(0)).isOp());

        // Entity / LivingEntity / Player
        b.bindGetter(EntityApi.ENTITY_NAME, (NativeFunction.OfRef) a -> ((Fakes.Entity) a.getRef(0)).name());
        b.bindGetter(EntityApi.ENTITY_UUID, (NativeFunction.OfRef) a -> ((Fakes.Entity) a.getRef(0)).uuid());
        b.bindGetter(EntityApi.ENTITY_LOCATION, (NativeFunction.OfRef) a -> ((Fakes.Entity) a.getRef(0)).location());
        b.bindGetter(EntityApi.ENTITY_WORLD, (NativeFunction.OfRef) a -> ((Fakes.Entity) a.getRef(0)).location().world());
        b.bindGetter(EntityApi.ENTITY_VALID, (NativeFunction.OfBool) a -> ((Fakes.Entity) a.getRef(0)).valid());
        b.bind(EntityApi.TELEPORT, (NativeFunction.OfVoid) a ->
                ((Fakes.Entity) a.getRef(0)).teleport((Fakes.Location) a.getRef(1)));
        b.bind(EntityApi.TELEPORT_TO_ENTITY, (NativeFunction.OfVoid) a ->
                ((Fakes.Entity) a.getRef(0)).teleport(((Fakes.Entity) a.getRef(1)).location()));
        b.bindGetter(EntityApi.HEALTH, (NativeFunction.OfDouble) a -> ((Fakes.LivingEntity) a.getRef(0)).health());
        b.bindSetter(EntityApi.HEALTH, a -> ((Fakes.LivingEntity) a.getRef(0)).health(a.getDouble(1)));
        b.bindGetter(EntitiesApi.LIVING_ENTITY_MAX_HEALTH, (NativeFunction.OfDouble) a ->
                ((Fakes.LivingEntity) a.getRef(0)).maxHealth());
        b.bindSetter(EntitiesApi.LIVING_ENTITY_MAX_HEALTH, a -> ((Fakes.LivingEntity) a.getRef(0)).maxHealth(a.getDouble(1)));
        b.bind(EntityApi.PLAYER_TO_STRING, (NativeFunction.OfRef) a -> ((Fakes.Player) a.getRef(0)).name());
        b.bindGetter(EntityApi.FOOD, (NativeFunction.OfInt) a -> ((Fakes.Player) a.getRef(0)).food());
        b.bindSetter(EntityApi.FOOD, a -> ((Fakes.Player) a.getRef(0)).food(a.getInt(1)));
        b.bindGetter(EntityApi.LEVEL, (NativeFunction.OfInt) a -> ((Fakes.Player) a.getRef(0)).level());
        b.bindSetter(EntityApi.LEVEL, a -> ((Fakes.Player) a.getRef(0)).level(a.getInt(1)));
        b.bindGetter(EntityApi.GAME_MODE_PROPERTY, (NativeFunction.OfRef) a -> ((Fakes.Player) a.getRef(0)).gameMode());
        b.bindSetter(EntityApi.GAME_MODE_PROPERTY, a ->
                ((Fakes.Player) a.getRef(0)).gameMode((Fakes.GameMode) a.getRef(1)));
        b.bindGetter(EntityApi.DISPLAY_NAME, (NativeFunction.OfRef) a -> ((Fakes.Player) a.getRef(0)).displayName());
        b.bindSetter(EntityApi.DISPLAY_NAME, a -> ((Fakes.Player) a.getRef(0)).displayName(a.getRef(1)));
        b.bind(EntityApi.KICK, (NativeFunction.OfVoid) a -> ((Fakes.Player) a.getRef(0)).kick(a.getRef(1)));
        // OfflinePlayer (fake players are always known and online while joined)
        b.bindGetter(EntityApi.OFFLINE_NAME, (NativeFunction.OfRef) a -> ((Fakes.Player) a.getRef(0)).name());
        b.bindGetter(EntityApi.OFFLINE_UUID, (NativeFunction.OfRef) a -> ((Fakes.Player) a.getRef(0)).uuid());
        b.bindGetter(EntityApi.OFFLINE_ONLINE, (NativeFunction.OfBool) a -> players.contains((Fakes.Player) a.getRef(0)));
        b.bindGetter(EntityApi.OFFLINE_PLAYER_ONLINE, (NativeFunction.OfRef) a ->
                players.contains((Fakes.Player) a.getRef(0)) ? a.getRef(0) : null);
        b.bindGetter(EntityApi.OFFLINE_PLAYED_BEFORE, (NativeFunction.OfBool) a -> true);
        b.bind(EntityApi.OFFLINE_TO_STRING, (NativeFunction.OfRef) a -> ((Fakes.Player) a.getRef(0)).name());
        b.bind(ServerApi.OFFLINE_PLAYER_BY_NAME, (NativeFunction.OfRef) a -> {
            for (Fakes.Player player : known) {
                if (player.name().equalsIgnoreCase(a.getString(0))) {
                    return player;
                }
            }
            throw new dev.tachyonscript.api.natives.ScriptError("No player named " + a.getString(0) + " has played here.");
        });
        b.bind(ServerApi.OFFLINE_PLAYER_BY_UUID, (NativeFunction.OfRef) a -> {
            for (Fakes.Player player : known) {
                if (player.uuid().equals(a.getRef(0))) {
                    return player;
                }
            }
            throw new dev.tachyonscript.api.natives.ScriptError("No player with UUID " + a.getRef(0) + " has played here.");
        });
        b.bindType(MinecraftTypes.OFFLINE_PLAYER, Fakes.Player.class);
        b.bindCodec(dev.tachyonscript.api.type.Types.COMPONENT, new dev.tachyonscript.api.storage.Codec() {
            @Override
            public String encode(Object value) {
                return (String) value;
            }

            @Override
            public Object decode(String text) {
                return text;
            }
        });

        // World, Location, Block, GameMode
        b.bindGetter(WorldApi.WORLD_NAME, (NativeFunction.OfRef) a -> ((Fakes.World) a.getRef(0)).name());
        b.bindGetter(WorldApi.WORLD_PLAYERS, (NativeFunction.OfRef) a -> new ArrayList<Object>(((Fakes.World) a.getRef(0)).players()));
        b.bindGetter(WorldApi.WORLD_TIME, (NativeFunction.OfLong) a -> ((Fakes.World) a.getRef(0)).time());
        b.bindSetter(WorldApi.WORLD_TIME, a -> ((Fakes.World) a.getRef(0)).time(a.getLong(1)));
        b.bind(WorldApi.WORLD_TO_STRING, (NativeFunction.OfRef) a -> ((Fakes.World) a.getRef(0)).name());
        b.bindGetter(WorldApi.LOCATION_X, (NativeFunction.OfDouble) a -> ((Fakes.Location) a.getRef(0)).x());
        b.bindGetter(WorldApi.LOCATION_Y, (NativeFunction.OfDouble) a -> ((Fakes.Location) a.getRef(0)).y());
        b.bindGetter(WorldApi.LOCATION_Z, (NativeFunction.OfDouble) a -> ((Fakes.Location) a.getRef(0)).z());
        b.bindGetter(WorldApi.LOCATION_YAW, (NativeFunction.OfFloat) a -> ((Fakes.Location) a.getRef(0)).yaw());
        b.bindGetter(WorldApi.LOCATION_PITCH, (NativeFunction.OfFloat) a -> ((Fakes.Location) a.getRef(0)).pitch());
        b.bindGetter(WorldApi.LOCATION_WORLD, (NativeFunction.OfRef) a -> ((Fakes.Location) a.getRef(0)).world());
        b.bindGetter(WorldApi.LOCATION_BLOCK, (NativeFunction.OfRef) a ->
                new Fakes.Block((Fakes.Location) a.getRef(0), "minecraft:stone"));
        b.bind(WorldApi.LOCATION_ADD, (NativeFunction.OfRef) a ->
                ((Fakes.Location) a.getRef(0)).add(a.getDouble(1), a.getDouble(2), a.getDouble(3)));
        b.bind(WorldApi.LOCATION_DISTANCE, (NativeFunction.OfDouble) a ->
                ((Fakes.Location) a.getRef(0)).distance((Fakes.Location) a.getRef(1)));
        b.bind(WorldApi.LOCATION_TO_STRING, (NativeFunction.OfRef) a -> {
            Fakes.Location l = (Fakes.Location) a.getRef(0);
            return l.world().name() + " " + Values.toString(l.x()) + ", " + Values.toString(l.y()) + ", " + Values.toString(l.z());
        });
        b.bind(WorldApi.NEW_LOCATION, (NativeFunction.OfRef) a ->
                new Fakes.Location((Fakes.World) a.getRef(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), 0, 0));
        b.bindGetter(WorldsApi.BLOCK_TYPE, (NativeFunction.OfRef) a ->
                new Fakes.Keyed("Material", ((Fakes.Block) a.getRef(0)).type()));
        b.bindGetter(WorldApi.BLOCK_LOCATION, (NativeFunction.OfRef) a -> ((Fakes.Block) a.getRef(0)).location());
        b.bindGetter(WorldApi.BLOCK_WORLD, (NativeFunction.OfRef) a -> ((Fakes.Block) a.getRef(0)).location().world());
        b.bindGetter(WorldApi.SURVIVAL, (NativeFunction.OfRef) a -> Fakes.GameMode.SURVIVAL);
        b.bindGetter(WorldApi.CREATIVE, (NativeFunction.OfRef) a -> Fakes.GameMode.CREATIVE);
        b.bindGetter(WorldApi.ADVENTURE, (NativeFunction.OfRef) a -> Fakes.GameMode.ADVENTURE);
        b.bindGetter(WorldApi.SPECTATOR, (NativeFunction.OfRef) a -> Fakes.GameMode.SPECTATOR);
        b.bind(WorldApi.GAME_MODE_TO_STRING, (NativeFunction.OfRef) a ->
                ((Fakes.GameMode) a.getRef(0)).name().toLowerCase(Locale.ROOT));

        // Server, broadcast, log
        b.bindGetter(ServerApi.PLAYERS, (NativeFunction.OfRef) a -> new ArrayList<Object>(players));
        b.bind(ServerApi.PLAYER_BY_NAME, (NativeFunction.OfRef) a -> {
            for (Fakes.Player player : players) {
                if (player.name().equals(a.getString(0))) {
                    return player;
                }
            }
            return null;
        });
        b.bindGetter(ServerApi.ONLINE_COUNT, (NativeFunction.OfInt) a -> players.size());
        b.bindGetter(ServerApi.MAX_PLAYERS, (NativeFunction.OfInt) a -> 100);
        b.bindGetter(ServerApi.WORLDS, (NativeFunction.OfRef) a -> new ArrayList<Object>(List.of(world)));
        b.bind(ServerApi.WORLD_BY_NAME, (NativeFunction.OfRef) a -> world.name().equals(a.getString(0)) ? world : null);
        b.bind(ServerApi.BROADCAST, (NativeFunction.OfVoid) a -> {
            broadcasts.add(a.getRef(0));
            players.forEach(player -> player.send(a.getRef(0)));
        });
        for (var declaration : ServerApi.LOG) {
            String level = declaration.name().equals("log") ? "info" : declaration.simpleName();
            b.bind(declaration, (NativeFunction.OfVoid) a -> logs.add("SCRIPT " + level + " " + Values.toString(a.getRef(0))));
        }

        // Text
        b.bind(TextApi.MINI, (NativeFunction.OfRef) a -> a.getString(0));
        b.bind(TextApi.PLAIN, (NativeFunction.OfRef) a -> String.valueOf(a.getRef(0)).replaceAll("<[^>]*>", ""));
        b.bind(TextApi.ESCAPE, (NativeFunction.OfRef) a -> a.getString(0).replace("<", "\\<"));

        // Events
        b.bind(EventApi.PLAYER_JOIN.variable("player").orElseThrow(), (NativeFunction.OfRef) a ->
                ((Fakes.JoinEvent) a.getRef(0)).player);
        b.bind(EventApi.PLAYER_QUIT.variable("player").orElseThrow(), (NativeFunction.OfRef) a ->
                ((Fakes.QuitEvent) a.getRef(0)).player);
        b.bind(EventApi.PLAYER_DEATH.variable("victim").orElseThrow(), (NativeFunction.OfRef) a ->
                ((Fakes.DeathEvent) a.getRef(0)).victim);
        b.bind(EventApi.PLAYER_DEATH.variable("killer").orElseThrow(), (NativeFunction.OfRef) a ->
                ((Fakes.DeathEvent) a.getRef(0)).killer);
        b.bind(EventApi.PLAYER_CHAT.variable("player").orElseThrow(), (NativeFunction.OfRef) a ->
                ((Fakes.ChatEvent) a.getRef(0)).player);
        b.bind(EventApi.PLAYER_CHAT.variable("message").orElseThrow(), (NativeFunction.OfRef) a ->
                ((Fakes.ChatEvent) a.getRef(0)).message);
        b.bind(EventApi.PLAYER_MOVE.variable("player").orElseThrow(), (NativeFunction.OfRef) a ->
                ((Fakes.MoveEvent) a.getRef(0)).player);
        b.bind(EventApi.PLAYER_MOVE.variable("from").orElseThrow(), (NativeFunction.OfRef) a ->
                ((Fakes.MoveEvent) a.getRef(0)).from);
        b.bind(EventApi.PLAYER_MOVE.variable("to").orElseThrow(), (NativeFunction.OfRef) a ->
                ((Fakes.MoveEvent) a.getRef(0)).to);
        b.bind(EventApi.BLOCK_BREAK.variable("player").orElseThrow(), (NativeFunction.OfRef) a ->
                ((Fakes.BlockBreakEvent) a.getRef(0)).player);
        b.bind(EventApi.BLOCK_BREAK.variable("block").orElseThrow(), (NativeFunction.OfRef) a ->
                ((Fakes.BlockBreakEvent) a.getRef(0)).block);
        b.bind(EventApi.ENTITY_DAMAGE.variable("entity").orElseThrow(), (NativeFunction.OfRef) a ->
                ((Fakes.DamageEvent) a.getRef(0)).entity);
        b.bind(EventApi.ENTITY_DAMAGE.variable("cause").orElseThrow(), (NativeFunction.OfRef) a ->
                ((Fakes.DamageEvent) a.getRef(0)).cause);
        b.bind(EventApi.CANCEL, (NativeFunction.OfVoid) a -> ((Fakes.Cancellable) a.getRef(0)).setCancelled(true));
        b.bind(EventApi.UNCANCEL, (NativeFunction.OfVoid) a -> ((Fakes.Cancellable) a.getRef(0)).setCancelled(false));
        b.bindGetter(EventApi.CANCELLED, (NativeFunction.OfBool) a -> ((Fakes.Cancellable) a.getRef(0)).isCancelled());
        b.bindSetter(EventApi.CANCELLED, a -> ((Fakes.Cancellable) a.getRef(0)).setCancelled(a.getBool(1)));
        b.bindGetter(EventApi.JOIN_MESSAGE, (NativeFunction.OfRef) a -> ((Fakes.JoinEvent) a.getRef(0)).joinMessage);
        b.bindSetter(EventApi.JOIN_MESSAGE, a -> ((Fakes.JoinEvent) a.getRef(0)).joinMessage = a.getRef(1));
        b.bindGetter(EventApi.QUIT_MESSAGE, (NativeFunction.OfRef) a -> ((Fakes.QuitEvent) a.getRef(0)).quitMessage);
        b.bindSetter(EventApi.QUIT_MESSAGE, a -> ((Fakes.QuitEvent) a.getRef(0)).quitMessage = a.getRef(1));
        b.bindGetter(EventApi.DEATH_MESSAGE, (NativeFunction.OfRef) a -> ((Fakes.DeathEvent) a.getRef(0)).deathMessage);
        b.bindSetter(EventApi.DEATH_MESSAGE, a -> ((Fakes.DeathEvent) a.getRef(0)).deathMessage = a.getRef(1));
        b.bindGetter(EventApi.KEEP_INVENTORY, (NativeFunction.OfBool) a -> ((Fakes.DeathEvent) a.getRef(0)).keepInventory);
        b.bindSetter(EventApi.KEEP_INVENTORY, a -> ((Fakes.DeathEvent) a.getRef(0)).keepInventory = a.getBool(1));
        b.bindGetter(EventApi.DAMAGE, (NativeFunction.OfDouble) a -> ((Fakes.DamageEvent) a.getRef(0)).damage);
        b.bindSetter(EventApi.DAMAGE, a -> ((Fakes.DamageEvent) a.getRef(0)).damage = a.getDouble(1));
        return b.build();
    }
}
