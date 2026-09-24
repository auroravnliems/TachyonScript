# ADR 0005: Interpreter frame layout

## Context

A naive tree-walking or `Object[]`-frame interpreter boxes every number and
allocates frames per call. On a Minecraft server that turns into garbage
collection pressure in the hottest paths (movement events fire for every player
many times per second).

## Decision

* The assembler packs each function into an `int[]`: an opcode followed by its
  operands (frame slots, constant-pool indexes, jump targets).
* Every thread has one `ExecutionStack` with a `long[]` for primitive slots
  (ints, longs and booleans directly, floats and doubles as raw bits) and an
  `Object[]` for reference slots. A call reserves a window in both arrays; there
  is no frame object.
* Registers of kind `REF` get reference slots; all other kinds get primitive
  slots. Arithmetic never boxes.
* Natives receive an `Arguments` view (one per call depth, reused) that reads the
  caller's slots directly.
* Reference slots are cleared when a frame is left, so the stack never keeps
  players, worlds or events alive.
* The dispatch loop is one `switch` over the opcode. Its bytecode size is kept
  below HotSpot's limit for compiling huge methods (8000 bytes); a test enforces
  this, because an interpreter loop the JIT refuses to compile is an order of
  magnitude slower.
* Safety checks are placed where they are cheap: loop back edges (`LOOP`)
  decrement a counter and only read the clock when it runs out; calls check the
  depth counter.

## Consequences

* Executing a handler allocates nothing except what the script itself creates
  (strings, lists, components).
* Values crossing the native boundary are typed by representation, which the
  compiler guarantees.
* The layout maps directly to JVM locals for the planned bytecode backend.
