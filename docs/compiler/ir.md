# Intermediate representation

`tachyon-ir` defines the typed register IR that sits between the language and
the execution backends. Why registers rather than a stack is explained in
[ADR 0001](../decisions/0001-register-based-ir.md). The IR has no dependency on
the language frontend: tests and future front ends can build it directly with
`FunctionBuilder`.

## Structure

```text
IrModule    name, source text, functions, event handlers (event → function key)
IrFunction  key, display name, parameters, registers, blocks, return kind
IrBlock     instructions + exactly one terminator
Register    index + kind: INT, LONG, FLOAT, DOUBLE, BOOL, REF
```

Every instruction and terminator carries a packed source span (`Spans`), which
the runtime uses for error locations.

## Instructions

| Instruction                        | Meaning                                         |
|------------------------------------|-------------------------------------------------|
| `Const target value`               | int, long, float, double, bool, string or null  |
| `Move target source`               | copy between registers of the same kind         |
| `Unary op target operand`          | `NEG_I32` ... `NEG_F64`, `NOT`, `IS_NULL`, `IS_NOT_NULL` |
| `Binary op target left right`      | `ADD_I32` ... `REM_F64`, `EQ_I32` ... `GE_F64`, `EQ_BOOL`, `EQ_REF`, ... |
| `Convert op target operand`        | widening/narrowing, boxing/unboxing, to-string, string → Component |
| `InstanceOf target operand type`   | `is`                                            |
| `CheckCast target operand type safe` | `as` (fails) and `as?` (null)                 |
| `CallNative target decl args`      | call a declared native (target optional)        |
| `Call target function args`        | call a script function by key                   |
| `Concat target parts`              | string concatenation                            |
| `RenderTemplate target segments args` | component template                           |
| `NewList`, `ListGet`, `ListSet`, `ListSize`, `ListAdd`, `ListContains` | lists |

Terminators: `Jump target [backEdge]`, `Branch condition ifTrue ifFalse`,
`Return [value]`, `Unreachable`.

Operators are specialised by kind; there is no generic "add". The printer
(`IrPrinter`, also `tys dump ir`) shows functions like this:

```text
function event player.join #0: void  [event player.join]
  params: r0:PlayerJoinEvent
  B0:
    r1:Player = call event player.join:player(r0)
    r2:string = call Entity.name:get(r1)
    r3:Component = template "Hello " r2 "!"
    call CommandSender.send(Component)(r1, r3)
    return
```

## Verifier

`IrVerifier` runs after lowering and optimization and rejects (as an internal
compiler error, never silently) any function where:

* an operand or target has the wrong register kind for the instruction;
* a register may be read before it is assigned on some path (definite
  assignment over the control-flow graph);
* a jump or branch targets a missing block, or a block has no terminator;
* a native call's argument count or kinds do not match the declaration, or the
  target kind does not match its return representation;
* a script call does not match the callee's parameters;
* a return does not match the function's return kind.

## Optimizer

`Optimizer` runs a pipeline of `IrPass`es; each must preserve semantics exactly,
including the order of side effects and runtime errors, and the result is
verified again. Implemented today: removal of unreachable blocks. Constant
folding of constant expressions is done earlier, by the binder. Planned passes
(constant and copy propagation, dead code elimination, branch folding, slot
reuse, superinstructions in the assembler) are listed in
[`../status.md`](../status.md); each will come with differential tests that run
programs with and without the pass and compare results.

## From IR to execution

The assembler (`tachyon-runtime`) maps registers to frame slots and emits the
packed `int[]` code described in [`interpreter.md`](interpreter.md). The planned
bytecode backend will translate the same verified IR to JVM classes.
