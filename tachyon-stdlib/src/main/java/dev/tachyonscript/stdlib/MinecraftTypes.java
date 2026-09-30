package dev.tachyonscript.stdlib;

import dev.tachyonscript.api.type.ClassType;

import java.util.List;

/**
 * Tachyon-owned descriptors of Minecraft types. They carry no Bukkit dependency; the
 * platform binds each one to its runtime class.
 */
public final class MinecraftTypes {

    public static final ClassType UUID = ClassType.builder("UUID").storable().doc("A unique identifier.").build();
    public static final ClassType COMMAND_SENDER = ClassType.builder("CommandSender")
            .doc("Something that can run commands and receive messages: a player or the console.").build();
    public static final ClassType WORLD = ClassType.builder("World").storable().doc("A loaded world.").build();
    public static final ClassType LOCATION = ClassType.builder("Location").storable()
            .doc("A position (x, y, z, yaw, pitch) in a world.").build();
    public static final ClassType ENTITY = ClassType.builder("Entity").doc("Any entity in a world.").build();
    public static final ClassType LIVING_ENTITY = ClassType.builder("LivingEntity").supertypes(ENTITY)
            .doc("An entity with health, such as a mob or a player.").build();
    public static final ClassType OFFLINE_PLAYER = ClassType.builder("OfflinePlayer").storable()
            .doc("A player who may be offline: everything known about a player without them being online.").build();
    public static final ClassType PLAYER = ClassType.builder("Player").supertypes(LIVING_ENTITY, COMMAND_SENDER, OFFLINE_PLAYER)
            .doc("An online player.").build();
    public static final ClassType BLOCK = ClassType.builder("Block").doc("A block at a position in a world.").build();
    public static final ClassType GAME_MODE = ClassType.builder("GameMode")
            .doc("A player game mode: GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE or GameMode.SPECTATOR.")
            .build();

    /** A repeating block started with {@code every} ({@code task} inside the block). */
    public static final ClassType TASK = ClassType.builder("Task")
            .doc("A repeating block started with 'every'; call task.cancel() to stop it.").build();

    /** Event object types. */
    public static final ClassType CANCELLABLE = ClassType.builder("Cancellable")
            .doc("An event that handlers can cancel.").build();
    public static final ClassType PLAYER_JOIN_EVENT = ClassType.builder("PlayerJoinEvent").build();
    public static final ClassType PLAYER_QUIT_EVENT = ClassType.builder("PlayerQuitEvent").build();
    public static final ClassType ENTITY_DEATH_EVENT = ClassType.builder("EntityDeathEvent").supertypes(CANCELLABLE).build();
    public static final ClassType PLAYER_DEATH_EVENT = ClassType.builder("PlayerDeathEvent").supertypes(ENTITY_DEATH_EVENT)
            .build();
    public static final ClassType PLAYER_CHAT_EVENT = ClassType.builder("PlayerChatEvent").supertypes(CANCELLABLE).build();
    public static final ClassType PLAYER_MOVE_EVENT = ClassType.builder("PlayerMoveEvent").supertypes(CANCELLABLE).build();
    public static final ClassType BLOCK_BREAK_EVENT = ClassType.builder("BlockBreakEvent").supertypes(CANCELLABLE).build();
    public static final ClassType ENTITY_DAMAGE_EVENT = ClassType.builder("EntityDamageEvent").supertypes(CANCELLABLE).build();

    /** Every type, supertypes before subtypes (registration order). */
    public static final List<ClassType> ALL = List.of(UUID, COMMAND_SENDER, WORLD, LOCATION, ENTITY, LIVING_ENTITY,
            OFFLINE_PLAYER, PLAYER, BLOCK, GAME_MODE, TASK, CANCELLABLE, PLAYER_JOIN_EVENT, PLAYER_QUIT_EVENT, ENTITY_DEATH_EVENT,
            PLAYER_DEATH_EVENT,
            PLAYER_CHAT_EVENT, PLAYER_MOVE_EVENT, BLOCK_BREAK_EVENT, ENTITY_DAMAGE_EVENT);

    private MinecraftTypes() {
    }
}
