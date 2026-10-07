# Implementation status

What exists today, what is partial, and what is planned. This page is updated with
every milestone; anything not listed as implemented should be assumed missing.
Version: 0.7.0-SNAPSHOT (language level 2, IR format 2).

The 2026-10-05 security hotfix retained 0.5.1 at the user's explicit request.
AI failure policy now defaults to `warn`, including migration from legacy
`ai.required: true`. Provider outages use a shared cooldown and aggregated alerts;
deterministic quarantine remains enforced. See [security policy](security.md).
This version integrates the optimizer work with that hotfix; historical artifacts remain separate.
It is also lighter than 0.5.1: AI reviews run in the background and are stored, review
requests are about 5x smaller, the interpreter no longer checks revocation on every
instruction, the jar no longer bundles OkHttp/Kotlin (5.0 MB to 1.9 MB) and security
memory is bounded. See the [changelog](../CHANGELOG.md) for measurements.

## Summary

| Area | Status |
|------|--------|
| Lexer, parser, diagnostics | Implemented |
| Type checker (types, overloads, null safety, narrowing, constants, records, lambdas, modules) | Implemented |
| Typed register IR, verifier (exception handlers, closures, globals) | Implemented |
| IR optimizer | Implemented conservative pipeline; [algorithms, differential tests and limits](compiler/optimizer.md) |
| Interpreter (packed code, zero-allocation frames, watchdog, exception tables) | Implemented |
| Engine: transactional reload, module graph, generations, dispatch by priority, errors, profiler | Implemented |
| Commands, scheduling, lifecycle hooks, daily tasks | Implemented |
| Saved variables (`persistent`, `playerdata`) with SQLite / MySQL storage | Implemented |
| Databases in scripts (`Database`, `Sql`, async queries) | Implemented |
| Standard library (111 events, typed extensions), generated from specifications | Implemented; see the exact [API reference](language/reference.md) |
| Menus, boss bars, sidebars, titles, tab list, text displays | Implemented |
| Vault and PlaceholderAPI integration | Implemented |
| Paper/Folia platform | Core probe passed live Paper 1.21.11 build 132 (28 checks) and Folia build 14 (32 checks) with each backend; [scope](integration.md) |
| Plugin: `/tys`, config, permissions, async reload | Implemented |
| Addon API | Implemented (may change before 1.0) |
| CLI (`tys check`, `tys dump`, `tys docs [--wiki]`) | Implemented; pass dumps and optimizer switches |
| Documentation: `docs/`, GitHub wiki in `wiki/` (every example compiled by the tests) | Implemented |
| JMH benchmarks | Implemented ([results](benchmarks.md)) |
| Bytecode backend | Implemented, opt-in (`runtime.backend: bytecode`); the interpreter stays the default |
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
* Selected-file reload uses active sources for importers and leaves unrelated disk
  edits alone. Persistent emergency stop revokes scripts and their dependents.
* ScriptMenu click/drag views are isolated from generic handlers at every priority.
* Typed statistics, metadata, BlockData, signs and display/math extensions are
  implemented. The [Skript audit](skript-audit/README.md) is partial, with 912 source
  classes still unreviewed; no full parity claim is made.
* Optimizer: block-local constant/copy propagation, arithmetic/comparison and branch
  folding, exception-aware liveness/DCE, jump threading and unreachable removal.
  Unused registers do not consume frame slots. Global propagation, live slot reuse,
  CSE and superinstructions remain unimplemented; these require separate measurements.
* Two backends run the same assembled code: the interpreter (default) and, with
  `runtime.backend: bytecode`, one JVM hidden class per function that HotSpot compiles
  (loops about 11x and calls about 2.8x faster, loading slower). A function too large
  for HotSpot to compile, or one whose generation fails, stays interpreted with a console
  warning. Equivalence is tested on the optimizer corpus and generated programs.
* Safety limits: call depth and execution time per event (checked on loop back edges
  and function entries, so recursion without loops also times out). A native call that
  blocks (a slow addon) cannot be interrupted.
* Security revocation is checked at function entry, before every native call and with
  the watchdog's loop check (not per instruction): a revoked script reaches no native.
* AI security reviews run in the background by default and are stored by exact input
  (`security/ai-reviews.json`); a changed script keeps its previous version until its
  review finishes, then activates by itself.

## Known limitations

* Live Folia coverage includes two regions, reads/writes, teleport, spawn/drop,
  scheduling, reload and SQLite shutdown. Client menus, player disconnection, MySQL,
  Vault/PlaceholderAPI and sustained load still need a Folia-specific live suite.
  Sidebars are not available on Folia. The new CI workflow has not yet run remotely.
* Writes to an entity or block that the current thread does not own are applied on the
  owner's next tick. An ownership-sensitive read from another tick thread fails;
  an async caller waits for the owner. Neither is a write-completion guarantee.
* Operations returning a new entity (`spawn`, `dropItem`) called from another region's
  thread on Folia report an error; use `after 1 tick for <entity> { }`.
* The profiler measures whole executions (handlers, commands, tasks, blocks, placeholders),
  not individual statements.
* Modules are not published to a Maven repository; addons compile against the plugin jar.

## Next milestones

1. Extend the live Folia probe to player clients/menus, integrations and load; observe the new CI matrix.
2. Gather live evidence with the bytecode backend before making it the default; broaden
   optimizer coverage as features evolve.
3. Language server (diagnostics, completion) using the same compiler.
