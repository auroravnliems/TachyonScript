# ADR 0001: Register-based typed IR

## Context

The compiler needs one intermediate representation that the interpreter can
execute, that optimization passes can transform, and that a later JVM bytecode
backend can translate. The two usual shapes are a stack machine (like JVM
bytecode) and a register machine / three-address code.

## Decision

The IR is a **typed register-based three-address code in basic blocks**:

* every function has virtual registers, each with a fixed kind — `INT`, `LONG`,
  `FLOAT`, `DOUBLE`, `BOOL` or `REF`;
* instructions are specialised by kind (`ADD_I32`, `LT_F64`, ...), so nothing is
  decided at run time from a value's class;
* blocks end in exactly one terminator (`jump`, `branch`, `return`,
  `unreachable`); loop back edges are marked;
* native calls reference a `NativeDeclaration`, script calls a function key;
* every instruction carries a packed source span.

A verifier checks every function before it is assembled: register kinds of
operands and targets, definite assignment on all paths, valid block targets,
argument counts and kinds against native signatures, and return kinds.

## Consequences

* The interpreter executes fewer instructions than a stack machine would for
  the same source (`a = b + c` is one instruction instead of four), which is
  the main cost of an interpreter.
* Data-flow passes (constant propagation, copy propagation, dead code
  elimination) work on named values directly. The form can be converted to SSA
  if a pass needs it.
* Translating to JVM bytecode is simple: each register becomes a local variable.
* Registers must be mapped to frame slots (done by the assembler); a later pass
  will reuse slots of registers whose lifetimes do not overlap.
* The IR is not SSA today. Passes that need SSA will have to build it.
