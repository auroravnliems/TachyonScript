package dev.tachyonscript.stdlib;

import dev.tachyonscript.api.declaration.Effect;
import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.declaration.ThreadingRequirement;
import dev.tachyonscript.api.doc.Documentation;
import dev.tachyonscript.api.type.Types;

import java.util.List;

import static dev.tachyonscript.stdlib.MinecraftTypes.BLOCK;
import static dev.tachyonscript.stdlib.MinecraftTypes.BLOCK_BREAK_EVENT;
import static dev.tachyonscript.stdlib.MinecraftTypes.CANCELLABLE;
import static dev.tachyonscript.stdlib.MinecraftTypes.ENTITY;
import static dev.tachyonscript.stdlib.MinecraftTypes.ENTITY_DAMAGE_EVENT;
import static dev.tachyonscript.stdlib.MinecraftTypes.LOCATION;
import static dev.tachyonscript.stdlib.MinecraftTypes.PLAYER;
import static dev.tachyonscript.stdlib.MinecraftTypes.PLAYER_CHAT_EVENT;
import static dev.tachyonscript.stdlib.MinecraftTypes.PLAYER_DEATH_EVENT;
import static dev.tachyonscript.stdlib.MinecraftTypes.PLAYER_JOIN_EVENT;
import static dev.tachyonscript.stdlib.MinecraftTypes.PLAYER_MOVE_EVENT;
import static dev.tachyonscript.stdlib.MinecraftTypes.PLAYER_QUIT_EVENT;

/** Events and the members of event objects. */
public final class EventApi {

    public static final EventDeclaration PLAYER_JOIN = EventDeclaration.builder("player.join", PLAYER_JOIN_EVENT)
            .variable("player", PLAYER, "The player who joined.")
            .threading(ThreadingRequirement.ENTITY)
            .documentation(new Documentation("A player joined the server.", "",
                    List.of("event player.join {\n    player.send(\"<green>Welcome {player.name}!\")\n}"), "0.1.0"))
            .build();

    public static final EventDeclaration PLAYER_QUIT = EventDeclaration.builder("player.quit", PLAYER_QUIT_EVENT)
            .variable("player", PLAYER, "The player who left.")
            .threading(ThreadingRequirement.ENTITY)
            .doc("A player left the server.").build();

    public static final EventDeclaration PLAYER_DEATH = EventDeclaration.builder("player.death", PLAYER_DEATH_EVENT)
            .variable("victim", PLAYER, "The player who died.")
            .variable("killer", Types.nullable(PLAYER), "The player who killed them, if any.")
            .threading(ThreadingRequirement.ENTITY)
            .doc("A player died.").build();

    public static final EventDeclaration PLAYER_CHAT = EventDeclaration.builder("player.chat", PLAYER_CHAT_EVENT)
            .variable("player", PLAYER, "The player who sent the message.")
            .variable("message", Types.STRING, "The plain text of the message.")
            .cancellable()
            .threading(ThreadingRequirement.ASYNC)
            .documentation(new Documentation("A player sent a chat message.",
                    "Chat is handled off the main thread. Treat the message as untrusted text.", List.of(), "0.1.0"))
            .build();

    public static final EventDeclaration PLAYER_MOVE = EventDeclaration.builder("player.move", PLAYER_MOVE_EVENT)
            .variable("player", PLAYER, "The moving player.")
            .variable("from", LOCATION, "Where the player was.")
            .variable("to", LOCATION, "Where the player is moving to.")
            .cancellable()
            .threading(ThreadingRequirement.ENTITY)
            .documentation(new Documentation("A player moved or turned.",
                    "Fires very often (many times per second per player). Keep handlers short.", List.of(), "0.1.0"))
            .build();

    public static final EventDeclaration BLOCK_BREAK = EventDeclaration.builder("block.break", BLOCK_BREAK_EVENT)
            .variable("player", PLAYER, "The player breaking the block.")
            .variable("block", BLOCK, "The block being broken.")
            .cancellable()
            .threading(ThreadingRequirement.ENTITY)
            .doc("A player is breaking a block.").build();

    public static final EventDeclaration ENTITY_DAMAGE = EventDeclaration.builder("entity.damage", ENTITY_DAMAGE_EVENT)
            .variable("entity", ENTITY, "The damaged entity.")
            .variable("cause", Types.STRING, "Damage cause, e.g. 'fall' or 'entity_attack'.")
            .cancellable()
            .threading(ThreadingRequirement.ENTITY)
            .doc("An entity is about to take damage. Change event.damage to modify it.").build();

    public static final List<EventDeclaration> EVENTS = List.of(PLAYER_JOIN, PLAYER_QUIT, PLAYER_DEATH, PLAYER_CHAT,
            PLAYER_MOVE, BLOCK_BREAK, ENTITY_DAMAGE);

    // ---------------------------------------------------------------- event object members

    public static final FunctionDeclaration CANCEL = FunctionDeclaration.method(CANCELLABLE, "cancel")
            .effects(Effect.MODIFIES_WORLD).doc("Cancels the event.").build();
    public static final FunctionDeclaration UNCANCEL = FunctionDeclaration.method(CANCELLABLE, "uncancel")
            .effects(Effect.MODIFIES_WORLD).doc("Un-cancels the event.").build();
    public static final PropertyDeclaration CANCELLED = PropertyDeclaration.member(CANCELLABLE, "cancelled", Types.BOOL)
            .mutable().doc("Whether the event is cancelled.").build();

    public static final PropertyDeclaration JOIN_MESSAGE = PropertyDeclaration.member(PLAYER_JOIN_EVENT, "joinMessage",
            Types.nullable(Types.COMPONENT)).mutable().doc("The broadcast join message; null for none.").build();
    public static final PropertyDeclaration QUIT_MESSAGE = PropertyDeclaration.member(PLAYER_QUIT_EVENT, "quitMessage",
            Types.nullable(Types.COMPONENT)).mutable().doc("The broadcast quit message; null for none.").build();
    public static final PropertyDeclaration DEATH_MESSAGE = PropertyDeclaration.member(PLAYER_DEATH_EVENT, "deathMessage",
            Types.nullable(Types.COMPONENT)).mutable().doc("The broadcast death message; null for none.").build();
    public static final PropertyDeclaration KEEP_INVENTORY = PropertyDeclaration.member(PLAYER_DEATH_EVENT,
            "keepInventory", Types.BOOL).mutable().doc("Whether the player keeps their inventory.").build();
    public static final PropertyDeclaration DAMAGE = PropertyDeclaration.member(ENTITY_DAMAGE_EVENT, "damage",
            Types.DOUBLE).mutable().doc("The amount of damage.").build();

    public static final List<FunctionDeclaration> FUNCTIONS = List.of(CANCEL, UNCANCEL);
    public static final List<PropertyDeclaration> PROPERTIES = List.of(CANCELLED, JOIN_MESSAGE, QUIT_MESSAGE, DEATH_MESSAGE,
            KEEP_INVENTORY, DAMAGE);

    private EventApi() {
    }
}
