package dev.tachyonscript.stdlib;

import dev.tachyonscript.api.declaration.Effect;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.declaration.ThreadingRequirement;
import dev.tachyonscript.api.doc.Documentation;
import dev.tachyonscript.api.type.Types;

import java.util.List;

import static dev.tachyonscript.stdlib.MinecraftTypes.COMMAND_SENDER;
import static dev.tachyonscript.stdlib.MinecraftTypes.ENTITY;
import static dev.tachyonscript.stdlib.MinecraftTypes.GAME_MODE;
import static dev.tachyonscript.stdlib.MinecraftTypes.LIVING_ENTITY;
import static dev.tachyonscript.stdlib.MinecraftTypes.LOCATION;
import static dev.tachyonscript.stdlib.MinecraftTypes.PLAYER;
import static dev.tachyonscript.stdlib.MinecraftTypes.UUID;
import static dev.tachyonscript.stdlib.MinecraftTypes.WORLD;

/** Members of command senders, entities and players. */
public final class EntityApi {

    // ---------------------------------------------------------------- CommandSender

    public static final PropertyDeclaration SENDER_NAME = PropertyDeclaration.member(COMMAND_SENDER, "name", Types.STRING)
            .doc("The sender's name (the player name, or CONSOLE).").build();

    public static final FunctionDeclaration SEND = FunctionDeclaration.method(COMMAND_SENDER, "send")
            .parameter("message", Types.COMPONENT)
            .documentation(new Documentation("Sends a chat message.",
                    "A string argument is MiniMessage text; interpolated values are inserted as plain text.",
                    List.of("player.send(\"<green>Welcome {player.name}!\")"), "0.1.0"))
            .threading(ThreadingRequirement.ANY)
            .build();

    public static final FunctionDeclaration HAS_PERMISSION = FunctionDeclaration.method(COMMAND_SENDER, "hasPermission")
            .parameter("permission", Types.STRING).returns(Types.BOOL)
            .effects(Effect.READS_WORLD)
            .doc("Whether the sender has a permission.").build();

    public static final PropertyDeclaration OP = PropertyDeclaration.member(COMMAND_SENDER, "op", Types.BOOL)
            .doc("Whether the sender is a server operator.").build();

    // ---------------------------------------------------------------- Entity

    public static final PropertyDeclaration ENTITY_NAME = PropertyDeclaration.member(ENTITY, "name", Types.STRING)
            .doc("The entity's name.").build();

    public static final PropertyDeclaration ENTITY_UUID = PropertyDeclaration.member(ENTITY, "uuid", UUID)
            .doc("The entity's unique id.").build();

    public static final PropertyDeclaration ENTITY_LOCATION = PropertyDeclaration.member(ENTITY, "location", LOCATION)
            .getterThreading(ThreadingRequirement.ENTITY)
            .doc("A copy of the entity's current location.").build();

    public static final PropertyDeclaration ENTITY_WORLD = PropertyDeclaration.member(ENTITY, "world", WORLD)
            .doc("The world the entity is in.").build();

    public static final PropertyDeclaration ENTITY_VALID = PropertyDeclaration.member(ENTITY, "valid", Types.BOOL)
            .doc("Whether the entity still exists (false after it died, despawned or the player left).").build();

    public static final FunctionDeclaration TELEPORT = FunctionDeclaration.method(ENTITY, "teleport")
            .parameter("destination", LOCATION)
            .effects(Effect.MODIFIES_WORLD)
            .threading(ThreadingRequirement.ENTITY)
            .doc("Teleports the entity to a location.").build();

    public static final FunctionDeclaration TELEPORT_TO_ENTITY = FunctionDeclaration.method(ENTITY, "teleport")
            .parameter("target", ENTITY)
            .effects(Effect.MODIFIES_WORLD)
            .threading(ThreadingRequirement.ENTITY)
            .doc("Teleports the entity to another entity.").build();

    // ---------------------------------------------------------------- LivingEntity

    public static final PropertyDeclaration HEALTH = PropertyDeclaration.member(LIVING_ENTITY, "health", Types.DOUBLE)
            .mutable()
            .getterThreading(ThreadingRequirement.ENTITY)
            .setterThreading(ThreadingRequirement.ENTITY)
            .setterEffects(Effect.MODIFIES_WORLD)
            .doc("Current health, between 0 and maxHealth.").build();

    public static final PropertyDeclaration MAX_HEALTH = PropertyDeclaration.member(LIVING_ENTITY, "maxHealth", Types.DOUBLE)
            .getterThreading(ThreadingRequirement.ENTITY)
            .doc("Maximum health.").build();

    // ---------------------------------------------------------------- Player

    public static final FunctionDeclaration PLAYER_TO_STRING = FunctionDeclaration.method(PLAYER, "toString")
            .returns(Types.STRING).doc("The player's name; used when a player is inserted into text.").build();

    public static final PropertyDeclaration FOOD = PropertyDeclaration.member(PLAYER, "food", Types.INT)
            .mutable()
            .getterThreading(ThreadingRequirement.ENTITY)
            .setterThreading(ThreadingRequirement.ENTITY)
            .setterEffects(Effect.MODIFIES_WORLD)
            .doc("Food level, from 0 to 20.").build();

    public static final PropertyDeclaration LEVEL = PropertyDeclaration.member(PLAYER, "level", Types.INT)
            .mutable()
            .getterThreading(ThreadingRequirement.ENTITY)
            .setterThreading(ThreadingRequirement.ENTITY)
            .setterEffects(Effect.MODIFIES_WORLD)
            .doc("Experience level.").build();

    public static final PropertyDeclaration GAME_MODE_PROPERTY = PropertyDeclaration.member(PLAYER, "gameMode", GAME_MODE)
            .mutable()
            .setterThreading(ThreadingRequirement.ENTITY)
            .setterEffects(Effect.MODIFIES_WORLD)
            .doc("The player's game mode.").build();

    public static final PropertyDeclaration DISPLAY_NAME = PropertyDeclaration.member(PLAYER, "displayName", Types.COMPONENT)
            .mutable()
            .doc("The name shown in chat.").build();

    public static final FunctionDeclaration KICK = FunctionDeclaration.method(PLAYER, "kick")
            .parameter("reason", Types.COMPONENT)
            .threading(ThreadingRequirement.ENTITY)
            .effects(Effect.MODIFIES_WORLD)
            .doc("Disconnects the player with a reason.").build();

    // ---------------------------------------------------------------- UUID

    public static final FunctionDeclaration UUID_TO_STRING = FunctionDeclaration.method(UUID, "toString")
            .returns(Types.STRING).effects(Effect.PURE).doc("The canonical text form of the UUID.").build();

    public static final List<PropertyDeclaration> PROPERTIES = List.of(SENDER_NAME, OP, ENTITY_NAME, ENTITY_UUID,
            ENTITY_LOCATION, ENTITY_WORLD, ENTITY_VALID, HEALTH, MAX_HEALTH, FOOD, LEVEL, GAME_MODE_PROPERTY, DISPLAY_NAME);

    public static final List<FunctionDeclaration> FUNCTIONS = List.of(SEND, HAS_PERMISSION, TELEPORT, TELEPORT_TO_ENTITY,
            PLAYER_TO_STRING, KICK, UUID_TO_STRING);

    private EntityApi() {
    }
}
