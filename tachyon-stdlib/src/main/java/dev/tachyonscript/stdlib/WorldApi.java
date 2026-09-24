package dev.tachyonscript.stdlib;

import dev.tachyonscript.api.declaration.Effect;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.declaration.ThreadingRequirement;
import dev.tachyonscript.api.type.Types;

import java.util.List;

import static dev.tachyonscript.stdlib.MinecraftTypes.BLOCK;
import static dev.tachyonscript.stdlib.MinecraftTypes.GAME_MODE;
import static dev.tachyonscript.stdlib.MinecraftTypes.LOCATION;
import static dev.tachyonscript.stdlib.MinecraftTypes.PLAYER;
import static dev.tachyonscript.stdlib.MinecraftTypes.WORLD;

/** Worlds, locations, blocks and game modes. */
public final class WorldApi {

    // ---------------------------------------------------------------- World

    public static final PropertyDeclaration WORLD_NAME = PropertyDeclaration.member(WORLD, "name", Types.STRING)
            .doc("The world's name.").build();

    public static final PropertyDeclaration WORLD_PLAYERS = PropertyDeclaration.member(WORLD, "players", Types.list(PLAYER))
            .doc("A snapshot of the players currently in the world.").build();

    public static final PropertyDeclaration WORLD_TIME = PropertyDeclaration.member(WORLD, "time", Types.LONG)
            .mutable()
            .setterThreading(ThreadingRequirement.GLOBAL)
            .setterEffects(Effect.MODIFIES_WORLD)
            .doc("Time of day in ticks (0 to 24000).").build();

    public static final FunctionDeclaration WORLD_TO_STRING = FunctionDeclaration.method(WORLD, "toString")
            .returns(Types.STRING).doc("The world's name.").build();

    // ---------------------------------------------------------------- Location

    public static final PropertyDeclaration LOCATION_X = coordinate("x");
    public static final PropertyDeclaration LOCATION_Y = coordinate("y");
    public static final PropertyDeclaration LOCATION_Z = coordinate("z");

    public static final PropertyDeclaration LOCATION_YAW = PropertyDeclaration.member(LOCATION, "yaw", Types.FLOAT)
            .getterEffects(Effect.PURE).doc("Horizontal rotation in degrees.").build();

    public static final PropertyDeclaration LOCATION_PITCH = PropertyDeclaration.member(LOCATION, "pitch", Types.FLOAT)
            .getterEffects(Effect.PURE).doc("Vertical rotation in degrees.").build();

    public static final PropertyDeclaration LOCATION_WORLD = PropertyDeclaration.member(LOCATION, "world", Types.nullable(WORLD))
            .doc("The world of the location, or null if it is not loaded.").build();

    public static final PropertyDeclaration LOCATION_BLOCK = PropertyDeclaration.member(LOCATION, "block", BLOCK)
            .getterThreading(ThreadingRequirement.REGION)
            .doc("The block at the location.").build();

    public static final FunctionDeclaration LOCATION_ADD = FunctionDeclaration.method(LOCATION, "add")
            .parameter("x", Types.DOUBLE).parameter("y", Types.DOUBLE).parameter("z", Types.DOUBLE)
            .returns(LOCATION).effects(Effect.PURE)
            .doc("A new location offset by the given amounts.").build();

    public static final FunctionDeclaration LOCATION_DISTANCE = FunctionDeclaration.method(LOCATION, "distance")
            .parameter("other", LOCATION).returns(Types.DOUBLE).effects(Effect.PURE)
            .doc("Distance to another location in the same world.").build();

    public static final FunctionDeclaration LOCATION_TO_STRING = FunctionDeclaration.method(LOCATION, "toString")
            .returns(Types.STRING).effects(Effect.PURE).doc("Text such as 'world 10.5, 64, -3'.").build();

    public static final FunctionDeclaration NEW_LOCATION = FunctionDeclaration.global("location")
            .parameter("world", WORLD).parameter("x", Types.DOUBLE).parameter("y", Types.DOUBLE).parameter("z", Types.DOUBLE)
            .returns(LOCATION).effects(Effect.PURE)
            .doc("Creates a location.").build();

    // ---------------------------------------------------------------- Block

    public static final PropertyDeclaration BLOCK_TYPE = PropertyDeclaration.member(BLOCK, "type", Types.STRING)
            .getterThreading(ThreadingRequirement.REGION)
            .doc("The block's material key, e.g. 'minecraft:stone'.").build();

    public static final PropertyDeclaration BLOCK_LOCATION = PropertyDeclaration.member(BLOCK, "location", LOCATION)
            .getterEffects(Effect.PURE).doc("The block's location.").build();

    public static final PropertyDeclaration BLOCK_WORLD = PropertyDeclaration.member(BLOCK, "world", WORLD)
            .doc("The block's world.").build();

    // ---------------------------------------------------------------- GameMode

    public static final PropertyDeclaration SURVIVAL = gameMode("SURVIVAL");
    public static final PropertyDeclaration CREATIVE = gameMode("CREATIVE");
    public static final PropertyDeclaration ADVENTURE = gameMode("ADVENTURE");
    public static final PropertyDeclaration SPECTATOR = gameMode("SPECTATOR");

    public static final FunctionDeclaration GAME_MODE_TO_STRING = FunctionDeclaration.method(GAME_MODE, "toString")
            .returns(Types.STRING).effects(Effect.PURE).doc("Lower-case name, e.g. 'creative'.").build();

    public static final List<PropertyDeclaration> PROPERTIES = List.of(WORLD_NAME, WORLD_PLAYERS, WORLD_TIME, LOCATION_X,
            LOCATION_Y, LOCATION_Z, LOCATION_YAW, LOCATION_PITCH, LOCATION_WORLD, LOCATION_BLOCK, BLOCK_TYPE, BLOCK_LOCATION,
            BLOCK_WORLD, SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR);

    public static final List<FunctionDeclaration> FUNCTIONS = List.of(WORLD_TO_STRING, LOCATION_ADD, LOCATION_DISTANCE,
            LOCATION_TO_STRING, NEW_LOCATION, GAME_MODE_TO_STRING);

    private static PropertyDeclaration coordinate(String axis) {
        return PropertyDeclaration.member(LOCATION, axis, Types.DOUBLE).getterEffects(Effect.PURE)
                .doc("The " + axis + " coordinate.").build();
    }

    private static PropertyDeclaration gameMode(String name) {
        return PropertyDeclaration.global("GameMode." + name, GAME_MODE).getterEffects(Effect.PURE)
                .doc("The " + name.toLowerCase(java.util.Locale.ROOT) + " game mode.").build();
    }

    private WorldApi() {
    }
}
