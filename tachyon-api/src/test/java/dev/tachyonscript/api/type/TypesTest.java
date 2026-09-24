package dev.tachyonscript.api.type;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypesTest {

    private static final ClassType ENTITY = ClassType.builder("Entity").build();
    private static final ClassType LIVING = ClassType.builder("LivingEntity").supertypes(ENTITY).build();
    private static final ClassType SENDER = ClassType.builder("CommandSender").build();
    private static final ClassType PLAYER = ClassType.builder("Player").supertypes(LIVING, SENDER).build();

    @Test
    void subtypingIsReflexiveTransitiveAndIncludesAny() {
        assertTrue(PLAYER.isSubtypeOf(PLAYER));
        assertTrue(PLAYER.isSubtypeOf(LIVING));
        assertTrue(PLAYER.isSubtypeOf(ENTITY));
        assertTrue(PLAYER.isSubtypeOf(SENDER));
        assertTrue(PLAYER.isSubtypeOf(Types.ANY));
        assertFalse(ENTITY.isSubtypeOf(PLAYER));
        assertFalse(SENDER.isSubtypeOf(ENTITY));
    }

    @Test
    void linearizationIsDepthFirstInDeclarationOrder() {
        assertEquals(List.of(PLAYER, LIVING, ENTITY, SENDER), PLAYER.linearization());
    }

    @Test
    void nullableIsIdempotentAndRejectsInvalidInner() {
        Type nullablePlayer = Types.nullable(PLAYER);
        assertEquals("Player?", nullablePlayer.displayName());
        assertSame(nullablePlayer, Types.nullable(nullablePlayer));
        assertSame(Types.NULL, Types.nullable(Types.NULL));
        assertEquals(PLAYER, nullablePlayer.nonNullable());
        assertEquals(Representation.REF, Types.nullable(Types.INT).representation());
        assertThrows(IllegalArgumentException.class, () -> new NullableType(Types.VOID));
        assertThrows(IllegalArgumentException.class, () -> new NullableType(nullablePlayer));
    }

    @Test
    void structuralTypesCompareByValue() {
        assertEquals(Types.list(Types.STRING), Types.list(Types.STRING));
        assertEquals("Map<string, int?>", Types.map(Types.STRING, Types.nullable(Types.INT)).displayName());
        assertThrows(IllegalArgumentException.class, () -> Types.map(Types.nullable(Types.STRING), Types.INT));
        assertThrows(IllegalArgumentException.class, () -> Types.list(Types.VOID));
    }

    @Test
    void primitiveRepresentations() {
        assertEquals(Representation.LONG, Types.DURATION.representation());
        assertTrue(Types.INT.isNumeric());
        assertFalse(Types.BOOL.isNumeric());
        assertTrue(Representation.DOUBLE.isPrimitive());
        assertFalse(Representation.REF.isPrimitive());
    }

    @Test
    void rejectsInvalidTypeNames() {
        assertThrows(IllegalArgumentException.class, () -> ClassType.builder("1Player"));
        assertThrows(IllegalArgumentException.class, () -> ClassType.builder("Pla yer"));
        assertThrows(IllegalArgumentException.class, () -> ClassType.builder(""));
    }
}
