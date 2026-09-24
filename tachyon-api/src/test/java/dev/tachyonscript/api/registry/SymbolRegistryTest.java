package dev.tachyonscript.api.registry;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.declaration.ThreadingRequirement;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.Types;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolRegistryTest {

    private static final ClassType ENTITY = ClassType.builder("Entity").build();
    private static final ClassType PLAYER = ClassType.builder("Player").supertypes(ENTITY).build();
    private static final ClassType JOIN_EVENT = ClassType.builder("PlayerJoinEvent").build();

    private static SymbolRegistry.Builder base() {
        return SymbolRegistry.builder().type(ENTITY).type(PLAYER).type(JOIN_EVENT);
    }

    @Test
    void overloadsAreAllowedButDuplicateSignaturesAreRejectedWithoutSideEffects() {
        FunctionDeclaration absInt = FunctionDeclaration.global("math.abs").parameter("value", Types.INT).returns(Types.INT).build();
        FunctionDeclaration absDouble = FunctionDeclaration.global("math.abs").parameter("value", Types.DOUBLE).returns(Types.DOUBLE).build();
        FunctionDeclaration duplicate = FunctionDeclaration.global("math.abs").parameter("x", Types.INT).returns(Types.INT).build();

        SymbolRegistry.Builder builder = base().function(absInt).function(absDouble);
        RegistrationException error = assertThrows(RegistrationException.class, () -> builder.function(duplicate));
        assertTrue(error.getMessage().contains("math.abs(int)"), error.getMessage());

        SymbolRegistry registry = builder.build();
        assertEquals(List.of(absInt, absDouble), registry.functions("math.abs"));
        assertEquals(2, registry.natives().size());
    }

    @Test
    void namespacesAreDerivedFromQualifiedNames() {
        SymbolRegistry registry = base()
                .function(FunctionDeclaration.global("log.info").parameter("message", Types.STRING).build())
                .function(FunctionDeclaration.global("log").parameter("message", Types.STRING).build())
                .property(PropertyDeclaration.global("server.players", Types.list(PLAYER)).build())
                .build();
        assertTrue(registry.isNamespace("log"));
        assertTrue(registry.isNamespace("server"));
        assertFalse(registry.isNamespace("server.players"));
        assertFalse(registry.isNamespace(""));
        assertEquals(Set.of("info"), registry.namespaceMembers("log"));
        assertEquals(Set.of("log", "server"), registry.namespaceMembers(""));
        assertEquals(1, registry.functions("log").size());
    }

    @Test
    void membersAreStoredPerOwnerAndConflictsAreRejected() {
        PropertyDeclaration health = PropertyDeclaration.member(ENTITY, "health", Types.DOUBLE).mutable()
                .setterThreading(ThreadingRequirement.ENTITY).build();
        FunctionDeclaration send = FunctionDeclaration.method(PLAYER, "send").parameter("message", Types.COMPONENT).build();
        SymbolRegistry.Builder builder = base().property(health).function(send);

        assertThrows(RegistrationException.class,
                () -> builder.function(FunctionDeclaration.method(ENTITY, "health").returns(Types.DOUBLE).build()));
        assertThrows(RegistrationException.class,
                () -> builder.property(PropertyDeclaration.member(ENTITY, "health", Types.DOUBLE).build()));

        SymbolRegistry registry = builder.build();
        assertTrue(registry.declaredProperty(ENTITY, "health").isPresent());
        assertTrue(registry.declaredProperty(PLAYER, "health").isEmpty(), "lookup is not inherited");
        assertEquals(List.of(send), registry.declaredMethods(PLAYER, "send"));
        assertEquals(Set.of("send"), registry.declaredMemberNames(PLAYER));
        assertEquals("Entity.health:set", health.setter().orElseThrow().key());
        assertEquals(0, health.setter().orElseThrow().threadingParameter());
        assertEquals(NativeDeclaration.Kind.METHOD, send.invocable().kind());
        assertEquals(2, send.invocable().arity(), "receiver is parameter 0");
    }

    @Test
    void rejectsUnregisteredAndReservedTypes() {
        ClassType stranger = ClassType.builder("World").build();
        assertThrows(RegistrationException.class,
                () -> base().function(FunctionDeclaration.global("worlds.main").returns(stranger).build()));
        assertThrows(RegistrationException.class, () -> base().type(ClassType.builder("List").build()));
        assertThrows(RegistrationException.class, () -> base().type(ClassType.builder("Player").build()));
        ClassType orphan = ClassType.builder("Zombie").supertypes(stranger).build();
        assertThrows(RegistrationException.class, () -> base().type(orphan));
    }

    @Test
    void eventsHaveDenseIndicesAndVariableGetters() {
        EventDeclaration join = EventDeclaration.builder("player.join", JOIN_EVENT)
                .variable("player", PLAYER, "The joining player.")
                .threading(ThreadingRequirement.ENTITY)
                .build();
        EventDeclaration quit = EventDeclaration.builder("player.quit", JOIN_EVENT)
                .variable("player", PLAYER, "The leaving player.")
                .build();
        SymbolRegistry registry = base().event(join).event(quit).build();
        assertEquals(0, registry.eventIndex(join));
        assertEquals(1, registry.eventIndex(quit));
        assertEquals("event player.join:player", join.variable("player").orElseThrow().getter().key());
        assertThrows(RegistrationException.class, () -> base().event(join).event(join));
        assertThrows(IllegalArgumentException.class,
                () -> EventDeclaration.builder("x.y", JOIN_EVENT).variable("event", PLAYER, ""));
    }

    @Test
    void globalPropertyCannotShadowNamespace() {
        SymbolRegistry.Builder builder = base()
                .function(FunctionDeclaration.global("server.broadcast").parameter("message", Types.COMPONENT).build());
        assertThrows(RegistrationException.class,
                () -> builder.property(PropertyDeclaration.global("server", Types.STRING).build()));
    }

    @Test
    void bindingsValidateShapeAndDuplicates() {
        FunctionDeclaration abs = FunctionDeclaration.global("math.abs").parameter("value", Types.INT).returns(Types.INT).build();
        NativeFunction.OfInt absImpl = args -> Math.abs(args.getInt(0));
        Bindings.Builder builder = Bindings.builder();
        assertThrows(RegistrationException.class, () -> builder.bind(abs, (NativeFunction.OfDouble) args -> 1.0));
        builder.bind(abs, absImpl);
        assertThrows(RegistrationException.class, () -> builder.bind(abs, absImpl));
        Bindings bindings = builder.build();
        assertEquals(absImpl, bindings.lookup(abs.invocable()).orElseThrow());

        FunctionDeclaration otherAbs = FunctionDeclaration.global("math.abs").parameter("value", Types.INT).returns(Types.LONG).build();
        assertTrue(bindings.lookup(otherAbs.invocable()).isEmpty(), "same key, different signature must not match");

        Bindings merged = Bindings.builder().include(bindings).bindType(PLAYER, Object.class).build();
        assertEquals(1, merged.size());
        assertEquals(Object.class, merged.typeClass(PLAYER).orElseThrow());
        assertThrows(RegistrationException.class, () -> Bindings.builder().include(bindings).include(bindings));
    }
}
