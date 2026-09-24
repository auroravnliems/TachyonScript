package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.declaration.Effect;
import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.doc.Deprecation;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.type.ClassType;

import static dev.tachyonscript.api.type.Types.BOOL;
import static dev.tachyonscript.api.type.Types.COMPONENT;
import static dev.tachyonscript.api.type.Types.DOUBLE;
import static dev.tachyonscript.api.type.Types.INT;
import static dev.tachyonscript.api.type.Types.STRING;
import static dev.tachyonscript.api.type.Types.list;
import static dev.tachyonscript.api.type.Types.nullable;

/** A small Minecraft-like registry for frontend tests (independent of the real stdlib). */
final class TestRegistry {

    static final ClassType SENDER = ClassType.builder("CommandSender").build();
    static final ClassType ENTITY = ClassType.builder("Entity").build();
    static final ClassType LIVING = ClassType.builder("LivingEntity").supertypes(ENTITY).build();
    static final ClassType PLAYER = ClassType.builder("Player").supertypes(LIVING, SENDER).build();
    static final ClassType LOCATION = ClassType.builder("Location").build();
    static final ClassType CANCELLABLE = ClassType.builder("Cancellable").build();
    static final ClassType JOIN_EVENT = ClassType.builder("PlayerJoinEvent").build();
    static final ClassType DEATH_EVENT = ClassType.builder("PlayerDeathEvent").build();
    static final ClassType BREAK_EVENT = ClassType.builder("BlockBreakEvent").supertypes(CANCELLABLE).build();

    static final SymbolRegistry REGISTRY = SymbolRegistry.builder()
            .type(SENDER).type(ENTITY).type(LIVING).type(PLAYER).type(LOCATION).type(CANCELLABLE)
            .type(JOIN_EVENT).type(DEATH_EVENT).type(BREAK_EVENT)
            .property(PropertyDeclaration.member(ENTITY, "name", STRING).build())
            .function(FunctionDeclaration.method(PLAYER, "toString").returns(STRING).build())
            .property(PropertyDeclaration.member(LIVING, "health", DOUBLE).mutable().build())
            .property(PropertyDeclaration.member(LIVING, "maxHealth", DOUBLE).build())
            .property(PropertyDeclaration.member(PLAYER, "food", INT).mutable().build())
            .property(PropertyDeclaration.member(ENTITY, "location", LOCATION).build())
            .function(FunctionDeclaration.method(SENDER, "send").parameter("message", COMPONENT).build())
            .function(FunctionDeclaration.method(SENDER, "hasPermission").parameter("permission", STRING).returns(BOOL).build())
            .function(FunctionDeclaration.method(ENTITY, "teleport").parameter("destination", LOCATION).build())
            .function(FunctionDeclaration.method(ENTITY, "teleport").parameter("target", ENTITY).build())
            .function(FunctionDeclaration.method(PLAYER, "message").parameter("text", STRING)
                    .deprecated(new Deprecation("Old API.", "player.send(...)", "2.0")).build())
            .function(FunctionDeclaration.method(CANCELLABLE, "cancel").build())
            .property(PropertyDeclaration.member(CANCELLABLE, "cancelled", BOOL).mutable().build())
            .property(PropertyDeclaration.member(STRING, "length", INT).build())
            .function(FunctionDeclaration.method(STRING, "upper").returns(STRING).build())
            .function(FunctionDeclaration.global("broadcast").parameter("message", COMPONENT).build())
            .function(FunctionDeclaration.global("log").parameter("message", STRING).build())
            .function(FunctionDeclaration.global("log.info").parameter("message", STRING).build())
            .function(FunctionDeclaration.global("math.abs").parameter("value", INT).returns(INT).effects(Effect.PURE).build())
            .function(FunctionDeclaration.global("math.abs").parameter("value", DOUBLE).returns(DOUBLE).effects(Effect.PURE).build())
            .function(FunctionDeclaration.global("math.max").parameter("a", INT).parameter("b", INT).returns(INT).build())
            .function(FunctionDeclaration.global("math.max").parameter("a", DOUBLE).parameter("b", DOUBLE).returns(DOUBLE).build())
            .property(PropertyDeclaration.global("server.players", list(PLAYER)).build())
            .function(FunctionDeclaration.global("server.player").parameter("name", STRING).returns(nullable(PLAYER)).build())
            .event(EventDeclaration.builder("player.join", JOIN_EVENT).variable("player", PLAYER, "The player.").build())
            .event(EventDeclaration.builder("player.death", DEATH_EVENT)
                    .variable("victim", PLAYER, "Who died.").variable("killer", nullable(PLAYER), "Who killed.").build())
            .event(EventDeclaration.builder("block.break", BREAK_EVENT).variable("player", PLAYER, "Breaker.")
                    .cancellable().build())
            .build();

    private TestRegistry() {
    }
}
