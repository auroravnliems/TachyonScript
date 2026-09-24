# Implementation status

What exists today, what is partial, and what is planned. This page is updated with
every milestone; anything not listed as implemented should be assumed missing.
Version: 0.1.0-SNAPSHOT (language level 1).

## Summary

| Area | Status |
|------|--------|
| Lexer, parser, diagnostics | Implemented |
| Type checker (types, overloads, null safety, narrowing, constants) | Implemented |
| Typed register IR, verifier | Implemented |
| IR optimizer | Partial: unreachable-block removal only |
| Interpreter (packed code, zero-allocation frames, watchdog) | Implemented |
| Engine: transactional reload, generations, dispatch, errors, profiler | Implemented |
| Standard library (text, math, strings, players, worlds, 7 events) | Implemented, small |
| Paper/Folia platform | Implemented; compiled against Paper API 1.21.11 and tested with fakes, **not yet run on a live server by CI** |
| Plugin: `/tys`, config, permissions, async reload | Implemented |
| Addon API | Implemented (new; may change before 1.0) |
| CLI (`tys check`, `tys dump`, `tys docs`) | Implemented |
| JMH benchmarks | Implemented ([results](benchmarks.md)) |
| Commands, scheduling, persistent data, modules/imports | Planned |
| Bytecode backend | Planned |
| Language server, formatter | Planned |

## Language

Implemented:

* declarations: `event`, `function` (typed parameters, optional return type,
  recursion, calls before declaration), `const` (compile-time evaluated);
* statements: `let`, `var`, assignment and `+= -= *= /= %=`, `if`/`else if`/`else`,
  `while`, `for ... in` over lists and ranges (`a..b`, `a..<b`), `break`,
  `continue`, `return`;
* expressions: arithmetic with numeric promotion, comparisons, `&& || !`,
  string templates `"...{expr}..."`, `+` concatenation, `?.`, `??`, `is`, `as`,
  `as?`, list literals and indexing, method calls, properties, durations
  (`5 seconds`, `20 ticks`);
* types: `int long float double bool string Component Duration List<T> T? any`
  and the Minecraft types of the standard library;
* null safety with flow-sensitive narrowing; definite return analysis;
  unreachable-code, unused-variable and deprecation warnings;
* MiniMessage templates compiled once, values inserted as plain text; runtime
  text is never parsed as MiniMessage implicitly (`TYS0234`);
* diagnostics with stable codes, source excerpts, labels, expected/received types
  and "did you mean" suggestions.

Recognised but reported as "not supported yet" (planned):

* `command` declarations, `after`/`every`/`async`/`sync` scheduling, `cooldown`;
* `playerdata`, `persistent` global variables (storage);
* `module`/`import` (sharing code between files);
* `on load` / `on unload` hooks, `gui`, custom `eventtype`s;
* map literals and `Map<K, V>` operations; `try`/`catch`/`throw` (keywords are
  reserved);
* user-defined types, lambdas.

## Standard library

Players (messages, permissions, health, food, level, game mode, display name,
kick, teleport), entities, living entities, worlds (players, time), locations,
blocks (type, location), game modes, `server.*`, `broadcast`, `log.*`, `text.*`
(MiniMessage, plain text, escaping), `math.*`, string members, list operations.

Events: `player.join`, `player.quit`, `player.death`, `player.chat`,
`player.move`, `block.break`, `entity.damage`, with their event-object members
(cancel, join/quit/death messages, keep inventory, damage).

Not yet: items and inventories, materials as values, block placement and
changes, potion effects, sounds, titles/action bars/boss bars, scoreboards,
entities spawning, most events. The complete current surface is the generated
[reference](language/reference.md).

## Compiler and runtime

* Incremental reload by content hash: only changed files are recompiled; there is
  no module graph yet (there are no imports).
* Optimizer: only removal of unreachable blocks. Constant expressions are folded
  by the type checker. Planned: constant/copy propagation, dead code elimination,
  branch folding, slot reuse, superinstructions — each with differential tests.
* The interpreter is the only backend; `runtime.backend: bytecode` is accepted in
  the config but falls back to the interpreter with a warning.
* Safety limits: call depth (`safety.recursion-limit`), execution time per event
  (`safety.max-execution-time-ms`, checked on loop back edges). A native call
  that blocks (a slow addon) cannot be interrupted.
* `ThreadingRequirement` is declared on every native but not yet used by the
  compiler; the Paper bindings enforce ownership themselves by routing writes to
  the owning thread.

## Plugin and platform

* Paper and Folia with one code path; Folia detected from the API.
* One Bukkit listener per event that has handlers; none otherwise.
* `/tys help | reload [script] | scripts | info <script> | errors | profile start|stop|report | dump <script> [ir|code] | version`,
  with per-subcommand permissions.
* Reloads compile asynchronously and activate atomically; lenient and strict
  modes; failing scripts keep their previous version.
* Runtime errors are rate-limited per script location; internal compiler errors
  write a crash report to `plugins/TachyonScript/logs/`.
* Configuration is read at startup; changing `config.yml` needs a restart.

## Known limitations

* **No live-server test yet.** The platform is compiled against the Paper API
  sources of 1.21.11 and its bindings run in tests against real Bukkit event
  classes with faked players and worlds, but the plugin has not been exercised on a
  running Paper or Folia server by CI. That is the next milestone.
* Writes to an entity that the current thread does not own (Folia, or async
  events on Paper) are applied on the owner's next tick; reading the value
  immediately afterwards returns the old value.
* The profiler measures whole handlers, not individual statements.
* Modules are not published to a Maven repository; addons compile against the
  plugin jar.
* In-memory state does not survive reloads by design; persistent storage does
  not exist yet.

## Next milestones

1. Live-server integration tests (Paper and Folia) in CI; first public release.
2. Commands and scheduling (`command`, `after`, `every`, `async`) on the region
   scheduler abstraction.
3. Persistent data (`playerdata`, `persistent`) with an async write-behind store.
4. Modules and imports with a module graph for incremental compilation.
5. Optimizer passes with differential tests; then the ASM bytecode backend.
6. Language server (diagnostics, completion) using the same compiler.
