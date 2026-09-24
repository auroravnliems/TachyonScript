package dev.tachyonscript.platform.paper;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.api.value.Values;
import dev.tachyonscript.stdlib.EntityApi;
import dev.tachyonscript.stdlib.EventApi;
import dev.tachyonscript.stdlib.MinecraftTypes;
import dev.tachyonscript.stdlib.ServerApi;
import dev.tachyonscript.stdlib.TextApi;
import dev.tachyonscript.stdlib.WorldApi;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Implementations of the standard library's Minecraft declarations on the Paper API.
 *
 * <p>Every binding is a small lambda over the Bukkit object passed in its arguments; there
 * is no reflection and no name lookup. Writes that must happen on the owner of an entity or
 * on the global region go through {@link Threading}, which makes the same bindings correct on
 * Paper and on Folia.
 */
final class PaperBindings {

    private final Threading threads;
    private final Logger logger;
    private final BooleanSupplier debug;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    PaperBindings(Threading threads, Logger logger, BooleanSupplier debug) {
        this.threads = threads;
        this.logger = logger;
        this.debug = debug;
    }

    Bindings create() {
        Bindings.Builder b = Bindings.builder();
        types(b);
        senders(b);
        entities(b);
        players(b);
        worlds(b);
        server(b);
        text(b);
        events(b);
        return b.build();
    }

    private static void types(Bindings.Builder b) {
        b.bindType(MinecraftTypes.UUID, UUID.class)
                .bindType(MinecraftTypes.COMMAND_SENDER, CommandSender.class)
                .bindType(MinecraftTypes.WORLD, World.class)
                .bindType(MinecraftTypes.LOCATION, Location.class)
                .bindType(MinecraftTypes.ENTITY, Entity.class)
                .bindType(MinecraftTypes.LIVING_ENTITY, LivingEntity.class)
                .bindType(MinecraftTypes.PLAYER, Player.class)
                .bindType(MinecraftTypes.BLOCK, Block.class)
                .bindType(MinecraftTypes.GAME_MODE, GameMode.class)
                .bindType(MinecraftTypes.CANCELLABLE, Cancellable.class)
                .bindType(MinecraftTypes.PLAYER_JOIN_EVENT, PlayerJoinEvent.class)
                .bindType(MinecraftTypes.PLAYER_QUIT_EVENT, PlayerQuitEvent.class)
                .bindType(MinecraftTypes.PLAYER_DEATH_EVENT, PlayerDeathEvent.class)
                .bindType(MinecraftTypes.PLAYER_CHAT_EVENT, AsyncChatEvent.class)
                .bindType(MinecraftTypes.PLAYER_MOVE_EVENT, PlayerMoveEvent.class)
                .bindType(MinecraftTypes.BLOCK_BREAK_EVENT, BlockBreakEvent.class)
                .bindType(MinecraftTypes.ENTITY_DAMAGE_EVENT, EntityDamageEvent.class)
                .bindType(Types.COMPONENT, Component.class);
    }

    private static void senders(Bindings.Builder b) {
        b.bindGetter(EntityApi.SENDER_NAME, (NativeFunction.OfRef) a -> ((CommandSender) a.getRef(0)).getName());
        b.bind(EntityApi.SEND, (NativeFunction.OfVoid) a ->
                ((CommandSender) a.getRef(0)).sendMessage((Component) a.getRef(1)));
        b.bind(EntityApi.HAS_PERMISSION, (NativeFunction.OfBool) a ->
                ((CommandSender) a.getRef(0)).hasPermission(a.getString(1)));
        b.bindGetter(EntityApi.OP, (NativeFunction.OfBool) a -> ((CommandSender) a.getRef(0)).isOp());
    }

    private void entities(Bindings.Builder b) {
        b.bindGetter(EntityApi.ENTITY_NAME, (NativeFunction.OfRef) a -> ((Entity) a.getRef(0)).getName());
        b.bindGetter(EntityApi.ENTITY_UUID, (NativeFunction.OfRef) a -> ((Entity) a.getRef(0)).getUniqueId());
        b.bindGetter(EntityApi.ENTITY_LOCATION, (NativeFunction.OfRef) a -> ((Entity) a.getRef(0)).getLocation());
        b.bindGetter(EntityApi.ENTITY_WORLD, (NativeFunction.OfRef) a -> ((Entity) a.getRef(0)).getWorld());
        b.bindGetter(EntityApi.ENTITY_VALID, (NativeFunction.OfBool) a -> ((Entity) a.getRef(0)).isValid());
        b.bind(EntityApi.TELEPORT, (NativeFunction.OfVoid) a ->
                teleport((Entity) a.getRef(0), (Location) a.getRef(1)));
        b.bind(EntityApi.TELEPORT_TO_ENTITY, (NativeFunction.OfVoid) a ->
                teleport((Entity) a.getRef(0), ((Entity) a.getRef(1)).getLocation()));
        b.bindGetter(EntityApi.HEALTH, (NativeFunction.OfDouble) a -> ((LivingEntity) a.getRef(0)).getHealth());
        b.bindSetter(EntityApi.HEALTH, a -> {
            LivingEntity entity = (LivingEntity) a.getRef(0);
            double value = a.getDouble(1);
            threads.forEntity(entity, () -> entity.setHealth(Math.max(0, Math.min(maxHealth(entity), value))));
        });
        b.bindGetter(EntityApi.MAX_HEALTH, (NativeFunction.OfDouble) a -> maxHealth((LivingEntity) a.getRef(0)));
        b.bind(EntityApi.UUID_TO_STRING, (NativeFunction.OfRef) a -> a.getRef(0).toString());
    }

    /**
     * Teleports on the thread that owns the entity. Folia only supports asynchronous
     * teleports (which load the destination chunk first), and only from that thread.
     */
    private void teleport(Entity entity, Location destination) {
        if (destination.getWorld() == null) {
            throw new ScriptError("Cannot teleport " + entity.getName() + ": the destination world is not loaded.");
        }
        Location target = destination.clone();
        if (threads.folia()) {
            threads.forEntity(entity, () -> entity.teleportAsync(target));
        } else {
            threads.forEntity(entity, () -> entity.teleport(target));
        }
    }

    private static double maxHealth(LivingEntity entity) {
        AttributeInstance attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        return attribute != null ? attribute.getValue() : 20.0;
    }

    private void players(Bindings.Builder b) {
        b.bind(EntityApi.PLAYER_TO_STRING, (NativeFunction.OfRef) a -> ((Player) a.getRef(0)).getName());
        b.bindGetter(EntityApi.FOOD, (NativeFunction.OfInt) a -> ((Player) a.getRef(0)).getFoodLevel());
        b.bindSetter(EntityApi.FOOD, a -> {
            Player player = (Player) a.getRef(0);
            int value = Math.max(0, Math.min(20, a.getInt(1)));
            threads.forEntity(player, () -> player.setFoodLevel(value));
        });
        b.bindGetter(EntityApi.LEVEL, (NativeFunction.OfInt) a -> ((Player) a.getRef(0)).getLevel());
        b.bindSetter(EntityApi.LEVEL, a -> {
            Player player = (Player) a.getRef(0);
            int value = Math.max(0, a.getInt(1));
            threads.forEntity(player, () -> player.setLevel(value));
        });
        b.bindGetter(EntityApi.GAME_MODE_PROPERTY, (NativeFunction.OfRef) a -> ((Player) a.getRef(0)).getGameMode());
        b.bindSetter(EntityApi.GAME_MODE_PROPERTY, a -> {
            Player player = (Player) a.getRef(0);
            GameMode mode = (GameMode) a.getRef(1);
            threads.forEntity(player, () -> player.setGameMode(mode));
        });
        b.bindGetter(EntityApi.DISPLAY_NAME, (NativeFunction.OfRef) a -> ((Player) a.getRef(0)).displayName());
        b.bindSetter(EntityApi.DISPLAY_NAME, a -> ((Player) a.getRef(0)).displayName((Component) a.getRef(1)));
        b.bind(EntityApi.KICK, (NativeFunction.OfVoid) a -> {
            Player player = (Player) a.getRef(0);
            Component reason = (Component) a.getRef(1);
            threads.forEntity(player, () -> player.kick(reason));
        });
    }

    private void worlds(Bindings.Builder b) {
        b.bindGetter(WorldApi.WORLD_NAME, (NativeFunction.OfRef) a -> ((World) a.getRef(0)).getName());
        b.bindGetter(WorldApi.WORLD_PLAYERS, (NativeFunction.OfRef) a -> new ArrayList<Object>(((World) a.getRef(0)).getPlayers()));
        b.bindGetter(WorldApi.WORLD_TIME, (NativeFunction.OfLong) a -> ((World) a.getRef(0)).getTime());
        b.bindSetter(WorldApi.WORLD_TIME, a -> {
            World world = (World) a.getRef(0);
            long time = a.getLong(1);
            threads.global(() -> world.setTime(time));
        });
        b.bind(WorldApi.WORLD_TO_STRING, (NativeFunction.OfRef) a -> ((World) a.getRef(0)).getName());

        b.bindGetter(WorldApi.LOCATION_X, (NativeFunction.OfDouble) a -> ((Location) a.getRef(0)).getX());
        b.bindGetter(WorldApi.LOCATION_Y, (NativeFunction.OfDouble) a -> ((Location) a.getRef(0)).getY());
        b.bindGetter(WorldApi.LOCATION_Z, (NativeFunction.OfDouble) a -> ((Location) a.getRef(0)).getZ());
        b.bindGetter(WorldApi.LOCATION_YAW, (NativeFunction.OfFloat) a -> ((Location) a.getRef(0)).getYaw());
        b.bindGetter(WorldApi.LOCATION_PITCH, (NativeFunction.OfFloat) a -> ((Location) a.getRef(0)).getPitch());
        b.bindGetter(WorldApi.LOCATION_WORLD, (NativeFunction.OfRef) a -> ((Location) a.getRef(0)).getWorld());
        b.bindGetter(WorldApi.LOCATION_BLOCK, (NativeFunction.OfRef) a -> ((Location) a.getRef(0)).getBlock());
        // Location is mutable in Bukkit: always return a copy so script values never alias.
        b.bind(WorldApi.LOCATION_ADD, (NativeFunction.OfRef) a ->
                ((Location) a.getRef(0)).clone().add(a.getDouble(1), a.getDouble(2), a.getDouble(3)));
        b.bind(WorldApi.LOCATION_DISTANCE, (NativeFunction.OfDouble) a -> {
            Location from = (Location) a.getRef(0);
            Location to = (Location) a.getRef(1);
            if (from.getWorld() != to.getWorld()) {
                throw new ScriptError("Cannot measure the distance between locations in different worlds.");
            }
            return from.distance(to);
        });
        b.bind(WorldApi.LOCATION_TO_STRING, (NativeFunction.OfRef) a -> {
            Location location = (Location) a.getRef(0);
            World world = location.getWorld();
            return (world != null ? world.getName() + " " : "") + Values.toString(location.getX()) + ", "
                    + Values.toString(location.getY()) + ", " + Values.toString(location.getZ());
        });
        b.bind(WorldApi.NEW_LOCATION, (NativeFunction.OfRef) a ->
                new Location((World) a.getRef(0), a.getDouble(1), a.getDouble(2), a.getDouble(3)));

        b.bindGetter(WorldApi.BLOCK_TYPE, (NativeFunction.OfRef) a -> ((Block) a.getRef(0)).getType().getKey().asString());
        b.bindGetter(WorldApi.BLOCK_LOCATION, (NativeFunction.OfRef) a -> ((Block) a.getRef(0)).getLocation());
        b.bindGetter(WorldApi.BLOCK_WORLD, (NativeFunction.OfRef) a -> ((Block) a.getRef(0)).getWorld());

        b.bindGetter(WorldApi.SURVIVAL, (NativeFunction.OfRef) a -> GameMode.SURVIVAL);
        b.bindGetter(WorldApi.CREATIVE, (NativeFunction.OfRef) a -> GameMode.CREATIVE);
        b.bindGetter(WorldApi.ADVENTURE, (NativeFunction.OfRef) a -> GameMode.ADVENTURE);
        b.bindGetter(WorldApi.SPECTATOR, (NativeFunction.OfRef) a -> GameMode.SPECTATOR);
        b.bind(WorldApi.GAME_MODE_TO_STRING, (NativeFunction.OfRef) a ->
                ((GameMode) a.getRef(0)).name().toLowerCase(Locale.ROOT));
    }

    private void server(Bindings.Builder b) {
        b.bindGetter(ServerApi.PLAYERS, (NativeFunction.OfRef) a -> new ArrayList<Object>(Bukkit.getOnlinePlayers()));
        b.bind(ServerApi.PLAYER_BY_NAME, (NativeFunction.OfRef) a -> Bukkit.getPlayerExact(a.getString(0)));
        b.bindGetter(ServerApi.ONLINE_COUNT, (NativeFunction.OfInt) a -> Bukkit.getOnlinePlayers().size());
        b.bindGetter(ServerApi.MAX_PLAYERS, (NativeFunction.OfInt) a -> Bukkit.getMaxPlayers());
        b.bindGetter(ServerApi.WORLDS, (NativeFunction.OfRef) a -> new ArrayList<Object>(Bukkit.getWorlds()));
        b.bind(ServerApi.WORLD_BY_NAME, (NativeFunction.OfRef) a -> Bukkit.getWorld(a.getString(0)));
        b.bind(ServerApi.BROADCAST, (NativeFunction.OfVoid) a -> Bukkit.broadcast((Component) a.getRef(0)));
        for (FunctionDeclaration log : ServerApi.LOG) {
            String level = log.name().equals("log") ? "info" : log.simpleName();
            b.bind(log, (NativeFunction.OfVoid) a -> log(level, Values.toString(a.getRef(0))));
        }
    }

    private void log(String level, String message) {
        switch (level) {
            case "warn" -> logger.warning(message);
            case "error" -> logger.severe(message);
            case "debug" -> {
                if (debug.getAsBoolean()) {
                    logger.info("[debug] " + message);
                }
            }
            default -> logger.info(message);
        }
    }

    private void text(Bindings.Builder b) {
        b.bind(TextApi.MINI, (NativeFunction.OfRef) a -> miniMessage.deserialize(a.getString(0)));
        b.bind(TextApi.PLAIN, (NativeFunction.OfRef) a ->
                PlainTextComponentSerializer.plainText().serialize((Component) a.getRef(0)));
        b.bind(TextApi.ESCAPE, (NativeFunction.OfRef) a -> miniMessage.escapeTags(a.getString(0)));
    }

    private static void events(Bindings.Builder b) {
        b.bind(variable(EventApi.PLAYER_JOIN, "player"), (NativeFunction.OfRef) a -> ((PlayerJoinEvent) a.getRef(0)).getPlayer());
        b.bind(variable(EventApi.PLAYER_QUIT, "player"), (NativeFunction.OfRef) a -> ((PlayerQuitEvent) a.getRef(0)).getPlayer());
        b.bind(variable(EventApi.PLAYER_DEATH, "victim"), (NativeFunction.OfRef) a -> ((PlayerDeathEvent) a.getRef(0)).getPlayer());
        b.bind(variable(EventApi.PLAYER_DEATH, "killer"), (NativeFunction.OfRef) a ->
                ((PlayerDeathEvent) a.getRef(0)).getPlayer().getKiller());
        b.bind(variable(EventApi.PLAYER_CHAT, "player"), (NativeFunction.OfRef) a -> ((AsyncChatEvent) a.getRef(0)).getPlayer());
        b.bind(variable(EventApi.PLAYER_CHAT, "message"), (NativeFunction.OfRef) a ->
                PlainTextComponentSerializer.plainText().serialize(((AsyncChatEvent) a.getRef(0)).message()));
        b.bind(variable(EventApi.PLAYER_MOVE, "player"), (NativeFunction.OfRef) a -> ((PlayerMoveEvent) a.getRef(0)).getPlayer());
        b.bind(variable(EventApi.PLAYER_MOVE, "from"), (NativeFunction.OfRef) a -> ((PlayerMoveEvent) a.getRef(0)).getFrom());
        b.bind(variable(EventApi.PLAYER_MOVE, "to"), (NativeFunction.OfRef) a -> ((PlayerMoveEvent) a.getRef(0)).getTo());
        b.bind(variable(EventApi.BLOCK_BREAK, "player"), (NativeFunction.OfRef) a -> ((BlockBreakEvent) a.getRef(0)).getPlayer());
        b.bind(variable(EventApi.BLOCK_BREAK, "block"), (NativeFunction.OfRef) a -> ((BlockBreakEvent) a.getRef(0)).getBlock());
        b.bind(variable(EventApi.ENTITY_DAMAGE, "entity"), (NativeFunction.OfRef) a -> ((EntityDamageEvent) a.getRef(0)).getEntity());
        b.bind(variable(EventApi.ENTITY_DAMAGE, "cause"), (NativeFunction.OfRef) a ->
                ((EntityDamageEvent) a.getRef(0)).getCause().name().toLowerCase(Locale.ROOT));

        b.bind(EventApi.CANCEL, (NativeFunction.OfVoid) a -> ((Cancellable) a.getRef(0)).setCancelled(true));
        b.bind(EventApi.UNCANCEL, (NativeFunction.OfVoid) a -> ((Cancellable) a.getRef(0)).setCancelled(false));
        b.bindGetter(EventApi.CANCELLED, (NativeFunction.OfBool) a -> ((Cancellable) a.getRef(0)).isCancelled());
        b.bindSetter(EventApi.CANCELLED, a -> ((Cancellable) a.getRef(0)).setCancelled(a.getBool(1)));
        b.bindGetter(EventApi.JOIN_MESSAGE, (NativeFunction.OfRef) a -> ((PlayerJoinEvent) a.getRef(0)).joinMessage());
        b.bindSetter(EventApi.JOIN_MESSAGE, a -> ((PlayerJoinEvent) a.getRef(0)).joinMessage((Component) a.getRef(1)));
        b.bindGetter(EventApi.QUIT_MESSAGE, (NativeFunction.OfRef) a -> ((PlayerQuitEvent) a.getRef(0)).quitMessage());
        b.bindSetter(EventApi.QUIT_MESSAGE, a -> ((PlayerQuitEvent) a.getRef(0)).quitMessage((Component) a.getRef(1)));
        b.bindGetter(EventApi.DEATH_MESSAGE, (NativeFunction.OfRef) a -> ((PlayerDeathEvent) a.getRef(0)).deathMessage());
        b.bindSetter(EventApi.DEATH_MESSAGE, a -> ((PlayerDeathEvent) a.getRef(0)).deathMessage((Component) a.getRef(1)));
        b.bindGetter(EventApi.KEEP_INVENTORY, (NativeFunction.OfBool) a -> ((PlayerDeathEvent) a.getRef(0)).getKeepInventory());
        b.bindSetter(EventApi.KEEP_INVENTORY, a -> ((PlayerDeathEvent) a.getRef(0)).setKeepInventory(a.getBool(1)));
        b.bindGetter(EventApi.DAMAGE, (NativeFunction.OfDouble) a -> ((EntityDamageEvent) a.getRef(0)).getDamage());
        b.bindSetter(EventApi.DAMAGE, a -> ((EntityDamageEvent) a.getRef(0)).setDamage(Math.max(0, a.getDouble(1))));
    }

    private static dev.tachyonscript.api.declaration.EventVariable variable(EventDeclaration event, String name) {
        return event.variable(name).orElseThrow(() -> new IllegalStateException(event + " has no variable " + name));
    }
}
