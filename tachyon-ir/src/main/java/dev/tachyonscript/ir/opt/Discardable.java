package dev.tachyonscript.ir.opt;

import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.ir.Instruction;

/** Deliberately narrower than hasSideEffects: host equals/toString/collections may execute code. */
final class Discardable {
    private Discardable() { }

    static boolean test(Instruction instruction) {
        return switch (instruction) {
            case Instruction.Const ignored -> true;
            case Instruction.Move ignored -> true;
            case Instruction.Unary ignored -> true;
            case Instruction.Binary b -> !b.op().canFail() && b.op().operand() != Representation.REF;
            case Instruction.Convert c -> switch (c.op()) {
                case I32_TO_I64, I32_TO_F32, I32_TO_F64, I64_TO_I32, I64_TO_F32, I64_TO_F64,
                        F32_TO_I32, F32_TO_I64, F32_TO_F64, F64_TO_I32, F64_TO_I64, F64_TO_F32,
                        BOX_I32, BOX_I64, BOX_F32, BOX_F64, BOX_BOOL -> true;
                default -> false;
            };
            default -> false;
        };
    }
}
