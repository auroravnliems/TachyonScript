# Changelog

All notable changes are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/) once 1.0 is released.

## [Unreleased] — 0.1.0-SNAPSHOT

First development version.

### Language

- Declarations: `event`, `function`, `const`; statements `let`, `var`, assignments,
  `if`/`else`, `while`, `for ... in` (lists, ranges), `break`, `continue`, `return`.
- Static types: `int`, `long`, `float`, `double`, `bool`, `string`, `Component`,
  `Duration`, `List<T>`, nullable `T?`, `any`, and the Minecraft types of the standard
  library; overload resolution; null safety with flow-sensitive narrowing; `is`,
  `as`, `as?`, `?.`, `??`; compile-time constants.
- String templates; MiniMessage templates compiled once with values inserted as
  plain text. Runtime text is never parsed as MiniMessage implicitly (`TYS0234`).
- Diagnostics with stable codes, source excerpts, expected/received types and
  "did you mean" suggestions; planned features are reported as "not supported yet".

### Compiler and runtime

- Hand-written lexer and parser with error recovery and nesting limits.
- Typed register IR with a verifier; unreachable-block removal.
- Interpreter over packed `int[]` code with per-thread primitive/reference frames,
  zero-allocation native calls, recursion and execution-time limits, and
  TachyonScript stack traces.
- Engine with transactional reload (lenient and strict modes), per-file
  incremental recompilation, lock-free dispatch, rate-limited error reporting,
  slow-execution warnings and a profiler.
- Addon API: `TachyonAddon`, `AddonRegistrar`, with isolated validation of each
  addon.

### Platform and tools

- Paper and Folia platform: bindings for the standard library, one listener per
  handled event, owner-thread routing of entity and world writes, pre-parsed
  Adventure templates.
- Plugin with `/tys help|reload|scripts|info|errors|profile|dump|version`,
  per-subcommand permissions, validated configuration and crash reports for
  internal compiler errors.
- `tys` CLI: `check`, `dump tokens|ast|bound|ir|code`, `docs`.
- JMH benchmarks for the interpreter, event dispatch, message templates and the
  compiler.

### Performance

- The execution watchdog reads the clock lazily, which roughly halved the cost of
  running a short handler (77.6 → 44.3 ns/op on the development VM).
- Templates are parsed without MiniMessage's compaction so that they are really
  pre-parsed.
