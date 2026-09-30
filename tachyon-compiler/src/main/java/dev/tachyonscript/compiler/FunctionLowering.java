package dev.tachyonscript.compiler;

import dev.tachyonscript.api.type.FunctionType;
import dev.tachyonscript.api.type.NullType;
import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.ir.BinaryOp;
import dev.tachyonscript.ir.ConvertOp;
import dev.tachyonscript.ir.FunctionBuilder;
import dev.tachyonscript.ir.FunctionRef;
import dev.tachyonscript.ir.Instruction;
import dev.tachyonscript.ir.Register;
import dev.tachyonscript.ir.Spans;
import dev.tachyonscript.ir.Terminator;
import dev.tachyonscript.ir.UnaryOp;
import dev.tachyonscript.language.semantic.ArithmeticOp;
import dev.tachyonscript.language.semantic.BoundExpression;
import dev.tachyonscript.language.semantic.BoundStatement;
import dev.tachyonscript.language.semantic.ComparisonOp;
import dev.tachyonscript.language.semantic.ConversionKind;
import dev.tachyonscript.language.semantic.LocalSymbol;
import dev.tachyonscript.language.semantic.RecordSymbol;
import dev.tachyonscript.language.source.Span;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lowers the body of one bound function, event handler, command, lambda or initializer to IR.
 *
 * <p>Locals map to virtual registers. Expressions are emitted in evaluation order; when a
 * destination register is known (a local being initialized or assigned), the final
 * instruction of the expression writes it directly instead of going through a temporary.
 * Conditions are lowered straight to branches, so {@code &&}, {@code ||} and {@code !}
 * never materialize intermediate booleans.
 *
 * <p><b>Errors.</b> {@code try} blocks give their blocks an exception handler (a block starting
 * with {@link Instruction.Catch}). A {@code finally} block is copied onto every way out of the
 * {@code try}: the normal end, each {@code return}, {@code break} or {@code continue} leaving
 * it (after the returned value was computed), and a handler that runs it and throws the error
 * again. Copies on early exits are covered by the handler outside the {@code try}, so an error
 * in a {@code finally} block is never caught by its own {@code catch}.
 */
final class FunctionLowering {

    /**
     * An enclosing loop. {@code finallyDepth} is the number of pending {@code finally} blocks when
     * the loop was entered: jumping out of an iteration runs the ones added since.
     */
    private record Loop(int breakBlock, int continueBlock, boolean continueIsBackEdge, int finallyDepth) {
    }

    /** A {@code finally} block of an enclosing {@code try}, and the handler active outside that {@code try}. */
    private record PendingFinally(BoundStatement.Block body, int outerHandler) {
    }

    private final Lowering module;
    private final FunctionBuilder builder;
    private final Type returnType;
    private final Map<LocalSymbol, Register> locals = new IdentityHashMap<>();
    private final Deque<Loop> loops = new ArrayDeque<>();
    private List<PendingFinally> finallies = new ArrayList<>();

    FunctionLowering(Lowering module, FunctionBuilder builder, Type returnType) {
        this.module = module;
        this.builder = builder;
        this.returnType = returnType;
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
            case BoundStatement.GlobalStore store -> {
                Register value = value(store.value());
                builder.emit(new Instruction.GlobalSet(Lowering.global(store.global()), value, span(store.span())));
            }
            case BoundStatement.GlobalAdd add -> {
                Register delta = value(add.delta());
                builder.emit(new Instruction.GlobalAdd(Lowering.global(add.global()), delta, span(add.span())));
            }
            case BoundStatement.PlayerDataStore store -> {
                Register owner = value(store.owner());
                Register value = value(store.value());
                builder.emit(new Instruction.PlayerDataSet(Lowering.global(store.global()), owner, value, span(store.span())));
            }
            case BoundStatement.PlayerDataAdd add -> {
                Register owner = value(add.owner());
                Register delta = value(add.delta());
                builder.emit(new Instruction.PlayerDataAdd(Lowering.global(add.global()), owner, delta, span(add.span())));
            }
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
            case BoundStatement.Return ret -> lowerReturn(ret);
            case BoundStatement.Break brk -> {
                Loop loop = loops.peek();
                runFinallies(loop.finallyDepth(), span(brk.span()));
                builder.terminate(new Terminator.Jump(loop.breakBlock(), false, span(brk.span())));
            }
            case BoundStatement.Continue cont -> {
                Loop loop = loops.peek();
                runFinallies(loop.finallyDepth(), span(cont.span()));
                builder.terminate(new Terminator.Jump(loop.continueBlock(), loop.continueIsBackEdge(), span(cont.span())));
            }
            case BoundStatement.Try tryStatement -> lowerTry(tryStatement);
            case BoundStatement.Throw throwStatement -> {
                Register value = value(throwStatement.value());
                builder.terminate(new Terminator.Throw(value, span(throwStatement.span())));
            }
        }
    }

    private void lowerReturn(BoundStatement.Return ret) {
        long span = span(ret.span());
        if (finallies.isEmpty()) {
            builder.terminate(new Terminator.Return(ret.value() == null ? null : value(ret.value()), span));
            return;
        }
        // The value is computed before the finally blocks run, which may change the variables it reads.
        Register result = null;
        if (ret.value() != null) {
            result = builder.temp(returnType);
            emit(ret.value(), result);
        }
        runFinallies(0, span);
        builder.terminate(new Terminator.Return(result, span));
    }

    /**
     * Emits copies of the pending {@code finally} blocks from the innermost down to index
     * {@code downTo}, each covered by the handler outside its {@code try}.
     */
    private void runFinallies(int downTo, long span) {
        if (finallies.size() <= downTo) {
            return;
        }
        List<PendingFinally> all = finallies;
        int savedHandler = builder.handler();
        for (int i = all.size() - 1; i >= downTo && !builder.isTerminated(); i--) {
            PendingFinally pending = all.get(i);
            // While a copy runs, its own try (and the ones inside it) are no longer pending.
            finallies = new ArrayList<>(all.subList(0, i));
            builder.setHandler(pending.outerHandler());
            int block = builder.newBlock();
            builder.terminate(new Terminator.Jump(block, false, span));
            builder.switchTo(block);
            statement(pending.body());
        }
        finallies = all;
        builder.setHandler(savedHandler);
    }

    private void lowerTry(BoundStatement.Try statement) {
        long span = span(statement.span());
        int outer = builder.handler();
        boolean hasFinally = statement.finallyBody() != null;
        boolean hasCatch = statement.catchBody() != null;
        int finallyHandler = hasFinally ? builder.newBlock() : -1;
        builder.setHandler(hasFinally ? finallyHandler : outer);
        int catchHandler = hasCatch ? builder.newBlock() : -1;
        builder.setHandler(outer);
        int after = builder.newBlock();
        if (hasFinally) {
            finallies.add(new PendingFinally(statement.finallyBody(), outer));
        }

        // try { body }
        builder.setHandler(hasCatch ? catchHandler : finallyHandler);
        int body = builder.newBlock();
        builder.terminate(new Terminator.Jump(body, false, span));
        builder.switchTo(body);
        statement(statement.body());
        normalExit(statement, outer, after, span);

        // catch e { catchBody }
        if (hasCatch) {
            builder.setHandler(hasFinally ? finallyHandler : outer);
            builder.switchTo(catchHandler);
            Register error = builder.register(Types.EXCEPTION, statement.catchLocal().name());
            builder.emit(new Instruction.Catch(error, span));
            locals.put(statement.catchLocal(), error);
            statement(statement.catchBody());
            normalExit(statement, outer, after, span);
        }
        if (hasFinally) {
            finallies.removeLast();
            // An error in the body or the catch block: run the finally block, then throw it again.
            builder.setHandler(outer);
            builder.switchTo(finallyHandler);
            Register error = builder.temp(Types.EXCEPTION);
            builder.emit(new Instruction.Catch(error, span));
            statement(statement.finallyBody());
            if (!builder.isTerminated()) {
                builder.terminate(new Terminator.Throw(error, span));
            }
        }
        builder.setHandler(outer);
        builder.switchTo(after);
    }

    /** The end of a try or catch block was reached: run the finally block (outside the try) and continue after it. */
    private void normalExit(BoundStatement.Try statement, int outer, int after, long span) {
        if (builder.isTerminated()) {
            return;
        }
        builder.setHandler(outer);
        if (statement.finallyBody() != null) {
            List<PendingFinally> all = finallies;
            finallies = new ArrayList<>(all.subList(0, all.size() - 1));
            int block = builder.newBlock();
            builder.terminate(new Terminator.Jump(block, false, span));
            builder.switchTo(block);
            statement(statement.finallyBody());
            finallies = all;
        }
        builder.jumpIfOpen(after, span);
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
        loops.push(new Loop(exit, header, true, finallies.size()));
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
        loops.push(new Loop(exit, step, false, finallies.size()));
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

    /** {@code for x in list}: index-based iteration over the snapshot the binder took, no iterator allocation. */
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
        loops.push(new Loop(exit, step, false, finallies.size()));
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
            case BoundExpression.Let let -> {
                Register register = builder.register(let.local().type(), let.local().name());
                locals.put(let.local(), register);
                emit(let.value(), register);
                condition(let.body(), ifTrue, ifFalse);
            }
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
            case BoundExpression.FunctionCall call -> builder.emit(new Instruction.Call(null,
                    Lowering.function(call.function()), values(call.arguments()), span(call.span())));
            case BoundExpression.ClosureCall call -> {
                Register closure = value(call.callee());
                builder.emit(new Instruction.CallClosure(null, closure, values(call.arguments()), span(call.span())));
            }
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
            case BoundExpression.KeyedConstant constant -> {
                Register target = target(destination, constant.type());
                builder.emit(new Instruction.KeyedConst(target, constant.type(), constant.key(), span));
                yield target;
            }
            case BoundExpression.LocalLoad load -> move(local(load.local()), destination, span);
            case BoundExpression.GlobalLoad load -> {
                Register target = target(destination, load.global().type());
                builder.emit(new Instruction.GlobalGet(target, Lowering.global(load.global()), span));
                yield target;
            }
            case BoundExpression.PlayerDataLoad load -> {
                Register owner = value(load.owner());
                Register target = target(destination, load.global().type());
                builder.emit(new Instruction.PlayerDataGet(target, Lowering.global(load.global()), owner, span));
                yield target;
            }
            case BoundExpression.GlobalRestore restore -> {
                Register target = target(destination, PrimitiveType.BOOL);
                builder.emit(new Instruction.GlobalRestore(target, Lowering.global(restore.global()), span));
                yield target;
            }
            case BoundExpression.NativeCall call -> {
                List<Register> arguments = values(call.arguments());
                Register target = call.type() == PrimitiveType.VOID ? null : target(destination, call.type());
                builder.emit(new Instruction.CallNative(target, call.target(), arguments, span));
                yield target;
            }
            case BoundExpression.FunctionCall call -> {
                List<Register> arguments = values(call.arguments());
                Register target = call.type() == PrimitiveType.VOID ? null : target(destination, call.type());
                builder.emit(new Instruction.Call(target, Lowering.function(call.function()), arguments, span));
                yield target;
            }
            case BoundExpression.Lambda lambda -> {
                FunctionRef function = module.lambda(lambda);
                List<Register> captures = values(lambda.captureValues());
                Register target = target(destination, lambda.type());
                builder.emit(new Instruction.NewClosure(target, function, captures, span));
                yield target;
            }
            case BoundExpression.FunctionReference reference -> {
                Register target = target(destination, reference.type());
                builder.emit(new Instruction.NewClosure(target, Lowering.function(reference.function()), List.of(), span));
                yield target;
            }
            case BoundExpression.ClosureCall call -> {
                Register closure = value(call.callee());
                List<Register> arguments = values(call.arguments());
                Register target = call.type() == PrimitiveType.VOID ? null : target(destination, call.type());
                builder.emit(new Instruction.CallClosure(target, closure, arguments, span));
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
            case BoundExpression.Logical logical -> booleanValue(logical, destination, span);
            case BoundExpression.Conditional conditional -> {
                Register result = builder.temp(conditional.type());
                int whenTrue = builder.newBlock();
                int whenFalse = builder.newBlock();
                int join = builder.newBlock();
                condition(conditional.condition(), whenTrue, whenFalse);
                builder.switchTo(whenTrue);
                emit(conditional.whenTrue(), result);
                builder.jumpIfOpen(join, span);
                builder.switchTo(whenFalse);
                emit(conditional.whenFalse(), result);
                builder.jumpIfOpen(join, span);
                builder.switchTo(join);
                yield move(result, destination, span);
            }
            case BoundExpression.Let let -> {
                Register register = builder.register(let.local().type(), let.local().name());
                locals.put(let.local(), register);
                emit(let.value(), register);
                yield emit(let.body(), destination);
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
                if (test.record() != null) {
                    builder.emit(new Instruction.RecordTest(target, operand, Lowering.record(test.record()), span));
                } else {
                    builder.emit(new Instruction.InstanceOf(target, operand, test.target(), span));
                }
                yield target;
            }
            case BoundExpression.Cast cast -> {
                Register operand = value(cast.operand());
                Register target = target(destination, cast.type());
                if (cast.record() != null) {
                    builder.emit(new Instruction.RecordCast(target, operand, Lowering.record(cast.record()), cast.safe(), span));
                } else {
                    builder.emit(new Instruction.CheckCast(target, operand, cast.target(), cast.safe(), span));
                }
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
            case BoundExpression.MapLiteral map -> {
                List<Register> keys = new ArrayList<>();
                List<Register> values = new ArrayList<>();
                for (int i = 0; i < map.keys().size(); i++) {
                    keys.add(box(value(map.keys().get(i)), map.span()));
                    values.add(box(value(map.values().get(i)), map.span()));
                }
                Register target = target(destination, map.type());
                builder.emit(new Instruction.NewMap(target, keys, values, span));
                yield target;
            }
            case BoundExpression.NewRecord record -> {
                List<Register> fields = new ArrayList<>();
                for (BoundExpression field : record.fields()) {
                    fields.add(box(value(field), record.span()));
                }
                Register target = target(destination, record.type());
                builder.emit(new Instruction.NewRecord(target, Lowering.record(record.record()), fields, span));
                yield target;
            }
            case BoundExpression.RecordGet get -> {
                Register receiver = value(get.receiver());
                RecordSymbol.Field field = get.field();
                yield boxedRead(field.type(), destination, span,
                        boxed -> new Instruction.RecordGet(boxed, receiver, field.index(), span));
            }
            case BoundExpression.ListGet get -> {
                Register list = value(get.list());
                Register index = value(get.index());
                yield boxedRead(get.type(), destination, span, boxed -> new Instruction.ListGet(boxed, list, index, span));
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

    /** Reads a boxed value (a list element or record field) into a register of {@code type}, unboxing primitives. */
    private Register boxedRead(Type type, Register destination, long span,
                               java.util.function.Function<Register, Instruction> read) {
        if (type.representation().isPrimitive()) {
            Register boxed = builder.temp(Types.nullable(type));
            builder.emit(read.apply(boxed));
            Register target = target(destination, type);
            builder.emit(new Instruction.Convert(ConvertOp.unbox(type.representation()), target, boxed, span));
            return target;
        }
        Register target = target(destination, type);
        builder.emit(read.apply(target));
        return target;
    }

    /** A logical expression used as a value: branches that set a boolean. */
    private Register booleanValue(BoundExpression logical, Register destination, long span) {
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
        return move(result, destination, span);
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
            case REINTERPRET -> from.representation() == Representation.REF && to.representation().isPrimitive()
                    ? ConvertOp.unbox(to.representation()) : null;
            case TO_STRING -> {
                if (from == PrimitiveType.DURATION) {
                    yield ConvertOp.DURATION_TO_STRING;
                }
                if (from == PrimitiveType.INSTANT) {
                    yield ConvertOp.INSTANT_TO_STRING;
                }
                if (from.nonNullable() == PrimitiveType.DURATION || from.nonNullable() == PrimitiveType.INSTANT) {
                    yield null; // handled below: nullable durations and instants need a null check
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
        if (conversion.kind() == ConversionKind.TO_STRING && op == null) {
            return nullableTimeText(operand, from.nonNullable() == PrimitiveType.INSTANT
                    ? ConvertOp.INSTANT_TO_STRING : ConvertOp.DURATION_TO_STRING, destination, span);
        }
        if (op == null) {
            // Same representation (for example Duration -> long, or a re-typed reference): no operation.
            if (destination == null && !to.equals(operand.type()) && to.representation() == Representation.REF) {
                // Keep the static type of the result register exact (the verifier checks function types).
                Register retyped = builder.temp(to instanceof NullType ? Types.nullable(Types.ANY) : to);
                builder.emit(new Instruction.Move(retyped, operand, span));
                return retyped;
            }
            return move(operand, destination, span);
        }
        Register target = target(destination, to);
        builder.emit(new Instruction.Convert(op, target, operand, span));
        return target;
    }

    private Register nullableTimeText(Register boxed, ConvertOp text, Register destination, long span) {
        Register result = builder.temp(Types.STRING);
        Register present = builder.temp(PrimitiveType.BOOL);
        builder.emit(new Instruction.Unary(UnaryOp.IS_NOT_NULL, present, boxed, span));
        int some = builder.newBlock();
        int none = builder.newBlock();
        int join = builder.newBlock();
        builder.terminate(new Terminator.Branch(present, some, none, span));
        builder.switchTo(some);
        Register millis = builder.temp(PrimitiveType.LONG);
        builder.emit(new Instruction.Convert(ConvertOp.UNBOX_I64, millis, boxed, span));
        builder.emit(new Instruction.Convert(text, result, millis, span));
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

    /** Boxes primitive values stored in lists, maps and records. */
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
            case BIT_AND -> "AND";
            case BIT_OR -> "OR";
            case BIT_XOR -> "XOR";
            case SHIFT_LEFT -> "SHL";
            case SHIFT_RIGHT -> "SHR";
            case UNSIGNED_SHIFT_RIGHT -> "USHR";
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

    /** Whether {@code type} is a function type (closures are created for these). */
    static boolean isFunction(Type type) {
        return type.nonNullable() instanceof FunctionType;
    }
}
