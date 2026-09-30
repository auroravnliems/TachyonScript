package dev.tachyonscript.runtime.code;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.ir.FunctionRef;
import dev.tachyonscript.ir.GlobalRef;
import dev.tachyonscript.ir.IrFunction;
import dev.tachyonscript.ir.RecordRef;

import java.util.List;

/**
 * An assembled function: packed code plus the symbolic tables the linker resolves.
 * Platform-neutral; one code unit can be linked against different bindings.
 *
 * @param handlers exception handlers as {@code (startPc, endPc, handlerPc, errorSlot)}
 *                 quadruples: an error raised at a pc in {@code [startPc, endPc)} continues at
 *                 {@code handlerPc} with the error in reference slot {@code errorSlot}
 */
public record CodeUnit(
        String key,
        String displayName,
        IrFunction.Kind kind,
        int[] code,
        int primitiveSlots,
        int referenceSlots,
        int[] parameterSlots,
        boolean[] parameterIsReference,
        Representation returnKind,
        long[] primitivePool,
        Object[] referencePool,
        List<NativeDeclaration> natives,
        List<FunctionRef> functions,
        List<ClassType> classes,
        List<List<String>> templates,
        List<GlobalRef> globals,
        List<RecordRef> records,
        int[] handlers,
        int[] linePcs,
        long[] lineSpans,
        long span) {

    public CodeUnit {
        natives = List.copyOf(natives);
        functions = List.copyOf(functions);
        classes = List.copyOf(classes);
        templates = templates.stream().map(List::copyOf).toList();
        globals = List.copyOf(globals);
        records = List.copyOf(records);
    }

    /** Packed source span of the instruction at {@code pc} (or of the function if unknown). */
    public long spanAt(int pc) {
        int low = 0;
        int high = linePcs.length - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (linePcs[mid] <= pc) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found < 0 ? span : lineSpans[found];
    }
}
