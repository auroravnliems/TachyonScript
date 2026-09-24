package dev.tachyonscript.stdlib;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.natives.Arguments;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardLibraryTest {

    /** Minimal Arguments over boxed values, for calling natives directly. */
    private record Args(Object... values) implements Arguments {
        public int count() {
            return values.length;
        }

        public Object getRef(int index) {
            return values[index];
        }

        public int getInt(int index) {
            return (Integer) values[index];
        }

        public long getLong(int index) {
            return (Long) values[index];
        }

        public float getFloat(int index) {
            return (Float) values[index];
        }

        public double getDouble(int index) {
            return (Double) values[index];
        }

        public boolean getBool(int index) {
            return (Boolean) values[index];
        }
    }

    private static final Bindings CORE = StandardLibrary.coreBindings();

    private static NativeFunction bound(NativeDeclaration declaration) {
        return CORE.lookup(declaration).orElseThrow();
    }

    @Test
    void registryBuildsWithoutConflicts() {
        SymbolRegistry registry = StandardLibrary.registry();
        assertTrue(registry.event("player.join").isPresent());
        assertTrue(registry.isNamespace("server"));
        assertTrue(registry.isNamespace("GameMode"));
        assertTrue(registry.type("GameMode").isPresent());
        assertEquals(2, registry.functions("log").size());
    }

    @Test
    void coreBindingsCoverMathAndStrings() {
        List<NativeDeclaration> platform = StandardLibrary.platformDeclarations();
        assertFalse(platform.contains(MathApi.ABS_INT.invocable()));
        assertFalse(platform.contains(StringApi.UPPER.invocable()));
        assertTrue(platform.contains(EntityApi.SEND.invocable()));
        assertEquals(StandardLibrary.registry().natives().size(), platform.size() + CORE.size());
    }

    @Test
    void mathFunctionsBehave() {
        assertEquals(5, ((NativeFunction.OfInt) bound(MathApi.ABS_INT.invocable())).call(new Args(-5)));
        assertEquals(3, ((NativeFunction.OfInt) bound(MathApi.ROUND.invocable())).call(new Args(2.5)));
        assertEquals(-2, ((NativeFunction.OfInt) bound(MathApi.ROUND.invocable())).call(new Args(-2.5)));
        assertEquals(Integer.MAX_VALUE, ((NativeFunction.OfInt) bound(MathApi.FLOOR.invocable())).call(new Args(1e20)));
        assertEquals(10, ((NativeFunction.OfInt) bound(MathApi.CLAMP_INT.invocable())).call(new Args(15, 0, 10)));
        assertThrows(ScriptError.class,
                () -> ((NativeFunction.OfInt) bound(MathApi.CLAMP_INT.invocable())).call(new Args(1, 5, 0)));
        int random = ((NativeFunction.OfInt) bound(MathApi.RANDOM_INT.invocable())).call(new Args(3, 3));
        assertEquals(3, random);
    }

    @Test
    void stringFunctionsBehave() {
        assertEquals("ABC", ((NativeFunction.OfRef) bound(StringApi.UPPER.invocable())).call(new Args("abc")));
        assertEquals(42, ((NativeFunction.OfRef) bound(StringApi.TO_INT.invocable())).call(new Args(" 42 ")));
        assertNull(((NativeFunction.OfRef) bound(StringApi.TO_INT.invocable())).call(new Args("4x")));
        assertNull(((NativeFunction.OfRef) bound(StringApi.TO_DOUBLE.invocable())).call(new Args("NaN")));
        assertEquals(2.5, ((NativeFunction.OfRef) bound(StringApi.TO_DOUBLE.invocable())).call(new Args("2.5")));
        assertTrue(((NativeFunction.OfBool) bound(StringApi.CONTAINS.invocable())).call(new Args("hello", "ell")));
    }

    @Test
    void acceptsOnlyPlainDecimalNumbers() {
        for (String valid : new String[] {"0", "-1", "+2", "1.", ".5", "1.25", "1e3", "1E-3", "-.5e+2", "007"}) {
            assertTrue(StringApi.isPlainDecimal(valid), valid);
        }
        for (String invalid : new String[] {"", ".", "+", "-", "e3", "1e", "1e+", "1.2.3", "NaN", "Infinity", "0x1p3",
                "1d", "1f", "1 2", "\u0661"}) {
            assertFalse(StringApi.isPlainDecimal(invalid), invalid);
        }
        assertEquals(-50.0, ((NativeFunction.OfRef) bound(StringApi.TO_DOUBLE.invocable())).call(new Args(" -.5e+2 ")));
        assertNull(((NativeFunction.OfRef) bound(StringApi.TO_DOUBLE.invocable())).call(new Args("1d")));
    }
}
