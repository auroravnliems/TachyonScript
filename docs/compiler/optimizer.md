# IR optimizer

Runtime 0.7.0-SNAPSHOT retains language level 2 and IR format 2. Optimization is
enabled by default. It transforms verified IR and verifies the final result;
debug observers also verify every intermediate module. Passes allocate new blocks
and functions, with no mutable process-wide state.

## Pipeline

| Stable identifier | Algorithm and boundary |
|---|---|
| `unreachable-blocks` | Walk normal and handler edges from entry, retain reachable blocks and renumber all targets. Runs first and last. |
| `copy-propagation` | Substitute aliases within one block. A register write kills both its own alias and aliases referring to its previous value. Only identical types propagate. |
| `constant-propagation` | Track constants within one block; evaluate Java primitive operations with the interpreter's overflow, shift, floating-point and equality semantics. Fold known branches. No facts cross a join, loop header or handler. |
| `dead-code` | Backward worklist liveness over normal and exceptional predecessors. Remove unused, explicitly discardable instructions and overwritten local stores. |
| `jump-threading` | Follow empty forwarding blocks with cycle detection. Never bypass a jump marked as a watchdog back edge. |

The constant table distinguishes an unknown value from known `null`. Integer
division/remainder by zero is left in place to fail at its original source span.
Floating-point folding uses the same operations as execution, preserving signed
zero, infinities and NaN comparisons without algebraic reassociation.

## Observable behavior

Liveness includes a handler's live-in registers at **every instruction boundary**
of its protected blocks. A handler may observe a local before a later store, even
when the local is dead on normal exit. The analysis conservatively retains values
assigned before entry too, matching the verifier's definite-assignment contract.

Discardability is intentionally narrower than `Instruction.hasSideEffects()`.
Native/script/closure calls, globals, player data, collection operations, checked
casts, unboxing, host equality and string conversion remain executable even when
their result is unused. Host `equals` or `toString` can execute code or throw.
Allocation and reference operations are retained unless explicitly covered by the
discardability contract. No call is reordered or memoized across scheduling.

Loop back edges retain their flag and source span. Empty infinite loops still hit
the watchdog. Timeout/recursion failures remain uncatchable. The assembler assigns
slots only to referenced registers and all parameters, including unused parameters;
it does not yet merge live ranges or change the call ABI.

## Debugging

```sh
tys dump ir script.tys --no-optimize
tys dump ir script.tys
tys dump code script.tys
tys dump passes script.tys --disable-pass=dead-code
tys check scripts --disable-pass=constant-propagation --disable-pass=jump-threading
```

`dump passes` prints `input` then each enabled pass (including both unreachable
passes). Unknown names are usage errors. Flags apply only to that CLI invocation;
they do not modify server configuration. `CompilerOptions.withOptimization` and
`withDisabledPasses` provide the same switches to API consumers; the original
three-argument constructor remains available.

## Evidence and limits

`OptimizerDifferentialTest` compiles real source, links fresh state and executes
unoptimized, fully optimized, each-pass-only and each-pass-disabled variants.
The fixed corpus also executes every intermediate snapshot. It compares return
values, runtime error kind/message/source trace, native log order, globals and
reference cleanup. Coverage includes loops, joins, closures/captures, recursion,
records, nullability/casts, collections, handlers and finally exits, host effects,
floating-point boundaries and integer overflow. Watchdog cases exercise empty
loops under every configuration.

Ninety-six generated programs use seeds beginning at hexadecimal `54414348594f4e`,
compare against an independent Java arithmetic oracle, then test every variant.
A failure includes its seed and source so it can become a minimized regression.
`OptimizerEngineTest` compares imports, persistent/player state, commands, event
cancellation, async/sync handoff and callback retirement during reload.

These tests do not prove equivalence for every possible program or addon. There
is no global constant propagation, CSE, strength reduction, live-range slot reuse
or superinstruction pass in this release. Native blocking is still outside the
watchdog's interruption capability. Performance claims require the same-machine
on/off JMH results in [benchmarks](../benchmarks.md).
