package dev.tachyonscript.ir;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.type.ClassType;

import java.util.ArrayList;
import java.util.List;

/**
 * A non-terminating IR instruction in three-address form: it reads registers, performs one
 * specialised operation and writes at most one register ({@link #target()}). Every
 * instruction carries a packed source span ({@link Spans}).
 */
public sealed interface Instruction {

    /** Written register, or {@code null}. */
    Register target();

    /** Read registers, in evaluation order. */
    List<Register> operands();

    /** Packed source span, or {@link Spans#NONE}. */
    long span();

    /** Whether the instruction has effects besides writing its target (calls, mutations, failures). */
    default boolean hasSideEffects() {
        return true;
    }

    /**
     * Loads a constant. {@code value} is an {@code Integer}, {@code Long}, {@code Float},
     * {@code Double}, {@code Boolean}, {@code String} or {@code null}, matching the target kind.
     */
    record Const(Register target, Object value, long span) implements Instruction {
        public List<Register> operands() {
            return List.of();
        }

        @Override
        public boolean hasSideEffects() {
            return false;
        }
    }

    record Move(Register target, Register source, long span) implements Instruction {
        public List<Register> operands() {
            return List.of(source);
        }

        @Override
        public boolean hasSideEffects() {
            return false;
        }
    }

    record Unary(UnaryOp op, Register target, Register operand, long span) implements Instruction {
        public List<Register> operands() {
            return List.of(operand);
        }

        @Override
        public boolean hasSideEffects() {
            return false;
        }
    }

    record Binary(BinaryOp op, Register target, Register left, Register right, long span) implements Instruction {
        public List<Register> operands() {
            return List.of(left, right);
        }

        @Override
        public boolean hasSideEffects() {
            return op.canFail();
        }
    }

    record Convert(ConvertOp op, Register target, Register operand, long span) implements Instruction {
        public List<Register> operands() {
            return List.of(operand);
        }

        @Override
        public boolean hasSideEffects() {
            // Unboxing null and MiniMessage parsing can fail at runtime.
            return op == ConvertOp.STRING_TO_COMPONENT || op.name().startsWith("UNBOX");
        }
    }

    /** {@code target = operand is type} */
    record InstanceOf(Register target, Register operand, ClassType type, long span) implements Instruction {
        public List<Register> operands() {
            return List.of(operand);
        }

        @Override
        public boolean hasSideEffects() {
            return false;
        }
    }

    /** Checked cast; a safe cast yields null instead of failing. Null operands stay null for safe casts. */
    record CheckCast(Register target, Register operand, ClassType type, boolean safe, long span) implements Instruction {
        public List<Register> operands() {
            return List.of(operand);
        }

        @Override
        public boolean hasSideEffects() {
            return !safe;
        }
    }

    /** Calls a host operation; {@code target} is null for void results or discarded values. */
    record CallNative(Register target, NativeDeclaration function, List<Register> arguments, long span)
            implements Instruction {
        public CallNative {
            arguments = List.copyOf(arguments);
        }

        public List<Register> operands() {
            return arguments;
        }
    }

    /** Calls a script function of the same module by its key. */
    record Call(Register target, String function, List<Register> arguments, long span) implements Instruction {
        public Call {
            arguments = List.copyOf(arguments);
        }

        public List<Register> operands() {
            return arguments;
        }
    }

    /** Concatenates string registers. */
    record Concat(Register target, List<Register> parts, long span) implements Instruction {
        public Concat {
            parts = List.copyOf(parts);
        }

        public List<Register> operands() {
            return parts;
        }

        @Override
        public boolean hasSideEffects() {
            return false;
        }
    }

    /**
     * Renders a message template: {@code segments} are MiniMessage text around the arguments,
     * which are strings (inserted as plain text) or components.
     */
    record RenderTemplate(Register target, List<String> segments, List<Register> arguments, long span)
            implements Instruction {
        public RenderTemplate {
            segments = List.copyOf(segments);
            arguments = List.copyOf(arguments);
        }

        public List<Register> operands() {
            return arguments;
        }

        @Override
        public boolean hasSideEffects() {
            return false;
        }
    }

    record NewList(Register target, List<Register> elements, long span) implements Instruction {
        public NewList {
            elements = List.copyOf(elements);
        }

        public List<Register> operands() {
            return elements;
        }

        @Override
        public boolean hasSideEffects() {
            return false;
        }
    }

    record ListGet(Register target, Register list, Register index, long span) implements Instruction {
        public List<Register> operands() {
            return List.of(list, index);
        }
    }

    record ListSet(Register list, Register index, Register value, long span) implements Instruction {
        public Register target() {
            return null;
        }

        public List<Register> operands() {
            return List.of(list, index, value);
        }
    }

    record ListSize(Register target, Register list, long span) implements Instruction {
        public List<Register> operands() {
            return List.of(list);
        }

        @Override
        public boolean hasSideEffects() {
            return false;
        }
    }

    record ListAdd(Register list, Register value, long span) implements Instruction {
        public Register target() {
            return null;
        }

        public List<Register> operands() {
            return List.of(list, value);
        }
    }

    record ListContains(Register target, Register list, Register value, long span) implements Instruction {
        public List<Register> operands() {
            return List.of(list, value);
        }

        @Override
        public boolean hasSideEffects() {
            return false;
        }
    }

    /** Returns a copy of {@code instruction} whose operands are replaced through {@code mapping}. */
    static Instruction mapOperands(Instruction instruction, java.util.function.UnaryOperator<Register> mapping) {
        return switch (instruction) {
            case Const c -> c;
            case Move m -> new Move(m.target(), mapping.apply(m.source()), m.span());
            case Unary u -> new Unary(u.op(), u.target(), mapping.apply(u.operand()), u.span());
            case Binary b -> new Binary(b.op(), b.target(), mapping.apply(b.left()), mapping.apply(b.right()), b.span());
            case Convert c -> new Convert(c.op(), c.target(), mapping.apply(c.operand()), c.span());
            case InstanceOf i -> new InstanceOf(i.target(), mapping.apply(i.operand()), i.type(), i.span());
            case CheckCast c -> new CheckCast(c.target(), mapping.apply(c.operand()), c.type(), c.safe(), c.span());
            case CallNative c -> new CallNative(c.target(), c.function(), map(c.arguments(), mapping), c.span());
            case Call c -> new Call(c.target(), c.function(), map(c.arguments(), mapping), c.span());
            case Concat c -> new Concat(c.target(), map(c.parts(), mapping), c.span());
            case RenderTemplate t -> new RenderTemplate(t.target(), t.segments(), map(t.arguments(), mapping), t.span());
            case NewList n -> new NewList(n.target(), map(n.elements(), mapping), n.span());
            case ListGet g -> new ListGet(g.target(), mapping.apply(g.list()), mapping.apply(g.index()), g.span());
            case ListSet s -> new ListSet(mapping.apply(s.list()), mapping.apply(s.index()), mapping.apply(s.value()), s.span());
            case ListSize s -> new ListSize(s.target(), mapping.apply(s.list()), s.span());
            case ListAdd a -> new ListAdd(mapping.apply(a.list()), mapping.apply(a.value()), a.span());
            case ListContains c -> new ListContains(c.target(), mapping.apply(c.list()), mapping.apply(c.value()), c.span());
        };
    }

    private static List<Register> map(List<Register> registers, java.util.function.UnaryOperator<Register> mapping) {
        List<Register> mapped = new ArrayList<>(registers.size());
        for (Register register : registers) {
            mapped.add(mapping.apply(register));
        }
        return mapped;
    }
}
