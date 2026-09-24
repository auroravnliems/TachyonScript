package dev.tachyonscript.ir.verify;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.type.Representation;
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
 * No IR reaches the runtime without passing verification.
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
            for (int i = 0; i < block.instructions().size(); i++) {
                Instruction instruction = block.instructions().get(i);
                String where = function.key() + " B" + block.index() + "#" + i;
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
            case Instruction.CallNative c -> checkNativeCall(c, where);
            case Instruction.Call c -> checkCall(c, where);
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
                || instruction instanceof Instruction.ListSet || instruction instanceof Instruction.ListAdd);
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
        IrFunction callee = module.function(call.function()).orElse(null);
        if (callee == null) {
            problems.add(where + ": call to unknown function " + call.function());
            return;
        }
        if (callee.parameters().size() != call.arguments().size()) {
            problems.add(where + ": " + callee.key() + " takes " + callee.parameters().size() + " arguments, got "
                    + call.arguments().size());
            return;
        }
        for (int i = 0; i < call.arguments().size(); i++) {
            expect(where, callee.key() + " argument " + i, call.arguments().get(i), callee.parameters().get(i).kind());
        }
        if (call.target() != null) {
            if (callee.returnKind() == Representation.VOID) {
                problems.add(where + ": " + callee.key() + " returns nothing but has a target");
            } else {
                expect(where, callee.key() + " result", call.target(), callee.returnKind());
            }
        }
    }

    private void checkTerminator(IrFunction function, Terminator terminator, String where) {
        for (int successor : terminator.successors()) {
            if (successor < 0 || successor >= function.blocks().size()) {
                problems.add(where + ": jump to missing block B" + successor);
            }
        }
        switch (terminator) {
            case Terminator.Branch branch -> expect(where, "branch", branch.condition(), Representation.BOOL);
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

    private void expect(String where, String what, Register register, Representation kind) {
        if (register == null) {
            problems.add(where + ": " + what + " is missing a register");
        } else if (register.kind() != kind) {
            problems.add(where + ": " + what + " expects " + kind + " but " + register + " is " + register.kind());
        }
    }

    /**
     * Forward data flow over "definitely assigned" register sets: a register may only be read
     * if every path from the entry assigns it first.
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
            for (Instruction instruction : block.instructions()) {
                if (instruction.target() != null) {
                    assigned.set(instruction.target().index());
                }
            }
            for (int successor : block.terminator().successors()) {
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
