# Interpreter

The interpreter (`dev.tachyonscript.runtime.interpreter`) executes assembled code.
The design goals and the frame layout are recorded in
[ADR 0005](../decisions/0005-interpreter-frame-layout.md); this page describes
the format.

## Packed code

The assembler turns each verified IR function into a `CodeUnit`:

* `int[] code` — an opcode followed by its operands;
* frame sizes (primitive and reference slots), parameter slots;
* constant pools (long bits, references), native declarations, called
  functions, classes for type tests, message templates;
* a pc → source span table for error locations.

Operands are frame-relative slot numbers in one of two spaces: `p` (primitive
`long` slots holding ints, longs, booleans and float/double bits) and `r`
(references). Blocks are laid out in IR order; jumps to the next block become
fall-throughs and branches choose the form that falls through. Loop back edges
become `LOOP`, the only instruction that checks the execution time budget.

The opcode list is in `Opcodes`; `tys dump code` and `Disassembler` print it:

```text
function greet [greet(Player, int)] (r0, p0) -> void  [p: 4, r: 4]
     0  CONST_I         p1, 0
     3  LT_I            p2, p1, p0
     7  BR_F            p2, -> 38
    10  CALL_NATIVE_R   r1 = Entity.name:get(r0)
    15  I2S             r2, p1
    18  TEMPLATE        r3 = template#0 "<green>Hi {} #{}"(r1, r2)
    24  CALL_NATIVE_V   CommandSender.send(Component)(r0, r3)
    29  CONST_I         p3, 1
    32  ADD_I           p1, p1, p3
    36  LOOP            -> 3
    38  RET_V
```

## Linking

`Linker.link` turns code units into `CompiledFunction`s against a platform:
native declarations become direct `NativeFunction` references, classes become
`Class` objects, templates are compiled by the platform text service (constant
templates are rendered once), and script calls become direct references to the
callee. Anything missing is a link error; nothing is looked up by name while
running.

## Execution

* `Interpreter.invokeHandler(function, event)` runs an event handler;
  `Interpreter.call(function, args...)` calls any function with boxed values
  (tools and tests).
* Each thread has an `ExecutionStack`; a call reserves a window in its two
  arrays and copies arguments into the callee's parameter slots. Reference slots
  are cleared on return.
* Natives receive an `Arguments` view over the caller's slots.
* Errors become `ScriptRuntimeException` with a TachyonScript stack trace (script
  function, file, line, column, source line). Java exceptions thrown by natives
  are wrapped with the native's key; `ScriptError` from a native becomes a
  normal script error message.

## Limits

* **Call depth**: every call checks the depth against `recursion-limit`
  (default 128).
* **Execution time**: `LOOP` decrements a counter; every 1024 back edges it
  reads the clock. The first check starts the clock, later checks stop the
  execution once `max-execution-time-ms` (default 1000) has passed. Code without
  loops never reads the clock.
* The dispatch loop's bytecode must stay below HotSpot's 8000-byte limit for
  compiling large methods; `JitFriendlinessTest` enforces it.
