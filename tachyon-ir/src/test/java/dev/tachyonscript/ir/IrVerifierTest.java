package dev.tachyonscript.ir;

import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.ir.opt.RemoveUnreachableBlocks;
import dev.tachyonscript.ir.verify.IrVerifier;
import dev.tachyonscript.ir.verify.VerificationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrVerifierTest {

    private static final long S = Spans.NONE;
    private static final FunctionDeclaration LOG = FunctionDeclaration.global("log").parameter("message", Types.STRING).build();
    private static final FunctionDeclaration ABS = FunctionDeclaration.global("math.abs").parameter("v", Types.INT)
            .returns(Types.INT).build();

    private static IrModule module(IrFunction... functions) {
        return new IrModule("test", new SourceText("test.tys", ""), List.of(functions), List.of());
    }

    private static IrFunction function(dev.tachyonscript.api.type.Type returnType, Consumer<FunctionBuilder> body) {
        FunctionBuilder builder = new FunctionBuilder("f", "function f", IrFunction.Kind.FUNCTION, returnType, S);
        body.accept(builder);
        return builder.build();
    }

    private static VerificationException invalid(IrFunction function) {
        return assertThrows(VerificationException.class, () -> IrVerifier.verify(module(function)));
    }

    @Test
    void acceptsWellFormedLoop() {
        // var i = 0; while i < 10 { i = i + 1 }; return i
        IrFunction function = function(Types.INT, b -> {
            Register i = b.register(Types.INT, "i");
            Register ten = b.temp(Types.INT);
            Register one = b.temp(Types.INT);
            Register condition = b.temp(Types.BOOL);
            b.emit(new Instruction.Const(i, 0, S));
            b.emit(new Instruction.Const(ten, 10, S));
            b.emit(new Instruction.Const(one, 1, S));
            int header = b.newBlock();
            int body = b.newBlock();
            int exit = b.newBlock();
            b.terminate(new Terminator.Jump(header, false, S));
            b.switchTo(header);
            b.emit(new Instruction.Binary(BinaryOp.LT_I32, condition, i, ten, S));
            b.terminate(new Terminator.Branch(condition, body, exit, S));
            b.switchTo(body);
            b.emit(new Instruction.Binary(BinaryOp.ADD_I32, i, i, one, S));
            b.terminate(new Terminator.Jump(header, true, S));
            b.switchTo(exit);
            b.terminate(new Terminator.Return(i, S));
        });
        assertDoesNotThrow(() -> IrVerifier.verify(module(function)));
        String printed = IrPrinter.print(function);
        assertTrue(printed.contains("r3:bool = lt_i32 r0, r1"), printed);
        assertTrue(printed.contains("loop B1"), printed);
    }

    @Test
    void rejectsKindMismatch() {
        VerificationException error = invalid(function(Types.VOID, b -> {
            Register d = b.temp(Types.DOUBLE);
            Register i = b.temp(Types.INT);
            b.emit(new Instruction.Const(d, 1.0, S));
            b.emit(new Instruction.Binary(BinaryOp.ADD_I32, i, d, d, S));
            b.terminate(new Terminator.Return(null, S));
        }));
        assertTrue(error.getMessage().contains("ADD_I32 expects INT but r0 is DOUBLE"), error.getMessage());
    }

    @Test
    void rejectsReadBeforeAssignmentOnSomePath() {
        VerificationException error = invalid(function(Types.INT, b -> {
            Register flag = b.parameter(Types.BOOL, "flag");
            Register x = b.register(Types.INT, "x");
            int set = b.newBlock();
            int join = b.newBlock();
            b.terminate(new Terminator.Branch(flag, set, join, S));
            b.switchTo(set);
            b.emit(new Instruction.Const(x, 1, S));
            b.terminate(new Terminator.Jump(join, false, S));
            b.switchTo(join);
            b.terminate(new Terminator.Return(x, S));
        }));
        assertTrue(error.getMessage().contains("r1 may be read before it is assigned"), error.getMessage());
    }

    @Test
    void rejectsInvalidJumpsAndReturns() {
        assertTrue(invalid(function(Types.VOID, b -> b.terminate(new Terminator.Jump(7, false, S))))
                .getMessage().contains("missing block B7"));
        assertTrue(invalid(function(Types.INT, b -> b.terminate(new Terminator.Return(null, S))))
                .getMessage().contains("missing return value"));
        assertTrue(invalid(function(Types.VOID, b -> {
            Register x = b.temp(Types.INT);
            b.emit(new Instruction.Const(x, 1, S));
            b.terminate(new Terminator.Return(x, S));
        })).getMessage().contains("void function returns a value"));
    }

    @Test
    void checksNativeCallSignatures() {
        assertTrue(invalid(function(Types.VOID, b -> {
            b.emit(new Instruction.CallNative(null, LOG.invocable(), List.of(), S));
            b.terminate(new Terminator.Return(null, S));
        })).getMessage().contains("takes 1 arguments, got 0"));
        assertTrue(invalid(function(Types.VOID, b -> {
            Register text = b.temp(Types.STRING);
            Register result = b.temp(Types.INT);
            b.emit(new Instruction.Const(text, "x", S));
            b.emit(new Instruction.CallNative(result, ABS.invocable(), List.of(text), S));
            b.terminate(new Terminator.Return(null, S));
        })).getMessage().contains("math.abs(int) argument 0 expects INT"));
    }

    @Test
    void checksConstantsAndTemplates() {
        assertTrue(invalid(function(Types.VOID, b -> {
            Register x = b.temp(Types.INT);
            b.emit(new Instruction.Const(x, 1L, S));
            b.terminate(new Terminator.Return(null, S));
        })).getMessage().contains("does not fit register kind INT"));
        assertTrue(invalid(function(Types.VOID, b -> {
            Register component = b.temp(Types.COMPONENT);
            b.emit(new Instruction.RenderTemplate(component, List.of("a", "b"), List.of(), S));
            b.terminate(new Terminator.Return(null, S));
        })).getMessage().contains("2 segments for 0 arguments"));
    }

    @Test
    void checksScriptCalls() {
        FunctionBuilder calleeBuilder = new FunctionBuilder("g(int)", "function g", IrFunction.Kind.FUNCTION, Types.VOID, S);
        calleeBuilder.parameter(Types.INT, "x");
        calleeBuilder.terminate(new Terminator.Return(null, S));
        IrFunction callee = calleeBuilder.build();
        IrFunction caller = function(Types.VOID, b -> {
            Register text = b.temp(Types.STRING);
            b.emit(new Instruction.Const(text, "x", S));
            b.emit(new Instruction.Call(null, "g(int)", List.of(text), S));
            b.emit(new Instruction.Call(null, "missing()", List.of(), S));
            b.terminate(new Terminator.Return(null, S));
        });
        VerificationException error = assertThrows(VerificationException.class, () -> IrVerifier.verify(module(caller, callee)));
        assertEquals(2, error.problems().size(), error.getMessage());
    }

    @Test
    void removesUnreachableBlocksAndRenumbers() {
        IrFunction function = function(Types.VOID, b -> {
            int dead = b.newBlock();
            int live = b.newBlock();
            b.terminate(new Terminator.Jump(live, false, S));
            b.switchTo(dead);
            b.terminate(new Terminator.Jump(live, false, S));
            b.switchTo(live);
            b.terminate(new Terminator.Return(null, S));
        });
        IrFunction cleaned = new RemoveUnreachableBlocks().run(function);
        assertEquals(2, cleaned.blocks().size());
        assertEquals(new Terminator.Jump(1, false, S), cleaned.blocks().getFirst().terminator());
        assertDoesNotThrow(() -> IrVerifier.verify(module(cleaned)));
    }
}
