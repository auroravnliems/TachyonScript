package dev.tachyonscript.runtime.interpreter;

import dev.tachyonscript.ir.SourceText;
import dev.tachyonscript.runtime.bytecode.BytecodeCompiler;
import dev.tachyonscript.runtime.code.CodeUnit;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Internal frame ABI for generated JVM bodies; callers enter through the runtime's normal call boundary. */
public interface BytecodeBody {
    void execute(CompiledFunction function, ExecutionStack stack, int primitiveBase, int referenceBase);

    /** Generates and defines the JVM body of a code unit. */
    static BytecodeBody compile(CodeUnit unit, SourceText source) {
        return define(BytecodeCompiler.generate(unit, source), unit.key());
    }

    /**
     * Defines generated class bytes as a hidden class in the runtime's package, without a
     * global cache or a static reference to the script. Hidden classes are not strongly tied
     * to their class loader: once the linked function (and so the generation) is unreachable,
     * the class is unloaded with it. Their frames are omitted from ordinary Java stack traces;
     * script errors are reported with .tys locations by the runtime either way.
     */
    public static BytecodeBody define(byte[] bytes, String key) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClass(bytes, true);
            return (BytecodeBody) lookup.findConstructor(lookup.lookupClass(), MethodType.methodType(void.class))
                    .asType(MethodType.methodType(BytecodeBody.class)).invokeExact();
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("Cannot define JVM code for " + key, error);
        }
    }
}
