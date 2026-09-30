package dev.tachyonscript.ir.verify;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.type.FunctionType;
import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.ir.FunctionRef;
import dev.tachyonscript.ir.GlobalRef;
import dev.tachyonscript.ir.Instruction;
import dev.tachyonscript.ir.IrBlock;
import dev.tachyonscript.ir.IrFunction;
import dev.tachyonscript.ir.IrModule;
import dev.tachyonscript.ir.Register;
import dev.tachyonscript.ir.Terminator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;

/**
 * Checks IR before it is allowed to execute.
 *
 * <p>This is the register-machine counterpart of JVM bytecode verification: every register
 * reference must be valid and of the kind the instruction expects (the equivalent of stack
 * type consistency), every register must be definitely assigned before it is read on every
 * path (the equivalent of stack underflow), every jump must target an existing block, calls
 * must match their callee's signature, and returns must match the function's return type.
 * Exception handlers are checked too: a block's handler must start with
 * {@link Instruction.Catch}, which may appear nowhere else, and a handler may only read
 * registers assigned on entry to every block it covers. No IR reaches the runtime without
 * passing verification.
 */
public final class IrVerifier {

    private final IrModule module;
    private final List<String> problems = new ArrayList<>();

    private IrVerifier(IrModule module) {
        this.module = module;
    }

    /** Verifies {@code module}, throwing {@link VerificationException} listing every problem. */
    public static void verify(IrModule module) {
        IrVerifier verifier = new IrVerifier(module);
        for (IrFunction function : module.functions()) {
            verifier.verifyFunction(function);
        }
        if (!verifier.problems.isEmpty()) {
            throw new VerificationException(module.name(), verifier.problems);
        }
    }

    private void verifyFunction(IrFunction function) {
        int registerCount = function.registers().size();
        for (Register parameter : function.parameters()) {
            checkRegister(function, parameter, "parameter");
        }
        for (IrBlock block : function.blocks()) {
            if (block.hasHandler()) {
                int handler = block.handler();
                if (handler < 0 || handler >= function.blocks().size() || !function.blocks().get(handler).isHandler()) {
                    problems.add(function.key() + " B" + block.index() + ": handler B" + handler
                            + " is not a block starting with catch");
                } else if (handler == block.index()) {
                    problems.add(function.key() + " B" + block.index() + ": a handler cannot handle its own errors");
                }
            }
            for (int i = 0; i < block.instructions().size(); i++) {
                Instruction instruction = block.instructions().get(i);
                String where = function.key() + " B" + block.index() + "#" + i;
                if (instruction instanceof Instruction.Catch && i != 0) {
                    problems.add(where + ": catch must be the first instruction of its block");
                }
                for (Register operand : instruction.operands()) {
                    checkRegister(function, operand, where);
                }
                if (instruction.target() != null) {
                    checkRegister(function, instruction.target(), where);
                }
                checkInstruction(function, instruction, where);
            }
            String where = function.key() + " B" + block.index() + " terminator";
            for (Register operand : block.terminator().operands()) {
                checkRegister(function, operand, where);
            }
            checkTerminator(function, block.terminator(), where);
        }
        if (problems.isEmpty() && registerCount > 0) {
            checkDefiniteAssignment(function);
        }
    }

    private void checkRegister(IrFunction function, Register register, String where) {
        int index = register.index();
        if (index < 0 || index >= function.registers().size() || !function.registers().get(index).equals(register)) {
            problems.add(where + ": invalid register " + register);
        }
    }

    private void checkInstruction(IrFunction function, Instruction instruction, String where) {
        switch (instruction) {
            case Instruction.Const c -> checkConstant(c, where);
            case Instruction.Move m -> expect(where, "move", m.source(), m.target().kind());
            case Instruction.Unary u -> {
                expect(where, u.op().name(), u.operand(), u.op().operand());
                expect(where, u.op().name(), u.target(), u.op().result());
            }
            case Instruction.Binary b -> {
                expect(where, b.op().name(), b.left(), b.op().operand());
                expect(where, b.op().name(), b.right(), b.op().operand());
                expect(where, b.op().name(), b.target(), b.op().result());
            }
            case Instruction.Convert c -> {
                expect(where, c.op().name(), c.operand(), c.op().operand());
                expect(where, c.op().name(), c.target(), c.op().result());
            }
            case Instruction.InstanceOf i -> {
                expect(where, "instanceof", i.operand(), Representation.REF);
                expect(where, "instanceof", i.target(), Representation.BOOL);
            }
            case Instruction.CheckCast c -> {
                expect(where, "cast", c.operand(), Representation.REF);
                expect(where, "cast", c.target(), Representation.REF);
            }
            case Instruction.KeyedConst k -> {
                expect(where, "keyed_const", k.target(), Representation.REF);
                if (!k.type().isKeyed()) {
                    problems.add(where + ": type " + k.type().name() + " has no named constants");
                }
            }
            case Instruction.CallNative c -> checkNativeCall(c, where);
            case Instruction.Call c -> checkCall(c, where);
            case Instruction.NewClosure n -> checkClosure(n, where);
            case Instruction.CallClosure c -> checkClosureCall(c, where);
            case Instruction.NewMap n -> {
                n.keys().forEach(key -> expect(where, "new_map", key, Representation.REF));
                n.values().forEach(value -> expect(where, "new_map", value, Representation.REF));
                expect(where, "new_map", n.target(), Representation.REF);
            }
            case Instruction.NewRecord n -> {
                if (n.fields().size() != n.record().fieldCount()) {
                    problems.add(where + ": record " + n.record() + " has " + n.record().fieldCount() + " fields, got "
                            + n.fields().size());
                }
                n.fields().forEach(field -> expect(where, "new_record", field, Representation.REF));
                expect(where, "new_record", n.target(), Representation.REF);
            }
            case Instruction.RecordGet g -> {
                expect(where, "record_get", g.record(), Representation.REF);
                expect(where, "record_get", g.target(), Representation.REF);
                if (g.index() < 0) {
                    problems.add(where + ": negative field index " + g.index());
                }
            }
            case Instruction.RecordTest t -> {
                expect(where, "record_test", t.operand(), Representation.REF);
                expect(where, "record_test", t.target(), Representation.BOOL);
            }
            case Instruction.RecordCast c -> {
                expect(where, "record_cast", c.operand(), Representation.REF);
                expect(where, "record_cast", c.target(), Representation.REF);
            }
            case Instruction.GlobalGet g -> {
                checkGlobal(g.global(), false, where);
                expect(where, "global_get " + g.global(), g.target(), g.global().type().representation());
            }
            case Instruction.GlobalSet g -> {
                checkGlobal(g.global(), false, where);
                expect(where, "global_set " + g.global(), g.value(), g.global().type().representation());
            }
            case Instruction.GlobalAdd g -> {
                checkGlobal(g.global(), false, where);
                checkNumeric(g.global(), where);
                expect(where, "global_add " + g.global(), g.delta(), g.global().type().representation());
            }
            case Instruction.GlobalRestore r -> {
                if (r.global().storage() != GlobalRef.Storage.PERSISTENT || !r.global().module().equals(module.name())) {
                    problems.add(where + ": only a persistent variable of this module can be restored: " + r.global());
                }
                expect(where, "global_restore", r.target(), Representation.BOOL);
            }
            case Instruction.PlayerDataGet g -> {
                checkGlobal(g.data(), true, where);
                expect(where, "playerdata_get", g.owner(), Representation.REF);
                expect(where, "playerdata_get " + g.data(), g.target(), g.data().type().representation());
            }
            case Instruction.PlayerDataSet g -> {
                checkGlobal(g.data(), true, where);
                expect(where, "playerdata_set", g.owner(), Representation.REF);
                expect(where, "playerdata_set " + g.data(), g.value(), g.data().type().representation());
            }
            case Instruction.PlayerDataAdd g -> {
                checkGlobal(g.data(), true, where);
                checkNumeric(g.data(), where);
                expect(where, "playerdata_add", g.owner(), Representation.REF);
                expect(where, "playerdata_add " + g.data(), g.delta(), g.data().type().representation());
            }
            case Instruction.Catch c -> expect(where, "catch", c.target(), Representation.REF);
            case Instruction.Concat c -> {
                c.parts().forEach(part -> expect(where, "concat", part, Representation.REF));
                expect(where, "concat", c.target(), Representation.REF);
            }
            case Instruction.RenderTemplate t -> {
                if (t.segments().size() != t.arguments().size() + 1) {
                    problems.add(where + ": template has " + t.segments().size() + " segments for "
                            + t.arguments().size() + " arguments");
                }
                t.arguments().forEach(argument -> expect(where, "template", argument, Representation.REF));
                expect(where, "template", t.target(), Representation.REF);
            }
            case Instruction.NewList n -> {
                n.elements().forEach(element -> expect(where, "new_list", element, Representation.REF));
                expect(where, "new_list", n.target(), Representation.REF);
            }
            case Instruction.ListGet g -> {
                expect(where, "list_get", g.list(), Representation.REF);
                expect(where, "list_get", g.index(), Representation.INT);
                expect(where, "list_get", g.target(), Representation.REF);
            }
            case Instruction.ListSet s -> {
                expect(where, "list_set", s.list(), Representation.REF);
                expect(where, "list_set", s.index(), Representation.INT);
                expect(where, "list_set", s.value(), Representation.REF);
            }
            case Instruction.ListSize s -> {
                expect(where, "list_size", s.list(), Representation.REF);
                expect(where, "list_size", s.target(), Representation.INT);
            }
            case Instruction.ListAdd a -> {
                expect(where, "list_add", a.list(), Representation.REF);
                expect(where, "list_add", a.value(), Representation.REF);
            }
            case Instruction.ListContains c -> {
                expect(where, "list_contains", c.list(), Representation.REF);
                expect(where, "list_contains", c.value(), Representation.REF);
                expect(where, "list_contains", c.target(), Representation.BOOL);
            }
        }
        if (instruction.target() == null && requiresTarget(instruction)) {
            problems.add(where + ": " + instruction.getClass().getSimpleName() + " needs a target register");
        }
    }

    private static boolean requiresTarget(Instruction instruction) {
        return !(instruction instanceof Instruction.CallNative || instruction instanceof Instruction.Call
                || instruction instanceof Instruction.CallClosure
                || instruction instanceof Instruction.ListSet || instruction instanceof Instruction.ListAdd
                || instruction instanceof Instruction.GlobalSet || instruction instanceof Instruction.GlobalAdd
                || instruction instanceof Instruction.PlayerDataSet || instruction instanceof Instruction.PlayerDataAdd);
    }

    private void checkGlobal(GlobalRef global, boolean playerData, String where) {
        boolean isPlayerData = global.storage() == GlobalRef.Storage.PLAYERDATA;
        if (isPlayerData != playerData) {
            problems.add(where + ": " + global + (playerData ? " is not a playerdata variable" : " is a playerdata variable"));
        }
        if (global.type().representation() == Representation.VOID) {
            problems.add(where + ": " + global + " has no value type");
        }
    }

    private void checkNumeric(GlobalRef global, String where) {
        Type type = global.type();
        if (type != PrimitiveType.INT && type != PrimitiveType.LONG && type != PrimitiveType.DOUBLE) {
            problems.add(where + ": atomic addition needs an int, long or double variable, " + global + " is "
                    + type.displayName());
        }
    }

    private void checkConstant(Instruction.Const constant, String where) {
        Object value = constant.value();
        boolean ok = switch (constant.target().kind()) {
            case INT -> value instanceof Integer;
            case LONG -> value instanceof Long;
            case FLOAT -> value instanceof Float;
            case DOUBLE -> value instanceof Double;
            case BOOL -> value instanceof Boolean;
            case REF -> value == null || value instanceof String;
            case VOID -> false;
        };
        if (!ok) {
            problems.add(where + ": constant " + value + " does not fit register kind " + constant.target().kind());
        }
    }

    private void checkNativeCall(Instruction.CallNative call, String where) {
        NativeDeclaration function = call.function();
        if (call.arguments().size() != function.arity()) {
            problems.add(where + ": " + function.key() + " takes " + function.arity() + " arguments, got "
                    + call.arguments().size());
            return;
        }
        for (int i = 0; i < call.arguments().size(); i++) {
            expect(where, function.key() + " argument " + i, call.arguments().get(i),
                    function.parameters().get(i).type().representation());
        }
        if (call.target() != null) {
            if (function.returnRepresentation() == Representation.VOID) {
                problems.add(where + ": " + function.key() + " returns nothing but has a target");
            } else {
                expect(where, function.key() + " result", call.target(), function.returnRepresentation());
            }
        }
    }

    private void checkCall(Instruction.Call call, String where) {
        FunctionRef function = call.function();
        if (!checkReference(function, where)) {
            return;
        }
        if (function.parameters().size() != call.arguments().size()) {
            problems.add(where + ": " + function.key() + " takes " + function.parameters().size() + " arguments, got "
                    + call.arguments().size());
            return;
        }
        for (int i = 0; i < call.arguments().size(); i++) {
            expect(where, function.key() + " argument " + i, call.arguments().get(i),
                    function.parameters().get(i).representation());
        }
        checkResult(call.target(), function.returnType(), function.key(), where);
    }

    /**
     * A reference to a function of this module must match the function's signature exactly;
     * references to imported modules are checked by the linker.
     */
    private boolean checkReference(FunctionRef function, String where) {
        if (!function.module().equals(module.name())) {
            return true;
        }
        IrFunction callee = module.function(function.key()).orElse(null);
        if (callee == null) {
            problems.add(where + ": reference to unknown function " + function.key());
            return false;
        }
        if (callee.parameters().size() != function.parameters().size()) {
            problems.add(where + ": " + callee.key() + " takes " + callee.parameters().size() + " parameters, the reference says "
                    + function.parameters().size());
            return false;
        }
        for (int i = 0; i < function.parameters().size(); i++) {
            if (callee.parameters().get(i).kind() != function.parameters().get(i).representation()) {
                problems.add(where + ": parameter " + i + " of " + callee.key() + " is " + callee.parameters().get(i).kind()
                        + ", the reference says " + function.parameters().get(i).representation());
                return false;
            }
        }
        if (callee.returnKind() != function.returnType().representation()) {
            problems.add(where + ": " + callee.key() + " returns " + callee.returnKind() + ", the reference says "
                    + function.returnType().representation());
            return false;
        }
        return true;
    }

    private void checkResult(Register target, Type returnType, String what, String where) {
        if (target == null) {
            return;
        }
        if (returnType.representation() == Representation.VOID) {
            problems.add(where + ": " + what + " returns nothing but has a target");
        } else {
            expect(where, what + " result", target, returnType.representation());
        }
    }

    private void checkClosure(Instruction.NewClosure closure, String where) {
        expect(where, "new_closure", closure.target(), Representation.REF);
        FunctionRef function = closure.function();
        if (!checkReference(function, where)) {
            return;
        }
        int captured = closure.captures().size();
        if (captured > function.parameters().size()) {
            problems.add(where + ": " + function.key() + " has fewer parameters than captured values");
            return;
        }
        for (int i = 0; i < captured; i++) {
            expect(where, function.key() + " capture " + i, closure.captures().get(i),
                    function.parameters().get(i).representation());
        }
        if (!(closure.target().type().nonNullable() instanceof FunctionType type)) {
            problems.add(where + ": closure target " + closure.target() + " does not have a function type");
            return;
        }
        if (type.arity() != function.parameters().size() - captured) {
            problems.add(where + ": closure of type " + type.displayName() + " for " + function.key() + " with "
                    + captured + " captured values");
            return;
        }
        for (int i = 0; i < type.arity(); i++) {
            if (type.parameters().get(i).representation() != function.parameters().get(captured + i).representation()) {
                problems.add(where + ": closure parameter " + i + " is " + type.parameters().get(i).displayName()
                        + " but " + function.key() + " takes " + function.parameters().get(captured + i).displayName());
            }
        }
        if (type.returnType().representation() != function.returnType().representation()) {
            problems.add(where + ": closure returns " + type.returnType().displayName() + " but " + function.key()
                    + " returns " + function.returnType().displayName());
        }
    }

    private void checkClosureCall(Instruction.CallClosure call, String where) {
        expect(where, "call_closure", call.closure(), Representation.REF);
        if (!(call.closure().type().nonNullable() instanceof FunctionType type)) {
            problems.add(where + ": " + call.closure() + " is not a function value");
            return;
        }
        if (type.arity() != call.arguments().size()) {
            problems.add(where + ": function value takes " + type.arity() + " arguments, got " + call.arguments().size());
            return;
        }
        for (int i = 0; i < type.arity(); i++) {
            expect(where, "call_closure argument " + i, call.arguments().get(i),
                    type.parameters().get(i).representation());
        }
        checkResult(call.target(), type.returnType(), "function value", where);
    }

    private void checkTerminator(IrFunction function, Terminator terminator, String where) {
        for (int successor : terminator.successors()) {
            if (successor < 0 || successor >= function.blocks().size()) {
                problems.add(where + ": jump to missing block B" + successor);
            }
        }
        switch (terminator) {
            case Terminator.Branch branch -> expect(where, "branch", branch.condition(), Representation.BOOL);
            case Terminator.Throw thrown -> expect(where, "throw", thrown.value(), Representation.REF);
            case Terminator.Return ret -> {
                if (function.returnKind() == Representation.VOID) {
                    if (ret.value() != null) {
                        problems.add(where + ": void function returns a value");
                    }
                } else if (ret.value() == null) {
                    problems.add(where + ": missing return value of kind " + function.returnKind());
                } else {
                    expect(where, "return", ret.value(), function.returnKind());
                }
            }
            default -> {
            }
        }
    }

    private static void merge(BitSet[] in, int successor, BitSet assigned, Deque<Integer> work) {
        if (in[successor] == null) {
            in[successor] = (BitSet) assigned.clone();
            work.add(successor);
        } else {
            BitSet merged = (BitSet) in[successor].clone();
            merged.and(assigned);
            if (!merged.equals(in[successor])) {
                in[successor] = merged;
                work.add(successor);
            }
        }
    }

    private void expect(String where, String what, Register register, Representation kind) {
        if (register == null) {
            problems.add(where + ": " + what + " is missing a register");
        } else if (register.kind() != kind) {
            problems.add(where + ": " + what + " expects " + kind + " but " + register + " is " + register.kind());
        }
    }

    /**
     * Forward data flow over "definitely assigned" register sets: a register may only be read
     * if every path from the entry assigns it first. An error can leave a block before any of
     * its instructions ran, so a handler starts with what is assigned on entry to every block it
     * covers (plus the register receiving the error).
     */
    private void checkDefiniteAssignment(IrFunction function) {
        int blocks = function.blocks().size();
        int registers = function.registers().size();
        BitSet[] in = new BitSet[blocks];
        BitSet entry = new BitSet(registers);
        function.parameters().forEach(parameter -> entry.set(parameter.index()));
        in[0] = entry;
        Deque<Integer> work = new ArrayDeque<>();
        work.add(0);
        while (!work.isEmpty()) {
            int index = work.poll();
            BitSet assigned = (BitSet) in[index].clone();
            IrBlock block = function.blocks().get(index);
            if (block.hasHandler()) {
                merge(in, block.handler(), in[index], work);
            }
            for (Instruction instruction : block.instructions()) {
                if (instruction.target() != null) {
                    assigned.set(instruction.target().index());
                }
            }
            for (int successor : block.terminator().successors()) {
                merge(in, successor, assigned, work);
            }
        }
        for (IrBlock block : function.blocks()) {
            if (in[block.index()] == null) {
                continue; // unreachable
            }
            BitSet assigned = (BitSet) in[block.index()].clone();
            for (int i = 0; i < block.instructions().size(); i++) {
                Instruction instruction = block.instructions().get(i);
                for (Register operand : instruction.operands()) {
                    if (!assigned.get(operand.index())) {
                        problems.add(function.key() + " B" + block.index() + "#" + i + ": " + operand
                                + " may be read before it is assigned");
                    }
                }
                if (instruction.target() != null) {
                    assigned.set(instruction.target().index());
                }
            }
            for (Register operand : block.terminator().operands()) {
                if (!assigned.get(operand.index())) {
                    problems.add(function.key() + " B" + block.index() + " terminator: " + operand
                            + " may be read before it is assigned");
                }
            }
        }
    }
}
