# Implementation status

What exists today, what is partial, and what is planned. This page is updated with
every milestone; anything not listed as implemented should be assumed missing.
Version: 0.2.0-SNAPSHOT (language level 2).

## Summary

| Area | Status |
|------|--------|
| Lexer, parser, diagnostics | Implemented |
| Type checker (types, overloads, null safety, narrowing, constants, records, lambdas, modules) | Implemented |
| Typed register IR, verifier (exception handlers, closures, globals) | Implemented |
| IR optimizer | Partial: unreachable-block removal only |
| Interpreter (packed code, zero-allocation frames, watchdog, exception tables) | Implemented |
| Engine: transactional reload, module graph, generations, dispatch by priority, errors, profiler | Implemented |
| Commands, scheduling, lifecycle hooks, daily tasks | Implemented |
| Saved variables (`persistent`, `playerdata`) with SQLite / MySQL storage | Implemented |
| Databases in scripts (`Database`, `Sql`, async queries) | Implemented |
| Standard library (~150 types, ~900 members, 111 events), generated from specifications | Implemented |
| Menus, boss bars, sidebars, titles, tab list, text displays | Implemented |
| Vault and PlaceholderAPI integration | Implemented |
| Paper/Folia platform | Implemented; verified on a live Paper 1.21.11 server (self-test script); Folia not yet run live |
| Plugin: `/tys`, config, permissions, async reload | Implemented |
| Addon API | Implemented (may change before 1.0) |
| CLI (`tys check`, `tys dump`, `tys docs [--wiki]`) | Implemented |
| Documentation: `docs/`, GitHub wiki in `wiki/` (every example compiled by the tests) | Implemented |
| JMH benchmarks | Implemented ([results](benchmarks.md)) |
| Bytecode backend | Planned |
| Language server, formatter | Planned |

## Language

* Declarations: `event` (with `@priority`, `@ignoreCancelled`), `command` (typed
  arguments, defaults, `string...`, sub-commands, `@permission`, `@cooldown`,
  `@playerOnly`, ...), `function` (defaults, recursion), `record`, `const`, script
  `let`/`var`, `persistent var`, `playerdata var`, `placeholder`, `on load`,
  `on unload`, `every <duration>` (optionally `@async`), `at "HH:mm"`, `module`,
  `import` (`as`, `{a, b} from`).
* Statements: `let`, `var`, assignments and `+= -= *= /= %= &= |= ^= <<= >>=`, `++`,
  `--`, `if`/`else`, `while`, `for x in list|range|map` (`for k, v in map`), `switch`,
  `try`/`catch`/`finally`, `throw`, `break`, `continue`, `return`, `after`, `every`
  (with `task`), `async`, `sync`.
* Expressions: arithmetic with numeric promotion, bitwise operators, comparisons,
  `&& || !`, `c ? a : b`, `in`/`!in`, `switch` expressions, templates, `?.`, `??`,
  `is`, `!is`, `as`, `as?` (also to `List<T>` and `Map<K, V>`), list and map literals
  and indexing, lambdas, calls of function values, record construction, constants of
  keyed types (`Material.DIAMOND`).
* Types: `int long float double bool string Component Duration Instant List<T>
  Map<K, V> function(A...): R T? any`, records, and the Minecraft types of the standard
  library.

## Compiler and runtime

* Incremental reload by content hash with a module graph: changed modules and the
  modules importing them are recompiled.
* Optimizer: only removal of unreachable blocks. Constant expressions are folded by
  the type checker. Planned: constant/copy propagation, dead code elimination, branch
  folding, slot reuse, superinstructions.
* The interpreter is the only backend; `runtime.backend: bytecode` is accepted in the
  config but falls back to the interpreter with a warning.
* Safety limits: call depth and execution time per event (checked on loop back edges).
  A native call that blocks (a slow addon) cannot be interrupted.

## Known limitations

* Folia support is implemented on the region schedulers but has not been run on a
  live Folia server yet. Sidebars are not available on Folia.
* Writes to an entity or block that the current thread does not own are applied on the
  owner's next tick; reading the value immediately afterwards returns the old value.
* Operations returning a new entity (`spawn`, `dropItem`) called from another region's
  thread on Folia report an error; use `after 1 tick for <entity> { }`.
* The profiler measures whole handlers, not individual statements.
* Modules are not published to a Maven repository; addons compile against the plugin jar.

## Next milestones

1. Live Folia test and CI integration tests on Paper and Folia.
2. Optimizer passes with differential tests; then the ASM bytecode backend.
3. Language server (diagnostics, completion) using the same compiler.
