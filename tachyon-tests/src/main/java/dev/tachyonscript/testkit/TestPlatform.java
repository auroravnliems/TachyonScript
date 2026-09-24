package dev.tachyonscript.testkit;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.value.Values;
import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.spi.EngineLogger;
import dev.tachyonscript.engine.spi.EventBridge;
import dev.tachyonscript.engine.spi.Platform;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A complete in-memory platform: every standard declaration is bound to the {@link Fakes},
 * events are fired by calling {@link #fire}, and messages, logs and active listeners are
 * recorded for assertions. Components are MiniMessage strings ({@link SimpleTextService}).
 */
public final class TestPlatform implements Platform {

    private final Fakes.World world = new Fakes.World("world");
    private final List<Fakes.Player> players = new ArrayList<>();
    private final Fakes.Console console = new Fakes.Console();
    private final List<Object> broadcasts = new CopyOnWriteArrayList<>();
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private volatile Set<EventDeclaration> activeEvents = Set.of();
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

    public TestPlatform() {
        this.bindings = Bindings.builder().include(StandardLibrary.coreBindings()).include(platformBindings()).build();
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
        world.players().add(player);
        return player;
    }

    public void leave(Fakes.Player player) {
        players.remove(player);
        world.players().remove(player);
        player.invalidate();
    }

    public List<Fakes.Player> players() {
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
        return events -> activeEvents = Set.copyOf(events);
    }

    @Override
    public EngineLogger logger() {
        return logger;
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
                .bindType(MinecraftTypes.UUID, java.util.UUID.class)
                .bindType(MinecraftTypes.CANCELLABLE, Fakes.Cancellable.class);

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
        b.bindGetter(EntityApi.MAX_HEALTH, (NativeFunction.OfDouble) a -> ((Fakes.LivingEntity) a.getRef(0)).maxHealth());
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
        b.bind(EntityApi.UUID_TO_STRING, (NativeFunction.OfRef) a -> a.getRef(0).toString());

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
        b.bindGetter(WorldApi.BLOCK_TYPE, (NativeFunction.OfRef) a -> ((Fakes.Block) a.getRef(0)).type());
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
