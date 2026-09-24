package dev.tachyonscript.ir;

import java.util.List;

/** The last instruction of a basic block: transfers control. */
public sealed interface Terminator {

    long span();

    List<Register> operands();

    /** Indices of successor blocks. */
    List<Integer> successors();

    /**
     * Unconditional jump. {@code backEdge} marks the jump that closes a loop iteration; the
     * runtime places its (cheap) execution-budget check only on back edges.
     */
    record Jump(int target, boolean backEdge, long span) implements Terminator {
        public List<Register> operands() {
            return List.of();
        }

        public List<Integer> successors() {
            return List.of(target);
        }
    }

    record Branch(Register condition, int ifTrue, int ifFalse, long span) implements Terminator {
        public List<Register> operands() {
            return List.of(condition);
        }

        public List<Integer> successors() {
            return List.of(ifTrue, ifFalse);
        }
    }

    /** Returns {@code value}, or nothing when {@code value} is null (void functions). */
    record Return(Register value, long span) implements Terminator {
        public List<Register> operands() {
            return value == null ? List.of() : List.of(value);
        }

        public List<Integer> successors() {
            return List.of();
        }
    }

    /** Marks code the compiler proved unreachable; executing it is an internal error. */
    record Unreachable(long span) implements Terminator {
        public List<Register> operands() {
            return List.of();
        }

        public List<Integer> successors() {
            return List.of();
        }
    }
}
