package dev.tachyonscript.compiler;

import dev.tachyonscript.api.type.NullType;
import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.ir.BinaryOp;
import dev.tachyonscript.ir.ConvertOp;
import dev.tachyonscript.ir.FunctionBuilder;
import dev.tachyonscript.ir.Instruction;
import dev.tachyonscript.ir.Register;
import dev.tachyonscript.ir.Spans;
import dev.tachyonscript.ir.Terminator;
import dev.tachyonscript.ir.UnaryOp;
import dev.tachyonscript.language.semantic.ArithmeticOp;
import dev.tachyonscript.language.semantic.BoundExpression;
import dev.tachyonscript.language.semantic.BoundStatement;
import dev.tachyonscript.language.semantic.ComparisonOp;
import dev.tachyonscript.language.semantic.LocalSymbol;
import dev.tachyonscript.language.source.Span;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lowers the body of one bound function or event handler to IR.
 *
 * <p>Locals map to virtual registers. Expressions are emitted in evaluation order; when a
 * destination register is known (a local being initialized or assigned), the final
 * instruction of the expression writes it directly instead of going through a temporary.
 * Conditions are lowered straight to branches, so {@code &&}, {@code ||} and {@code !}
 * never materialize intermediate booleans.
 */
final class FunctionLowering {

    private record Loop(int breakBlock, int continueBlock, boolean continueIsBackEdge) {
    }

    private final FunctionBuilder builder;
    private final Map<LocalSymbol, Register> locals = new IdentityHashMap<>();
    private final Deque<Loop> loops = new ArrayDeque<>();

    FunctionLowering(FunctionBuilder builder) {
        this.builder = builder;
    }

    void bind(LocalSymbol local, Register register) {
        locals.put(local, register);
    }

    // ================================================================= statements

    void statement(BoundStatement statement) {
        switch (statement) {
            case BoundStatement.Block block -> block.statements().forEach(this::statement);
            case BoundStatement.LocalDeclaration declaration -> {
                Register register = builder.register(declaration.local().type(), declaration.local().name());
                locals.put(declaration.local(), register);
                emit(declaration.initializer(), register);
            }
            case BoundStatement.LocalAssignment assignment -> emit(assignment.value(), local(assignment.local()));
            case BoundStatement.PropertyAssignment assignment -> {
                List<Register> arguments = new ArrayList<>(2);
                if (assignment.receiver() != null) {
                    arguments.add(value(assignment.receiver()));
                }
                arguments.add(value(assignment.value()));
                builder.emit(new Instruction.CallNative(null, assignment.setter(), arguments, span(assignment.span())));
            }
            case BoundStatement.ListSet set -> {
                Register list = value(set.list());
                Register index = value(set.index());
                Register element = box(value(set.value()), set.span());
                builder.emit(new Instruction.ListSet(list, index, element, span(set.span())));
            }
            case BoundStatement.ExpressionStatement expression -> discard(expression.expression());
            case BoundStatement.If anIf -> lowerIf(anIf);
            case BoundStatement.While loop -> lowerWhile(loop);
            case BoundStatement.ForRange loop -> lowerForRange(loop);
            case BoundStatement.ForEach loop -> lowerForEach(loop);
            case BoundStatement.Return ret -> builder.terminate(new Terminator.Return(
                    ret.value() == null ? null : value(ret.value()), span(ret.span())));
            case BoundStatement.Break brk -> builder.terminate(new Terminator.Jump(loops.peek().breakBlock(), false,
                    span(brk.span())));
            case BoundStatement.Continue cont -> {
                Loop loop = loops.peek();
                builder.terminate(new Terminator.Jump(loop.continueBlock(), loop.continueIsBackEdge(), span(cont.span())));
            }
        }
    }

    private void lowerIf(BoundStatement.If statement) {
        int thenBlock = builder.newBlock();
        int elseBlock = statement.elseBranch() != null ? builder.newBlock() : -1;
        int join = builder.newBlock();
        condition(statement.condition(), thenBlock, elseBlock >= 0 ? elseBlock : join);
        builder.switchTo(thenBlock);
        statement(statement.thenBranch());
        builder.jumpIfOpen(join, span(statement.span()));
        if (elseBlock >= 0) {
            builder.switchTo(elseBlock);
            statement(statement.elseBranch());
            builder.jumpIfOpen(join, span(statement.span()));
        }
        builder.switchTo(join);
    }

    private void lowerWhile(BoundStatement.While loop) {
        long span = span(loop.span());
        int header = builder.newBlock();
        int body = builder.newBlock();
        int exit = builder.newBlock();
        builder.terminate(new Terminator.Jump(header, false, span));
        builder.switchTo(header);
        condition(loop.condition(), body, exit);
        builder.switchTo(body);
        loops.push(new Loop(exit, header, true));
        statement(loop.body());
        loops.pop();
        if (!builder.isTerminated()) {
            builder.terminate(new Terminator.Jump(header, true, span));
        }
        builder.switchTo(exit);
    }

    /**
     * {@code for i in start..end}: bounds are evaluated once; the inclusive form tests for
     * the last value before incrementing so that a range ending at the maximum int never
     * overflows into an endless loop.
     */
    private void lowerForRange(BoundStatement.ForRange loop) {
        long span = span(loop.span());
        boolean isLong = loop.kind() == Representation.LONG;
        Type type = isLong ? PrimitiveType.LONG : PrimitiveType.INT;
        Register counter = builder.register(loop.variable().type().representation() == type.representation()
                ? loop.variable().type() : type, loop.variable().name());
        locals.put(loop.variable(), counter);
        emit(loop.start(), counter);
        Register end = builder.temp(type);
        emit(loop.end(), end);
        int header = builder.newBlock();
        int body = builder.newBlock();
        int step = builder.newBlock();
        int exit = builder.newBlock();
        builder.terminate(new Terminator.Jump(header, false, span));

        builder.switchTo(header);
        Register inRange = builder.temp(PrimitiveType.BOOL);
        BinaryOp test = loop.inclusive() ? (isLong ? BinaryOp.LE_I64 : BinaryOp.LE_I32) : (isLong ? BinaryOp.LT_I64 : BinaryOp.LT_I32);
        builder.emit(new Instruction.Binary(test, inRange, counter, end, span));
        builder.terminate(new Terminator.Branch(inRange, body, exit, span));

        builder.switchTo(body);
        loops.push(new Loop(exit, step, false));
        statement(loop.body());
        loops.pop();
        builder.jumpIfOpen(step, span);

        builder.switchTo(step);
        if (loop.inclusive()) {
            Register last = builder.temp(PrimitiveType.BOOL);
            builder.emit(new Instruction.Binary(isLong ? BinaryOp.EQ_I64 : BinaryOp.EQ_I32, last, counter, end, span));
            int increment = builder.newBlock();
            builder.terminate(new Terminator.Branch(last, exit, increment, span));
            builder.switchTo(increment);
        }
        Register one = builder.temp(type);
        builder.emit(new Instruction.Const(one, isLong ? (Object) 1L : (Object) 1, span));
        builder.emit(new Instruction.Binary(isLong ? BinaryOp.ADD_I64 : BinaryOp.ADD_I32, counter, counter, one, span));
        builder.terminate(new Terminator.Jump(header, true, span));
        builder.switchTo(exit);
    }

    /** {@code for x in list}: index-based iteration, no iterator allocation. */
    private void lowerForEach(BoundStatement.ForEach loop) {
        long span = span(loop.span());
        Register list = builder.temp(loop.iterable().type());
        emit(loop.iterable(), list);
        Register index = builder.temp(PrimitiveType.INT);
        builder.emit(new Instruction.Const(index, 0, span));
        Register variable = builder.register(loop.variable().type(), loop.variable().name());
        locals.put(loop.variable(), variable);
        int header = builder.newBlock();
        int body = builder.newBlock();
        int step = builder.newBlock();
        int exit = builder.newBlock();
        builder.terminate(new Terminator.Jump(header, false, span));

        builder.switchTo(header);
        Register size = builder.temp(PrimitiveType.INT);
        builder.emit(new Instruction.ListSize(size, list, span));
        Register more = builder.temp(PrimitiveType.BOOL);
        builder.emit(new Instruction.Binary(BinaryOp.LT_I32, more, index, size, span));
        builder.terminate(new Terminator.Branch(more, body, exit, span));

        builder.switchTo(body);
        Type element = loop.variable().type();
        if (element.representation().isPrimitive()) {
            Register boxed = builder.temp(Types.nullable(element));
            builder.emit(new Instruction.ListGet(boxed, list, index, span));
            builder.emit(new Instruction.Convert(ConvertOp.unbox(element.representation()), variable, boxed, span));
        } else {
            builder.emit(new Instruction.ListGet(variable, list, index, span));
        }
        loops.push(new Loop(exit, step, false));
        statement(loop.body());
        loops.pop();
        builder.jumpIfOpen(step, span);

        builder.switchTo(step);
        Register one = builder.temp(PrimitiveType.INT);
        builder.emit(new Instruction.Const(one, 1, span));
        builder.emit(new Instruction.Binary(BinaryOp.ADD_I32, index, index, one, span));
        builder.terminate(new Terminator.Jump(header, true, span));
        builder.switchTo(exit);
    }

    // ================================================================= conditions

    /** Lowers a boolean expression directly to control flow. */
    void condition(BoundExpression expression, int ifTrue, int ifFalse) {
        switch (expression) {
            case BoundExpression.Logical logical -> {
                int middle = builder.newBlock();
                if (logical.and()) {
                    condition(logical.left(), middle, ifFalse);
                } else {
                    condition(logical.left(), ifTrue, middle);
                }
                builder.switchTo(middle);
                condition(logical.right(), ifTrue, ifFalse);
            }
            case BoundExpression.Not not -> condition(not.operand(), ifFalse, ifTrue);
            case BoundExpression.Literal literal when literal.value() instanceof Boolean constant ->
                    builder.terminate(new Terminator.Jump(constant ? ifTrue : ifFalse, false, span(literal.span())));
            default -> {
                Register value = value(expression);
                builder.terminate(new Terminator.Branch(value, ifTrue, ifFalse, span(expression.span())));
            }
        }
    }

    // ================================================================= expressions

    /** Evaluates {@code expression} into some register. */
    Register value(BoundExpression expression) {
        return emit(expression, null);
    }

    /** Evaluates an expression whose value is not needed. */
    private void discard(BoundExpression expression) {
        switch (expression) {
            case BoundExpression.NativeCall call -> builder.emit(new Instruction.CallNative(null, call.target(),
                    values(call.arguments()), span(call.span())));
            case BoundExpression.FunctionCall call -> builder.emit(new Instruction.Call(null, call.function().key(),
                    values(call.arguments()), span(call.span())));
            default -> emit(expression, null);
        }
    }

    /**
     * Evaluates {@code expression}. When {@code destination} is non-null the value ends up in
     * it (preferably written by the expression's final instruction); returns the register
     * holding the value, or {@code null} for void expressions.
     */
    private Register emit(BoundExpression expression, Register destination) {
        long span = span(expression.span());
        return switch (expression) {
            case BoundExpression.Literal literal -> {
                Register target = target(destination, literal.type());
                builder.emit(new Instruction.Const(target, literal.value(), span));
                yield target;
            }
            case BoundExpression.LocalLoad load -> move(local(load.local()), destination, span);
            case BoundExpression.NativeCall call -> {
                List<Register> arguments = values(call.arguments());
                Register target = call.type() == PrimitiveType.VOID ? null : target(destination, call.type());
                builder.emit(new Instruction.CallNative(target, call.target(), arguments, span));
                yield target;
            }
            case BoundExpression.FunctionCall call -> {
                List<Register> arguments = values(call.arguments());
                Register target = call.type() == PrimitiveType.VOID ? null : target(destination, call.type());
                builder.emit(new Instruction.Call(target, call.function().key(), arguments, span));
                yield target;
            }
            case BoundExpression.Arithmetic arithmetic -> {
                Register left = value(arithmetic.left());
                Register right = value(arithmetic.right());
                Register target = target(destination, arithmetic.type());
                builder.emit(new Instruction.Binary(arithmeticOp(arithmetic.op(), arithmetic.kind()), target, left, right, span));
                yield target;
            }
            case BoundExpression.Negate negate -> {
                Register operand = value(negate.operand());
                Register target = target(destination, negate.type());
                UnaryOp op = switch (negate.kind()) {
                    case INT -> UnaryOp.NEG_I32;
                    case LONG -> UnaryOp.NEG_I64;
                    case FLOAT -> UnaryOp.NEG_F32;
                    default -> UnaryOp.NEG_F64;
                };
                builder.emit(new Instruction.Unary(op, target, operand, span));
                yield target;
            }
            case BoundExpression.Not not -> {
                Register operand = value(not.operand());
                Register target = target(destination, PrimitiveType.BOOL);
                builder.emit(new Instruction.Unary(UnaryOp.NOT, target, operand, span));
                yield target;
            }
            case BoundExpression.Compare compare -> {
                Register left = value(compare.left());
                Register right = value(compare.right());
                Register target = target(destination, PrimitiveType.BOOL);
                builder.emit(new Instruction.Binary(comparisonOp(compare.op(), compare.kind()), target, left, right, span));
                yield target;
            }
            case BoundExpression.NullCheck check -> {
                Register operand = value(check.operand());
                Register target = target(destination, PrimitiveType.BOOL);
                builder.emit(new Instruction.Unary(check.isNull() ? UnaryOp.IS_NULL : UnaryOp.IS_NOT_NULL, target, operand, span));
                yield target;
            }
            case BoundExpression.Logical logical -> {
                Register result = builder.temp(PrimitiveType.BOOL);
                int whenTrue = builder.newBlock();
                int whenFalse = builder.newBlock();
                int join = builder.newBlock();
                condition(logical, whenTrue, whenFalse);
                builder.switchTo(whenTrue);
                builder.emit(new Instruction.Const(result, true, span));
                builder.terminate(new Terminator.Jump(join, false, span));
                builder.switchTo(whenFalse);
                builder.emit(new Instruction.Const(result, false, span));
                builder.terminate(new Terminator.Jump(join, false, span));
                builder.switchTo(join);
                yield move(result, destination, span);
            }
            case BoundExpression.Concat concat -> {
                List<Register> parts = values(concat.parts());
                Register target = target(destination, Types.STRING);
                builder.emit(new Instruction.Concat(target, parts, span));
                yield target;
            }
            case BoundExpression.ComponentTemplate template -> {
                List<Register> arguments = values(template.arguments());
                Register target = target(destination, Types.COMPONENT);
                builder.emit(new Instruction.RenderTemplate(target, template.segments(), arguments, span));
                yield target;
            }
            case BoundExpression.Conversion conversion -> conversion(conversion, destination, span);
            case BoundExpression.SafeAccess access -> safeAccess(access, destination, span);
            case BoundExpression.Coalesce coalesce -> coalesce(coalesce, destination, span);
            case BoundExpression.TypeTest test -> {
                Register operand = value(test.operand());
                Register target = target(destination, PrimitiveType.BOOL);
                builder.emit(new Instruction.InstanceOf(target, operand, test.target(), span));
                yield target;
            }
            case BoundExpression.Cast cast -> {
                Register operand = value(cast.operand());
                Register target = target(destination, cast.type());
                builder.emit(new Instruction.CheckCast(target, operand, cast.target(), cast.safe(), span));
                yield target;
            }
            case BoundExpression.ListLiteral list -> {
                List<Register> elements = new ArrayList<>();
                for (BoundExpression element : list.elements()) {
                    elements.add(box(value(element), list.span()));
                }
                Register target = target(destination, list.type());
                builder.emit(new Instruction.NewList(target, elements, span));
                yield target;
            }
            case BoundExpression.ListGet get -> {
                Register list = value(get.list());
                Register index = value(get.index());
                if (get.type().representation().isPrimitive()) {
                    Register boxed = builder.temp(Types.nullable(get.type()));
                    builder.emit(new Instruction.ListGet(boxed, list, index, span));
                    Register target = target(destination, get.type());
                    builder.emit(new Instruction.Convert(ConvertOp.unbox(get.type().representation()), target, boxed, span));
                    yield target;
                }
                Register target = target(destination, get.type());
                builder.emit(new Instruction.ListGet(target, list, index, span));
                yield target;
            }
            case BoundExpression.ListSize size -> {
                Register list = value(size.list());
                Register target = target(destination, PrimitiveType.INT);
                builder.emit(new Instruction.ListSize(target, list, span));
                yield target;
            }
            case BoundExpression.ListContains contains -> {
                Register list = value(contains.list());
                Register element = box(value(contains.element()), contains.span());
                Register target = target(destination, PrimitiveType.BOOL);
                builder.emit(new Instruction.ListContains(target, list, element, span));
                yield target;
            }
            case BoundExpression.ListAdd add -> {
                Register list = value(add.list());
                Register element = box(value(add.element()), add.span());
                builder.emit(new Instruction.ListAdd(list, element, span));
                yield null;
            }
            case BoundExpression.Error error ->
                    throw new IllegalStateException("Erroneous expression reached lowering at " + error.span());
        };
    }

    private Register conversion(BoundExpression.Conversion conversion, Register destination, long span) {
        BoundExpression operandExpression = conversion.operand();
        Type from = operandExpression.type();
        Type to = conversion.type();
        Register operand = value(operandExpression);
        ConvertOp op = switch (conversion.kind()) {
            case NUMERIC -> ConvertOp.numeric(from.representation(), to.representation());
            case BOX -> ConvertOp.box(from.representation());
            case UNBOX -> ConvertOp.unbox(to.representation());
            case STRING_TO_COMPONENT -> ConvertOp.STRING_TO_COMPONENT;
            case TO_STRING -> {
                if (from == PrimitiveType.DURATION) {
                    yield ConvertOp.DURATION_TO_STRING;
                }
                if (from.nonNullable() == PrimitiveType.DURATION) {
                    yield null; // handled below: nullable durations need a null check
                }
                yield switch (from.representation()) {
                    case INT -> ConvertOp.I32_TO_STRING;
                    case LONG -> ConvertOp.I64_TO_STRING;
                    case FLOAT -> ConvertOp.F32_TO_STRING;
                    case DOUBLE -> ConvertOp.F64_TO_STRING;
                    case BOOL -> ConvertOp.BOOL_TO_STRING;
                    default -> ConvertOp.REF_TO_STRING;
                };
            }
        };
        if (conversion.kind() == dev.tachyonscript.language.semantic.ConversionKind.TO_STRING && op == null) {
            return nullableDurationText(operand, destination, span);
        }
        if (op == null) {
            // Same representation (for example int -> Duration arithmetic operands): no operation.
            return move(operand, destination, span);
        }
        Register target = target(destination, to);
        builder.emit(new Instruction.Convert(op, target, operand, span));
        return target;
    }

    private Register nullableDurationText(Register boxed, Register destination, long span) {
        Register result = builder.temp(Types.STRING);
        Register present = builder.temp(PrimitiveType.BOOL);
        builder.emit(new Instruction.Unary(UnaryOp.IS_NOT_NULL, present, boxed, span));
        int some = builder.newBlock();
        int none = builder.newBlock();
        int join = builder.newBlock();
        builder.terminate(new Terminator.Branch(present, some, none, span));
        builder.switchTo(some);
        Register millis = builder.temp(PrimitiveType.DURATION);
        builder.emit(new Instruction.Convert(ConvertOp.UNBOX_I64, millis, boxed, span));
        builder.emit(new Instruction.Convert(ConvertOp.DURATION_TO_STRING, result, millis, span));
        builder.terminate(new Terminator.Jump(join, false, span));
        builder.switchTo(none);
        builder.emit(new Instruction.Const(result, "null", span));
        builder.terminate(new Terminator.Jump(join, false, span));
        builder.switchTo(join);
        return move(result, destination, span);
    }

    private Register safeAccess(BoundExpression.SafeAccess access, Register destination, long span) {
        Register receiver = value(access.receiver());
        locals.put(access.temporary(), receiver);
        Register present = builder.temp(PrimitiveType.BOOL);
        builder.emit(new Instruction.Unary(UnaryOp.IS_NOT_NULL, present, receiver, span));
        int some = builder.newBlock();
        int join = builder.newBlock();
        if (access.type() == PrimitiveType.VOID) {
            builder.terminate(new Terminator.Branch(present, some, join, span));
            builder.switchTo(some);
            discard(access.access());
            builder.jumpIfOpen(join, span);
            builder.switchTo(join);
            return null;
        }
        int none = builder.newBlock();
        Register result = builder.temp(access.type());
        builder.terminate(new Terminator.Branch(present, some, none, span));
        builder.switchTo(some);
        emit(access.access(), result);
        builder.jumpIfOpen(join, span);
        builder.switchTo(none);
        builder.emit(new Instruction.Const(result, null, span));
        builder.terminate(new Terminator.Jump(join, false, span));
        builder.switchTo(join);
        return move(result, destination, span);
    }

    private Register coalesce(BoundExpression.Coalesce coalesce, Register destination, long span) {
        Register left = value(coalesce.left());
        locals.put(coalesce.temporary(), left);
        Register present = builder.temp(PrimitiveType.BOOL);
        builder.emit(new Instruction.Unary(UnaryOp.IS_NOT_NULL, present, left, span));
        int some = builder.newBlock();
        int none = builder.newBlock();
        int join = builder.newBlock();
        Register result = builder.temp(coalesce.type());
        builder.terminate(new Terminator.Branch(present, some, none, span));
        builder.switchTo(some);
        emit(coalesce.nonNullValue(), result);
        builder.jumpIfOpen(join, span);
        builder.switchTo(none);
        emit(coalesce.fallback(), result);
        builder.jumpIfOpen(join, span);
        builder.switchTo(join);
        return move(result, destination, span);
    }

    // ================================================================= helpers

    private List<Register> values(List<BoundExpression> expressions) {
        List<Register> registers = new ArrayList<>(expressions.size());
        for (BoundExpression expression : expressions) {
            registers.add(value(expression));
        }
        return registers;
    }

    private Register local(LocalSymbol symbol) {
        Register register = locals.get(symbol);
        if (register == null) {
            throw new IllegalStateException("Local '" + symbol.name() + "' has no register");
        }
        return register;
    }

    private Register target(Register destination, Type type) {
        if (destination != null) {
            return destination;
        }
        return builder.temp(type instanceof NullType ? Types.nullable(Types.ANY) : type);
    }

    private Register move(Register source, Register destination, long span) {
        if (destination == null || destination.equals(source)) {
            return source;
        }
        builder.emit(new Instruction.Move(destination, source, span));
        return destination;
    }

    /** Boxes primitive values stored in lists. */
    private Register box(Register value, Span span) {
        if (!value.kind().isPrimitive()) {
            return value;
        }
        Register boxed = builder.temp(Types.nullable(value.type()));
        builder.emit(new Instruction.Convert(ConvertOp.box(value.kind()), boxed, value, span(span)));
        return boxed;
    }

    static long span(Span span) {
        return Spans.of(span.start(), span.end());
    }

    private static BinaryOp arithmeticOp(ArithmeticOp op, Representation kind) {
        String suffix = suffix(kind);
        return BinaryOp.valueOf(switch (op) {
            case ADD -> "ADD";
            case SUBTRACT -> "SUB";
            case MULTIPLY -> "MUL";
            case DIVIDE -> "DIV";
            case REMAINDER -> "REM";
        } + "_" + suffix);
    }

    private static BinaryOp comparisonOp(ComparisonOp op, Representation kind) {
        return BinaryOp.valueOf(switch (op) {
            case EQUAL -> "EQ";
            case NOT_EQUAL -> "NE";
            case LESS -> "LT";
            case LESS_EQUAL -> "LE";
            case GREATER -> "GT";
            case GREATER_EQUAL -> "GE";
        } + "_" + suffix(kind));
    }

    private static String suffix(Representation kind) {
        return switch (kind) {
            case INT -> "I32";
            case LONG -> "I64";
            case FLOAT -> "F32";
            case DOUBLE -> "F64";
            case BOOL -> "BOOL";
            case REF -> "REF";
            case VOID -> throw new IllegalArgumentException("void operand");
        };
    }
}
