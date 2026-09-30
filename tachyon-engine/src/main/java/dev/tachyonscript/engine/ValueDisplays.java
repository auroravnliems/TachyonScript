package dev.tachyonscript.engine;

import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.natives.Arguments;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.api.value.Values;

import java.util.Optional;

/**
 * Registers the {@code toString()} member of every bound type as the display of its Java
 * class, so host objects read the same everywhere: inserted on their own ({@code "{player}"}),
 * inside a record ({@code Warp(owner=Steve)}), in a list printed by {@code log(...)}, or in JSON.
 */
final class ValueDisplays {

    private ValueDisplays() {
    }

    static void install(SymbolRegistry registry, Bindings bindings) {
        for (ClassType type : bindings.boundTypes()) {
            Optional<Class<?>> javaClass = bindings.typeClass(type);
            if (javaClass.isEmpty() || javaClass.get() == Object.class) {
                continue;
            }
            for (FunctionDeclaration method : registry.declaredMethods(type, "toString")) {
                if (!method.parameters().isEmpty() || method.returnType() != Types.STRING) {
                    continue;
                }
                bindings.lookup(method.invocable()).ifPresent(function -> {
                    if (function instanceof NativeFunction.OfRef text) {
                        Values.registerDisplay(javaClass.get(), value -> (String) text.call(new Single(value)));
                    }
                });
            }
        }
    }

    /** The arguments of a call with only a receiver. */
    private record Single(Object receiver) implements Arguments {

        @Override
        public int count() {
            return 1;
        }

        @Override
        public Object getRef(int index) {
            return receiver;
        }

        @Override
        public int getInt(int index) {
            throw new IllegalStateException("not an int argument");
        }

        @Override
        public long getLong(int index) {
            throw new IllegalStateException("not a long argument");
        }

        @Override
        public float getFloat(int index) {
            throw new IllegalStateException("not a float argument");
        }

        @Override
        public double getDouble(int index) {
            throw new IllegalStateException("not a double argument");
        }

        @Override
        public boolean getBool(int index) {
            throw new IllegalStateException("not a bool argument");
        }
    }
}
