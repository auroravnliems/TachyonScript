package dev.tachyonscript.ir;

import java.util.List;
import java.util.StringJoiner;

/**
 * Human-readable IR dump, used by tests and by the {@code dump-ir} debug commands:
 *
 * <pre>
 * function reward(Player, int): void  [function reward]
 *   params: r0:Player r1:int
 *   B0:
 *     r2:Component = template "Hello " r3 "!"
 *     call Player.send(Component)(r0, r2)
 *     return
 * </pre>
 */
public final class IrPrinter {

    private IrPrinter() {
    }

    public static String print(IrModule module) {
        StringBuilder out = new StringBuilder("module ").append(module.name()).append('\n');
        for (IrModule.EventHandler handler : module.handlers()) {
            out.append("  on ").append(handler.event().name()).append(" -> ").append(handler.function()).append('\n');
        }
        for (IrFunction function : module.functions()) {
            out.append('\n').append(print(function));
        }
        return out.toString();
    }

    public static String print(IrFunction function) {
        StringBuilder out = new StringBuilder("function ").append(function.key()).append(": ")
                .append(function.returnType().displayName()).append("  [").append(function.displayName()).append("]\n");
        if (!function.parameters().isEmpty()) {
            out.append("  params:");
            for (Register parameter : function.parameters()) {
                out.append(' ').append(declare(parameter));
            }
            out.append('\n');
        }
        for (IrBlock block : function.blocks()) {
            out.append("  B").append(block.index()).append(":\n");
            for (Instruction instruction : block.instructions()) {
                out.append("    ").append(print(instruction)).append('\n');
            }
            out.append("    ").append(print(block.terminator())).append('\n');
        }
        return out.toString();
    }

    public static String print(Instruction instruction) {
        String body = switch (instruction) {
            case Instruction.Const c -> constant(c.value());
            case Instruction.Move m -> m.source().toString();
            case Instruction.Unary u -> u.op().name().toLowerCase() + " " + u.operand();
            case Instruction.Binary b -> b.op().name().toLowerCase() + " " + b.left() + ", " + b.right();
            case Instruction.Convert c -> c.op().name().toLowerCase() + " " + c.operand();
            case Instruction.InstanceOf i -> "instanceof " + i.operand() + " " + i.type().name();
            case Instruction.CheckCast c -> (c.safe() ? "safe_cast " : "cast ") + c.operand() + " " + c.type().name();
            case Instruction.CallNative c -> "call " + c.function().key() + args(c.arguments());
            case Instruction.Call c -> "call_script " + c.function() + args(c.arguments());
            case Instruction.Concat c -> "concat" + args(c.parts());
            case Instruction.RenderTemplate t -> {
                StringBuilder out = new StringBuilder("template ").append(constant(t.segments().getFirst()));
                for (int i = 0; i < t.arguments().size(); i++) {
                    out.append(' ').append(t.arguments().get(i)).append(' ').append(constant(t.segments().get(i + 1)));
                }
                yield out.toString();
            }
            case Instruction.NewList n -> "new_list" + args(n.elements());
            case Instruction.ListGet g -> "list_get " + g.list() + ", " + g.index();
            case Instruction.ListSet s -> "list_set " + s.list() + ", " + s.index() + ", " + s.value();
            case Instruction.ListSize s -> "list_size " + s.list();
            case Instruction.ListAdd a -> "list_add " + a.list() + ", " + a.value();
            case Instruction.ListContains c -> "list_contains " + c.list() + ", " + c.value();
        };
        Register target = instruction.target();
        return target == null ? body : declare(target) + " = " + body;
    }

    public static String print(Terminator terminator) {
        return switch (terminator) {
            case Terminator.Jump j -> (j.backEdge() ? "loop B" : "jump B") + j.target();
            case Terminator.Branch b -> "branch " + b.condition() + " ? B" + b.ifTrue() + " : B" + b.ifFalse();
            case Terminator.Return r -> r.value() == null ? "return" : "return " + r.value();
            case Terminator.Unreachable ignored -> "unreachable";
        };
    }

    private static String declare(Register register) {
        return register + ":" + register.type().displayName();
    }

    private static String args(List<Register> registers) {
        StringJoiner joiner = new StringJoiner(", ", "(", ")");
        registers.forEach(register -> joiner.add(register.toString()));
        return joiner.toString();
    }

    private static String constant(Object value) {
        return switch (value) {
            case null -> "null";
            case String text -> '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
            case Long l -> l + "L";
            case Float f -> f + "f";
            default -> String.valueOf(value);
        };
    }
}
