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

    /**
     * Loads a named constant of a keyed type, such as {@code Material.DIAMOND}
     * ({@code key} {@code minecraft:diamond}). The linker resolves it once per load.
     */
    record KeyedConst(Register target, ClassType type, String key, long span) implements Instruction {
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

    /** Calls a script function of this module or of an imported one. */
    record Call(Register target, FunctionRef function, List<Register> arguments, long span) implements Instruction {
        public Call {
            arguments = List.copyOf(arguments);
        }

        public List<Register> operands() {
            return arguments;
        }
    }

    /**
     * Creates a function value: {@code function} with its first {@code captures.size()}
     * parameters bound to the current values of {@code captures} (a lambda), or with none (a
     * reference to a script function).
     */
    record NewClosure(Register target, FunctionRef function, List<Register> captures, long span)
            implements Instruction {
        public NewClosure {
            captures = List.copyOf(captures);
        }

        public List<Register> operands() {
            return captures;
        }

        @Override
        public boolean hasSideEffects() {
            return false;
        }
    }

    /** Calls a function value ({@code closure} has a function type); {@code target} is null for void. */
    record CallClosure(Register target, Register closure, List<Register> arguments, long span) implements Instruction {
        public CallClosure {
            arguments = List.copyOf(arguments);
        }

        public List<Register> operands() {
            List<Register> all = new ArrayList<>(arguments.size() + 1);
            all.add(closure);
            all.addAll(arguments);
            return all;
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

    /** Creates a map from boxed keys and values, in insertion order ({@code keys.size() == values.size()}). */
    record NewMap(Register target, List<Register> keys, List<Register> values, long span) implements Instruction {
        public NewMap {
            keys = List.copyOf(keys);
            values = List.copyOf(values);
            if (keys.size() != values.size()) {
                throw new IllegalArgumentException("A map needs as many keys as values");
            }
        }

        public List<Register> operands() {
            List<Register> all = new ArrayList<>(keys.size() * 2);
            for (int i = 0; i < keys.size(); i++) {
                all.add(keys.get(i));
                all.add(values.get(i));
            }
            return all;
        }

        @Override
        public boolean hasSideEffects() {
            return false;
        }
    }

    /** Creates a record value from its boxed fields, in declaration order. */
    record NewRecord(Register target, RecordRef record, List<Register> fields, long span) implements Instruction {
        public NewRecord {
            fields = List.copyOf(fields);
        }

        public List<Register> operands() {
            return fields;
        }

        @Override
        public boolean hasSideEffects() {
            return false;
        }
    }

    /** Reads field {@code index} of a record value (boxed). */
    record RecordGet(Register target, Register record, int index, long span) implements Instruction {
        public List<Register> operands() {
            return List.of(record);
        }

        @Override
        public boolean hasSideEffects() {
            return false;
        }
    }

    /** {@code target = operand is record} for a script-defined record type. */
    record RecordTest(Register target, Register operand, RecordRef record, long span) implements Instruction {
        public List<Register> operands() {
            return List.of(operand);
        }

        @Override
        public boolean hasSideEffects() {
            return false;
        }
    }

    /** Checked cast to a script-defined record type; a safe cast yields null instead of failing. */
    record RecordCast(Register target, Register operand, RecordRef record, boolean safe, long span) implements Instruction {
        public List<Register> operands() {
            return List.of(operand);
        }

        @Override
        public boolean hasSideEffects() {
            return !safe;
        }
    }

    /** Reads a top-level variable ({@code SCRIPT} or {@code PERSISTENT} storage). */
    record GlobalGet(Register target, GlobalRef global, long span) implements Instruction {
        public List<Register> operands() {
            return List.of();
        }
    }

    /** Writes a top-level variable ({@code SCRIPT} or {@code PERSISTENT} storage). */
    record GlobalSet(GlobalRef global, Register value, long span) implements Instruction {
        public Register target() {
            return null;
        }

        public List<Register> operands() {
            return List.of(value);
        }
    }

    /** Atomically adds {@code delta} to a numeric top-level variable (int, long or double). */
    record GlobalAdd(GlobalRef global, Register delta, long span) implements Instruction {
        public Register target() {
            return null;
        }

        public List<Register> operands() {
            return List.of(delta);
        }
    }

    /**
     * Loads the saved value of a {@code persistent var} into the variable; {@code target} (bool)
     * tells whether there was one. Used by module initializers only.
     */
    record GlobalRestore(Register target, GlobalRef global, long span) implements Instruction {
        public List<Register> operands() {
            return List.of();
        }
    }

    /** Reads the {@code playerdata var} {@code data} of the player {@code owner}. */
    record PlayerDataGet(Register target, GlobalRef data, Register owner, long span) implements Instruction {
        public List<Register> operands() {
            return List.of(owner);
        }
    }

    /** Writes the {@code playerdata var} {@code data} of the player {@code owner}. */
    record PlayerDataSet(GlobalRef data, Register owner, Register value, long span) implements Instruction {
        public Register target() {
            return null;
        }

        public List<Register> operands() {
            return List.of(owner, value);
        }
    }

    /** Atomically adds {@code delta} to a numeric {@code playerdata var} of the player {@code owner}. */
    record PlayerDataAdd(GlobalRef data, Register owner, Register delta, long span) implements Instruction {
        public Register target() {
            return null;
        }

        public List<Register> operands() {
            return List.of(owner, delta);
        }
    }

    /**
     * First instruction of an exception handler block: {@code target} (an {@code Error}) receives
     * the error that transferred control to the block. Emits no code.
     */
    record Catch(Register target, long span) implements Instruction {
        public List<Register> operands() {
            return List.of();
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
            case KeyedConst k -> k;
            case Move m -> new Move(m.target(), mapping.apply(m.source()), m.span());
            case Unary u -> new Unary(u.op(), u.target(), mapping.apply(u.operand()), u.span());
            case Binary b -> new Binary(b.op(), b.target(), mapping.apply(b.left()), mapping.apply(b.right()), b.span());
            case Convert c -> new Convert(c.op(), c.target(), mapping.apply(c.operand()), c.span());
            case InstanceOf i -> new InstanceOf(i.target(), mapping.apply(i.operand()), i.type(), i.span());
            case CheckCast c -> new CheckCast(c.target(), mapping.apply(c.operand()), c.type(), c.safe(), c.span());
            case CallNative c -> new CallNative(c.target(), c.function(), map(c.arguments(), mapping), c.span());
            case Call c -> new Call(c.target(), c.function(), map(c.arguments(), mapping), c.span());
            case NewClosure n -> new NewClosure(n.target(), n.function(), map(n.captures(), mapping), n.span());
            case CallClosure c -> new CallClosure(c.target(), mapping.apply(c.closure()), map(c.arguments(), mapping),
                    c.span());
            case Concat c -> new Concat(c.target(), map(c.parts(), mapping), c.span());
            case RenderTemplate t -> new RenderTemplate(t.target(), t.segments(), map(t.arguments(), mapping), t.span());
            case NewList n -> new NewList(n.target(), map(n.elements(), mapping), n.span());
            case ListGet g -> new ListGet(g.target(), mapping.apply(g.list()), mapping.apply(g.index()), g.span());
            case ListSet s -> new ListSet(mapping.apply(s.list()), mapping.apply(s.index()), mapping.apply(s.value()), s.span());
            case ListSize s -> new ListSize(s.target(), mapping.apply(s.list()), s.span());
            case ListAdd a -> new ListAdd(mapping.apply(a.list()), mapping.apply(a.value()), a.span());
            case ListContains c -> new ListContains(c.target(), mapping.apply(c.list()), mapping.apply(c.value()), c.span());
            case NewMap n -> new NewMap(n.target(), map(n.keys(), mapping), map(n.values(), mapping), n.span());
            case NewRecord n -> new NewRecord(n.target(), n.record(), map(n.fields(), mapping), n.span());
            case RecordGet g -> new RecordGet(g.target(), mapping.apply(g.record()), g.index(), g.span());
            case RecordTest t -> new RecordTest(t.target(), mapping.apply(t.operand()), t.record(), t.span());
            case RecordCast c -> new RecordCast(c.target(), mapping.apply(c.operand()), c.record(), c.safe(), c.span());
            case GlobalGet g -> g;
            case GlobalSet s -> new GlobalSet(s.global(), mapping.apply(s.value()), s.span());
            case GlobalAdd a -> new GlobalAdd(a.global(), mapping.apply(a.delta()), a.span());
            case GlobalRestore r -> r;
            case PlayerDataGet g -> new PlayerDataGet(g.target(), g.data(), mapping.apply(g.owner()), g.span());
            case PlayerDataSet s -> new PlayerDataSet(s.data(), mapping.apply(s.owner()), mapping.apply(s.value()), s.span());
            case PlayerDataAdd a -> new PlayerDataAdd(a.data(), mapping.apply(a.owner()), mapping.apply(a.delta()), a.span());
            case Catch c -> c;
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
