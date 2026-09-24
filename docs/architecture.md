# TachyonScript Architecture

This document describes how TachyonScript turns a `.tys` file into code that
runs inside a Paper or Folia server, and why the pieces are shaped the way they
are. It is the design reference for contributors. Implementation status of each
part is tracked separately in [`status.md`](status.md) so that this document can
describe the intended architecture without pretending that every part exists.

Major decisions have their own Architecture Decision Records in
[`decisions/`](decisions/).

## 1. Guiding rule

> Do the expensive work once, when a script is loaded — never on every execution.

Everything below follows from that rule. Names, types, members, overloads,
events, constants and static resources (materials, constant MiniMessage text) are
resolved by the compiler or the linker. At execution time the runtime only runs
prepared operations: it never parses text, scans patterns, searches registries
by name, or uses reflection.

## 2. Pipeline

```text
.tys source
   │  SourceFile (path, content, line index, hash)
   ▼
Lexer ─────────────► token list + comment trivia            tachyon-language
   ▼
Parser (recursive descent + Pratt) ► syntax tree (AST)       tachyon-language
   ▼
Binder / type checker ─► bound tree (semantic model)         tachyon-language
   ▼
Lowering ─────────────► typed register IR (CFG)              tachyon-compiler
   ▼
IR optimizer ─────────► optimized IR                         tachyon-ir
   ▼
IR verifier ──────────► verified IR                          tachyon-ir
   ▼
Assembler + linker ───► CompiledFunction (packed code,       tachyon-runtime
                        bound natives, linked constants)
   ▼
Generation ───────────► handler table, active events         tachyon-engine
   ▼
Platform ─────────────► Paper / Folia listeners, threading   tachyon-platform-paper
```

Every stage has one input and one output type, lives in its own package, and does
not reach back into earlier stages:

| Stage          | Input                     | Output                          |
|----------------|---------------------------|---------------------------------|
| Lexing         | `SourceFile`              | `LexResult` (tokens, comments)  |
| Parsing        | `TokenList`               | `SourceUnit` (syntax tree)      |
| Binding        | `SourceUnit` + registry   | `BoundModule` (semantic model)  |
| Lowering       | `BoundModule`             | `IrModule`                      |
| Optimization   | `IrModule`                | `IrModule`                      |
| Verification   | `IrModule`                | diagnostics (or success)        |
| Assembly       | `IrModule`                | `AssembledModule` (packed code) |
| Linking        | `AssembledModule` + bindings | `LinkedModule`               |
| Activation     | `LinkedModule`s           | `Generation`                    |

Diagnostics produced by any stage use the same `Diagnostic` model and always
point to a `SourceFile` span.

## 3. Modules

```text
tachyon-api              Stable, Bukkit-free API: type model, declarations,
                         native function interfaces, symbol registry.
tachyon-language         Source model, diagnostics, lexer, parser, syntax tree,
                         binder/type checker. Bukkit-free.
tachyon-ir               Typed register IR, builder, printer, verifier,
                         optimizer passes. Independent of the language frontend.
tachyon-compiler         Lowering (bound tree → IR) and the compilation driver
                         (multi-file compilation, timings, module graph).
tachyon-runtime          Assembler (IR → packed code), linker, interpreter,
                         execution stack, runtime errors, handler tables,
                         text service SPI.
tachyon-engine           Loading and transactional reload (generations),
                         event dispatch, error reporting, profiler, addon
                         assembly and the platform SPI.
tachyon-stdlib           Declarations of the standard library and the Minecraft
                         surface (types, members, events) plus the pure
                         implementations that need no server (math, strings).
tachyon-platform-paper   Paper/Folia bindings: implementations of the stdlib
                         declarations, event bridge, scheduler, text service.
tachyon-plugin           The Paper plugin: configuration, script directory,
                         /tys command, reload orchestration. Builds the jar.
tachyon-cli              Standalone `tys` tool (check, dump tokens/AST/IR/code,
                         docs).
tachyon-tests            End-to-end tests against an in-memory test platform.
tachyon-benchmarks       JMH benchmarks.
```

Dependency direction (arrows point to dependencies):

```text
plugin ─► platform-paper ─► engine ─► compiler ─► language ─► api
                │             │          └──────► ir ───────► api
                │             └─► runtime ─► ir
                └─► stdlib ─► api
cli ─► compiler, runtime, stdlib
tests (testkit) ─► engine, stdlib
benchmarks ─► tests, platform-paper
```

The rule that matters most: **nothing below `tachyon-platform-paper` depends on
Bukkit or Paper.** The lexer, parser, type checker, IR, optimizer, interpreter,
CLI and a future language server all run without a Minecraft server. They use
Tachyon-owned type descriptors, never `org.bukkit` classes.

Why `tachyon-compiler` exists separately from `tachyon-ir`: the IR is a target
that more than one producer can feed (tests build IR directly; a future
alternative front end could too), and the language frontend must stay usable by
tools (formatter, language server) that never need IR. Lowering is the only code
that knows both, so it lives in its own module.

Why there is no separate `tachyon-platform-folia` module: Paper's API already
contains the region-aware scheduler API (`GlobalRegionScheduler`,
`RegionScheduler`, `EntityScheduler`, `AsyncScheduler`) and ownership checks
(`Bukkit.isOwnedByCurrentRegion`). The same binding code therefore runs on Paper
and on Folia; what differs is *policy* (whether a global main thread exists),
which is selected once at startup through `PlatformCapabilities`. See
[ADR 0004](decisions/0004-unified-paper-folia-platform.md).

## 4. Declarations versus bindings

The compiler must know that `player.health` is a `double` property of
`LivingEntity` without loading Bukkit. The runtime must know how to read it on
Paper. These are two separate facts, kept in two separate places:

* A **declaration** (in `tachyon-api`, instances in `tachyon-stdlib` or in an
  addon) states the name, owner type, parameter types, return type, nullability,
  effects, threading requirement and documentation of an operation.
* A **binding** (in `tachyon-platform-paper`, in the test platform, or in an
  addon) supplies the `NativeFunction` implementation for a declaration.

Every bindable operation — global function, method, property getter, property
setter, event variable — is a `NativeDeclaration` with a stable textual key such
as `LivingEntity.health:get`. IR refers to declarations; the linker resolves each
referenced declaration to its binding exactly once per load. Missing bindings are
link errors, reported before a new generation is activated.

The same declarations drive documentation generation, CLI checking and future
IDE support, so there is one source of truth for the language surface. See
[ADR 0003](decisions/0003-declarations-and-bindings.md).

## 5. Language frontend

### Source model

`SourceFile` holds a logical path (relative to the scripts directory), the text,
a line-start index for O(log n) offset → line/column conversion, and a content
hash used for reload change detection and caching. Spans are `[start, end)` UTF-16
offsets into the file; line/column are computed only when rendering.

### Lexer

Hand written, single pass, produces a token list plus a comment list (kept as
trivia for doc comments and the future formatter). Notable behaviour:

* Newlines are emitted as `NEWLINE` tokens only when the innermost open bracket
  is a brace (or at top level). Inside `(...)` and `[...]` newlines are
  insignificant, which makes multi-line calls and lists work naturally.
* String interpolation is lexed into `TEMPLATE_START`, expression tokens,
  `TEMPLATE_MIDDLE`, …, `TEMPLATE_END`. Nothing about interpolation survives to
  runtime as text to parse.
* Numeric literals are range-checked; `1..10` is lexed as a range, not `1.`.

### Parser

Hand-written recursive descent for declarations and statements with a Pratt
parser for expressions. Binding powers are documented in
[`compiler/parser.md`](compiler/parser.md). Recovery uses synchronization points
(newline, `;`, `}`, declaration keywords) and suppresses cascading errors. Deep
nesting is bounded to protect the server from pathological input. See
[ADR 0002](decisions/0002-hand-written-parser.md).

### Binder and type checker

The binder resolves names through a scope chain (block → function → module →
registry), checks types bidirectionally (an expected type flows into literals,
templates and empty collections), resolves overloads, inserts explicit
conversion nodes, and performs flow-sensitive narrowing for `x != null` and
`x is T`. Its output is a *bound tree* in which every expression carries its
resolved type and every name its resolved symbol. Lowering never has to look
anything up by name.

## 6. Intermediate representation

The IR is a typed, register-based three-address code organised as a control-flow
graph of basic blocks. Each function has an unlimited set of virtual registers,
each with a fixed kind (`I32`, `I64`, `F32`, `F64`, `BOOL`, `REF`). Instructions
are specialised by type (`I32_ADD`, `F64_LT`, …), native calls reference
declarations directly, and every instruction carries its source span.

Why registers rather than a stack machine: fewer dispatches per source operation
in the interpreter, data-flow optimizations (constant propagation, dead code
elimination) work directly on named values, the form converts to SSA if that is
ever justified, and lowering to JVM bytecode stays trivial because each register
becomes a JVM local. The verifier checks the register-machine equivalents of
stack consistency: register kinds, definite assignment, valid block targets,
argument counts and kinds against native signatures, and return kinds. See
[ADR 0001](decisions/0001-register-based-ir.md).

## 7. Runtime

### Assembly and linking

The assembler turns an IR function into a compact `int[]` instruction stream,
allocates frame slots for registers, and records a pc → source span table. The
linker then materialises everything that needs the platform exactly once:
native bindings become direct references, message templates are compiled by the
platform text service, type tests become `Class` references, and calls between
script functions become direct references to `CompiledFunction` objects.

### Interpreter

One `switch` loop over the packed code. Frames live in a per-thread
`ExecutionStack` holding a `long[]` for all primitive registers (ints, longs,
booleans, float and double bits) and an `Object[]` for references, so executing a
script allocates nothing for locals and never boxes primitives. Reference slots
are cleared when a frame is left so that the stack never retains players, worlds
or events. See [ADR 0005](decisions/0005-interpreter-frame-layout.md).

Native calls receive an `Arguments` view that reads directly from the caller's
registers — no argument arrays are allocated. Script-to-script calls copy
arguments into the callee's window of the same stack.

Safety checks are placed where they are cheap: loop back-edges decrement a
counter and only consult the clock when it runs out; calls check a recursion
depth counter. Straight-line code pays nothing.

### Errors

Exceptions thrown while executing are converted into `ScriptRuntimeException`
carrying a TachyonScript stack trace (function, file, line, column, source
excerpt). The Java cause is kept for debug mode only. Reporting is rate-limited
per error site.

### Generations and reload

A `Generation` (`tachyon-engine`) is the immutable result of one load: the loaded
scripts with their linked code, a handler table indexed by event, and the set of
events that have handlers. Reload is transactional
([ADR 0007](decisions/0007-transactional-reload.md)):

1. read all sources and hash them, 2. compile the changed ones, 3. verify,
4. assemble and link — and then 5. build the complete new generation and publish
it with one volatile write. The platform is told which events now have handlers
and adjusts its listeners.

In lenient mode a failing script keeps its previous working version; in strict
mode any failure keeps the whole previous generation. Event threads read the
current generation once per event, so dispatch needs no locks, and an event is
handled entirely by one generation.

Planned: commands and scheduled tasks will belong to a generation too and be
retired with it; persistent data (`playerdata`, `persistent`) will survive reloads.

## 8. Threading and ownership (Paper and Folia)

Scripts are written without knowledge of Folia's regions. Event handlers run on
the thread that fired the event, which on Folia is the owner of the event's
entity or location ([ADR 0004](decisions/0004-unified-paper-folia-platform.md)).

The engine is thread-safe by construction: compiled code is immutable, each thread
has its own execution stack, and the active scripts are one immutable generation.

Bindings that change state go through the platform's `Threading` interface:
`forEntity(entity, action)` runs the action immediately when the current thread
owns the entity and otherwise schedules it on the entity's scheduler (Folia) or
the main thread (Paper); `global(action)` does the same for world-wide state such
as the time of day. Teleports use `teleportAsync` on Folia.

Each native declaration also states its requirement (`ThreadingRequirement`:
`GLOBAL`, `ENTITY`, `REGION`, `ASYNC`, ...). The compiler does not use it yet;
scheduling constructs (`after`, `every`, `async`) will be built on the same
abstraction.

## 9. Text and MiniMessage

A string template used where a `Component` is expected is compiled once: its
literal parts are MiniMessage, and interpolated values are inserted as plain text
(never re-parsed as MiniMessage). This is both faster and safe against tag
injection from player-controlled text. Compile-time constants are substituted
into the template text before it is compiled, so `const PREFIX = "<gold>[S]"`
formats as expected. A template without runtime values becomes a constant
component, created once at link time. See
[ADR 0006](decisions/0006-message-templates.md).

## 10. Extension API

Addons implement `TachyonAddon` (`tachyon-api`): they declare types, global
functions, members and events, bind them, and name the platform event class of
each new event. Registration is structured (types and signatures), never parser
patterns. On Paper, addons obtain an `AddonRegistrar` from Bukkit's services
manager during their `onEnable`; TachyonScript loads scripts on the first tick
after startup.

`AddonAssembly` (`tachyon-engine`) checks each addon against a fresh registry
built from the standard library and the addons accepted before it. An addon whose
declarations conflict, that throws, that leaves something unimplemented, binds
something it did not declare, or declares an event without a valid event class is
rejected as a whole with a message naming it; the others load normally. The
result is frozen into the immutable registry every compilation uses. See
[`addons/getting-started.md`](addons/getting-started.md).

## 11. Storage (planned)

Persistent variables (`playerdata`, `persistent global`) follow
memory cache → dirty tracking → asynchronous batched writes → pluggable backend
(SQLite, MySQL/MariaDB). Script code never waits for a database; keys are stable
schema identifiers derived from module and variable names, never compiler IDs.

## 12. Bytecode backend (planned)

Verified IR is also the input of the planned ASM backend. Registers map to JVM
locals, native calls become calls through constant `NativeFunction` fields (which
the JIT can inline because each generated call site is monomorphic), and each
generation is loaded in its own class loader so that a reload can release all
generated classes.
