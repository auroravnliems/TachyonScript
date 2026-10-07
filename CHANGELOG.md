# Changelog

All notable changes are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/) once 1.0 is released.

## [0.7.0-SNAPSHOT] — 2026-10-07

### Fixed: a damaged security journal no longer stops TachyonScript

- After the AI reviewer was switched off and on again (with restarts), a server could stop
  with `TachyonScript failed to start (java.io.IOException: Security audit integrity check
  failed; activation blocked)`, or `… Invalid incomplete security report …`, and no script
  ran until the `security/` folder was repaired by hand. Stopping the engine interrupts the
  thread that applies finished background reviews, and an interrupted thread closes its file
  channel in the middle of a write. That left an empty write-ahead report, or a journal
  record on disk that the in-memory chain never learned about, so the next record pointed at
  the wrong predecessor. With the 0.6.0 jar, 22 of 300 commits interrupted while writing
  broke the chain and 17 left an empty report; with this version none did, and a stress test
  that switches the reviewer and restarts mid-review 300 times found 2 empty reports before
  and none after.
- Security storage writes run on a thread that nothing interrupts. A failed journal append
  is cut off again, a failed report is removed, and the journal is checked before every
  append: one changed underneath the running server (an older copy of the folder uploaded
  over it, or a record whose failed write still reached the disk) is verified and recovered
  instead of being extended into a broken chain.
- Damage is recovered at startup instead of blocking activation. The damaged journal is kept
  as `security/audit-damaged-<UTC time>.jsonl`, the records before the damage stay, and the
  rest is rebuilt from the write-ahead reports in time order. Quarantines and disables are
  never lost: one found only in the damaged part is kept too, while approvals and restores
  need their report. Unreadable reports are renamed `*.report.json.unreadable`. Each
  recovery is logged as SEVERE and repeated by `/tys security status`. A folder damaged by
  0.6.0 recovers on the first start of this version; nothing has to be done by hand.
- Recovered reports are replayed by time instead of by file name, so a restore is no longer
  undone by an older quarantine whose random ID sorts later. Unreadable Discord outbox lines
  are skipped instead of stopping startup. A closed audit store refuses writes.

### JVM bytecode backend (opt-in)

- `runtime.backend: bytecode` (restart to change) translates every script function into a
  JVM class that HotSpot compiles to machine code. The generated method has no instruction
  dispatch loop; it works on the interpreter's own frame, calls and natives, and shares its
  helpers for strings, lists, maps and records, so behaviour, errors, `try`/`catch`, the
  watchdog and security revocation are the same. The default stays `interpreter`.
- JMH on the same machine (2 forks): integer loop 30.1 → 2.75 µs, double loop 29.8 → 2.77 µs,
  recursive fib(20) 815 → 292 µs, one event handler 74 → 45 ns. Loading costs more: the 32
  scripts of a real server archive (371 functions) take 148–270 ms extra to generate and define.
- A function whose JVM body would exceed HotSpot's 8,000-byte compilation limit, or whose
  generation fails, stays interpreted and the console says which one; none of the archive's
  functions did. Classes are hidden classes named after their script and function: a reload
  releases them, and profilers and stack traces show `.tys` line numbers.
- `tys dump bytecode <file>` prints the classes the backend would define.
- Equivalence: 80 tests run the optimizer's differential corpus and 64 generated programs on
  both backends and verify every class with ASM's `CheckClassAdapter`; the engine suite passes
  with `-Ptachyon.backend=bytecode`. The live Paper/Folia probe runs with either backend
  (`validation/integration/run.py --backend bytecode`).

## [0.6.0-SNAPSHOT] — 2026-10-06

### Lighter and faster than 0.5.1

Measured on an Intel i3-10105F with JDK 21.0.12.1; the AI and analyzer figures use a
real 32-script server archive (read only: nothing was run or sent anywhere).

- **AI security reviews no longer block loading.** With the new default
  `security.ai.review-mode: background`, a reload returns at once; a changed script keeps
  running its previous version until its review finishes, then activates by itself
  (importers wait with it). Before, one changed script made a reload wait about 18 s and
  a restart reviewed every script one after another. `blocking` restores the old way.
- **Finished reviews are stored** in `security/ai-reviews.json` by exact input, so an
  unchanged script is never sent again, also after a restart. Reviews of the same
  revision no longer produce new warning incidents each restart.
- **Review requests are about 5× smaller** (6.0 MB → 1.2 MB for the archive; its largest
  script 1.4 MB in two requests → 156 KB in one): pure calls and property reads without
  untrusted data are counted, not sent; shared code is sent once with all contexts;
  paths and positions are compact. Scripts with nothing to review skip the provider.
  At most `max-concurrent-reviews` (default 2) run at once.
- **Interpreter 1.5–1.9× faster.** 0.5.x checked script revocation before every
  instruction; it is now checked at function entry, before every native call and with
  the watchdog's loop check, which keeps the guarantee that a revoked script reaches no
  native. JMH with optimization on: integer loop 50.1 → 28.7 µs, double loop
  53.4 → 27.7 µs, fib(20) 1,362 → 934 µs; event handler 91 → 81 ns (noisy).
- **Plugin jar 5.0 MB → 1.9 MB.** Script HTTP uses a small JDK-socket client instead of
  OkHttp, Okio and the Kotlin library, with the same DNS-rebinding protection, TLS
  host-name verification, size, redirect and time limits. Web workers time out when idle.
- **Deterministic security analysis 3.2× faster** (756 → 236 ms per load for 32 scripts;
  2.3 → 1.4 s on a cold JVM) with identical decisions and findings: regular expressions
  are compiled once and skipped when they cannot match, hashes reuse their digest,
  repeated excerpts and node IDs are remembered, and taint paths join in linear time.
- **Memory stays flat over time.** The security service keeps manifests without their
  nodes (about 3.6 MB for 32 scripts before), at most 256 decided reviews (before: one
  more for every edit and reload) and the 512 most recent incidents plus state-deciding
  ones; older incidents are read from their report files on demand.
- The watchdog now also stops runaway recursion without loops (`f(n-1) + f(n-1)`).
  Function entries count toward the loop budget.

### Optimizer and diagnostics

- Complete the existing optimizer pipeline: block-local copy/constant propagation,
  primitive arithmetic/comparison folding, branch folding, exception-aware liveness
  and dead-store elimination, forwarding jumps and unreachable-block removal.
- Preserve observable native/host operations, runtime failures and loop watchdog
  checkpoints. The assembler omits unused register slots while retaining parameters.
- Add source-to-runtime differential tests for the whole pipeline, each pass alone
  and each pass disabled, intermediate verified snapshots, deterministic generated
  programs with Java oracles, and engine state across imports/events/tasks/reload.
- CLI `check` and `dump` accept `--no-optimize` and repeatable `--disable-pass=<name>`;
  `dump passes` prints the original IR and verified snapshots after every pass.
- JMH interpreter and compiler workloads now compare optimization on/off in the same
  harness. See [optimizer details](docs/compiler/optimizer.md) and [measurements](docs/benchmarks.md).

### Paper/Folia integration

- Add a separate test probe, pinned server downloads with SHA-256 verification,
  localhost runner and GitHub Actions Paper/Folia matrix. Local live runs passed
  27 checks on Paper 1.21.11 build 132 and 31 on Folia build 14; CI execution itself
  has not yet been observed. See [coverage and evidence](docs/integration.md).
- Route legacy entity/player getters through ownership checks before accessing
  Bukkit state. Cross-region reads report script errors; async reads wait for the
  owner. Display-name writes are forwarded and threading metadata is updated.
- Avoid reading an entity name while constructing an ownership diagnostic.
  The Folia global thread is also prohibited from waiting on entity/region reads.
- Tear down the engine directly in the host's disable callback after Folia halts
  its schedulers. Commands do not schedule client updates while disabled. Regression
  tests cover retirement and persisted unload changes without a scheduler tick.

Language level and IR format remain at 2. The delivered 0.5.1 security hotfix is retained.

## [0.5.1-SNAPSHOT] — 2026-10-05

### Security hotfix (same version explicitly requested for urgent replacement)

- AI outages default to warnings, including legacy `ai.required: true` configurations.
  Set `ai.failure-policy: keep-pending` explicitly for the old blocking policy.
- One provider failure opens a shared 60-second cooldown. Provider alerts are aggregated
  and limited to once per five minutes; safe HTTP/protocol reasons replace generic failures.
- Compatible AI envelopes may contain null tool-call fields; executable model actions,
  stale identities and invalid or incomplete JSON remain rejected.
- Normal event scheduling is not a storm finding. Deferred menu/HTTP callback bodies
  no longer inherit the loop at registration. Actual growth and runtime quotas remain checked.
- AI cannot escalate a compiler-established command permission into automatic quarantine.
  Deterministic dangerous flows, revocation and explicit restoration remain enforced.

Patch update from 0.5.0-SNAPSHOT; language level and IR format remain at 2.

### GUI isolation, operator controls and library extensions

- ScriptMenu views are excluded from generic inventory click/drag dispatch at every
  priority, including their bottom inventory and outside clicks. Menu callbacks,
  `allowTaking` and explicit `MenuClick.cancelled` overrides retain their contracts.
  Ordinary inventories and external plugin GUIs retain mutable generic cancellation.
- Targeted reload reads only selected files and uses active sources for importers;
  unrelated disk changes remain unapplied. Failed selected groups roll back together.
- Persistent `/tys disable [script|all]` (alias `diable`) and `/tys enable <script|all>`
  provide emergency controls, including dependent scripts, resource cleanup and
  revocation of old callbacks. Emergency stop skips script unload hooks.
- Server diagnostics use compact source/caret output with line/column locations.
  Slow warnings default to off; opt-in threshold is 50 ms on tick threads, with
  30-second throttling. `/tys performance <ms|off>` changes and saves this live.
- Added typed statistics, transient metadata with writer lifetime cleanup, BlockData,
  sign sides, display transforms, normalized Quaternion, Brightness and copied
  BoundingBox operations. Finite float conversion and extreme rotations are checked.
- [Skript source audit](docs/skript-audit/README.md) records reviewed decisions and
  remaining gaps; this is not a claim of full language or API parity.

## [0.5.0-SNAPSHOT]

Earlier work retained in this update. Language level 2: everything a server
usually scripts, without addons.

### Language

- Lambdas and scheduled blocks inside `finally` compile once even though cleanup is emitted
  at every exit. They used to fail with TYS0900 (duplicate lambda function key); cleanup
  still runs on normal completion, return and caught errors.
- Commands: `command name(args) { }` with typed arguments (numbers, `bool`,
  `Duration`, players, offline players, worlds, game modes, materials and other keyed
  types), default values, optional (`T?`) and rest (`string...`) arguments,
  sub-commands (`command warp.set(...)`), Tab completion, and the annotations
  `@permission`, `@permissionMessage`, `@aliases`, `@description`, `@usage`,
  `@cooldown`, `@cooldownMessage`, `@cooldownBypass`, `@playerOnly`.
- Scheduling: `after <duration> [for <entity>] { }`, `every <duration> [for <entity>] { }`
  with `task` (`task.runs`, `task.cancel()`), `async { }`, `sync { }`; top-level
  `every` tasks (optionally `@async`), `at "HH:mm"` daily tasks, `on load` and
  `on unload` hooks.
- Variables: script variables (`let`/`var` at the top of a file), `persistent var`
  (saved for the server) and `playerdata var` (saved per player, used as
  `player.name`), with atomic `+=` and `++`.
- Records (`record Warp(name: string, cost: int = 0) { functions }`), maps
  (`Map<K, V>`, literals, indexing, `for key, value in map`), lambdas and function
  values (`function(A): R`), `switch` statements and expressions, `try`/`catch`/
  `finally`/`throw`, `c ? a : b`, bitwise operators, `in`/`!in`, `!is`, `++`/`--`,
  compound assignments `&= |= ^= <<= >>=`, `Instant`, casts to `List<T>`/`Map<K, V>`.
- Event handlers: `@priority(LOWEST..MONITOR)` and `@ignoreCancelled`.
- Modules: `module name`, `import x`, `import x as y`, `import {a, b} from x`, with a
  module graph (importers are recompiled when a module changes) and cycle detection.
- `placeholder name { }` declares PlaceholderAPI placeholders (`%tys_name%`).
- A choice between texts written in the script is a message, like a single literal:
  `player.send(ok ? "<green>Yes" : "<red>No {player.name}")`, and `switch` expressions
  whose values are all such texts.
- Templates joined with `+` are a message too, formatted like the single template they spell:
  a long message can be split over lines (`"<gold>Hi {player.name}! " + "<gray>Rules: {url}"`).
  Before, only joined literals were; a join with a template was refused (TYS0234). A join with
  a variable is still refused.
- Constants of keyed types are checked at compile time: `Material.DIAMOND`,
  `Sound.ENTITY_PLAYER_LEVELUP`, `EntityType.ZOMBIE`, `PotionEffectType.SPEED`, ...
- A list or map literal passed to a function is typed by the parameter when every
  overload agrees (`[player.uuid, "bread", 2.5]` for a `List<any?>`).
- **Breaking:** `Block.type` is a `Material` (it was a text key such as
  `minecraft:stone`; in text it now shows `stone`).
- `for x in list` walks a snapshot taken when the loop starts, like map loops: the body
  (or a handler on another thread) may add or remove elements without the loop skipping,
  repeating or running past them. (Before, a loop that added to its own list could run
  until the time limit stopped it.)
- Lists and maps inserted into text show their elements the way the elements show on
  their own: `"{[1 second, 90 seconds]}"` is `[1s, 1m 30s]` (was `[1000, 90000]`), a list of
  players shows their names. Records do the same for their fields
  (`Visit(who=Steve, where=world 0.5, 64, 0.5, stay=1m 30s)`), and so do `log(value)` and
  `json.stringify` for players, locations, materials and other server objects.
- Importing a script whose file name is not a valid module name (`shop-items.tys` is the module
  `shop-items`, which `import` cannot write) explains that and suggests `module shop_items`;
  other unknown imports suggest every module of the load, also those not compiled yet. Uses of
  a failed import are no longer reported again as unknown names.
- A variable used in a lambda or scheduled block although it changes later is reported once
  (TYS0236), without an extra warning that the variable is never used.
- `after (delay) { }` and `every (delay) ... { }` accept a delay that starts with a parenthesis
  (it was read as a call of a function named `after`). A call `after(x)` is still a call.
- An invalid command parameter (a required one after optional ones, `string...` that is not
  last, a default value that is not a constant) is reported once; the command body no longer
  adds an `Unknown name` error for every use of that parameter.
- A runtime error of a function calling itself shows the repeated call once
  (`... 127 more calls at the same place`) instead of one line per call.
- Compiler hints no longer suggest functions that do not exist (`cooldown(...)` for a
  `cooldown` declaration, `compareTo` for comparing text) or syntax that does not exist
  (`?.[...]` for indexing a value that may be `null`); a map literal with mixed types suggests
  `Map<string, any>`. Calling a function value that may be `null` (for example one looked up
  in a map) reports TYS0216 with a null check to add.
- A syntax error inside a function or placeholder (for example `{1,16}` in a regular
  expression, which starts an interpolation) is no longer followed by a second error saying the
  function must return a value: the parser skipped the broken `return`, so that error only
  repeated the first one. Default values of record fields are checked even when no
  `Record(...)` call uses them yet.

### Standard library

- Generated from compact specifications (`tools/stdlib-gen`): about 150 types, more
  than 900 functions and properties and 111 events, covering players (experience,
  food, flight, speeds, titles, action bars, tab list, sounds, particles, cooldowns,
  permissions, bans), entities (types, names, equipment, potion effects, attributes,
  AI, targets, passengers, nearby entities, projectiles), items (`ItemStack`: names,
  lore, enchantments, durability, flags, model data, colors, skulls), inventories,
  menus (GUIs with click handlers), blocks (type, data, light, biome, signs,
  containers, drops), locations, vectors, worlds (time, weather, game rules, borders,
  spawning, explosions, lightning, fills), chunks, colors, boss bars, sidebars, text
  displays (holograms), server information, formatting (`format.*`), time
  (`time.*`), random numbers, text helpers, JSON, files, web requests, persistent data
  tags on items, entities, blocks, chunks and worlds, Vault economy, chat and groups,
  and PlaceholderAPI.
- Databases in scripts: `Database("name")` (configured) and `Database.sqlite(file)`,
  with asynchronous `execute`, `update`, `query`, `queryFirst` and synchronous variants
  for `async` blocks. SQL has the type `Sql`, which only accepts text written in the
  script, so SQL injection is a compile error (`TYS0235`).
- Everything a script creates (timers, menus, boss bars, sidebars, permission
  attachments) belongs to it and is cleaned up when it is reloaded.
- `player.death` can be cancelled, like `entity.death`: Paper then revives the player
  instead. `@ignoreCancelled` is accepted on it (it was rejected, although
  `event.cancel()` compiled).

### Engine, platform and plugin

- The test platform models chest menus, item names/lore/main hand and player command clicks.
  Both `Player.give` overloads record material, amount and name per receiver, so commands
  that change saved data before giving an item can finish in book scenarios. Inventory
  stacking, full-inventory drops and world physics still require Paper tests.
  Menu close/open requests are deferred during a click; reloading their owner closes them.
  Text files are held in memory and HTTP uses recorded requests with configurable replies,
  without making network connections, so integration scripts can be exercised safely.

- Saved variables are cached in memory, player data is loaded while players connect,
  and changes are written in the background (SQLite by default, MySQL/MariaDB or memory).
- A field added to a record that is already saved no longer loses the data: a saved record
  without the field takes the field's default value (`visits: int = 0`, `tags: List<string> = []`)
  or `null` for a `T?` field. Before, only nullable fields could be added; any other new field made
  the whole variable (every home of every player, say) start again from its initial value.
- Command registry with live updates of players' command lists; event dispatch by
  priority with `ignoreCancelled`; per-script resources and callbacks.
- Paper platform: region-aware threading for entities, blocks and inventories;
  constant tables from the server's live registries (data packs included); codecs for
  saving locations, worlds, players, items, vectors, colors and potion effects.
- Plugin configuration for storage, databases and command messages; `/tys status`;
  PlaceholderAPI expansion; soft dependencies on Vault and PlaceholderAPI.
- Script commands have `/help` pages (description, usage, aliases and sub-commands) that
  follow reloads; Paper builds its help index only at startup, so they are added by
  TachyonScript.
- `broadcast(message, permission)` reaches every online player with the permission, and the
  console. It used `Bukkit.broadcast`, which only reaches senders *subscribed* to the permission:
  a permission that only scripts check (registered by no plugin) reached nobody — not even
  operators — unless a permissions plugin granted it by name. The broadcast event is still fired.
- Opening or closing a player's inventory screen while an inventory event of that player is
  handled (a menu click, `onOpen`/`onClose`, `player.inventory*` handlers) happens right after
  the event, as the server requires, instead of during it.
- Values inside click actions and insertions of message templates are filled in:
  `"<click:run_command:'/tpaccept {player.name}'>[Accept]</click>"` runs
  `/tpaccept Steve` (it ran `/tpaccept <tys_arg_0>` before). The value goes in as plain text,
  like everywhere else in a message; `text.command(...)` and the other `text.*` click helpers
  still build clickable text from values without a template.
- `/tys profile` and the slow-execution warning cover every script execution: commands,
  `every`/`at` tasks, `after` blocks, placeholders, `on load` and callbacks (menu clicks, web
  and database answers), not only event handlers.
- `tys check <file>` checks the file on its own; when one of its imports is then missing, it
  says so and suggests checking the folder, which is checked like the scripts folder.
- `/tys scripts` says what each script declares (`2 handlers, 4 commands, 1 task`) and
  `/tys info` adds the module name and imports, commands, tasks, placeholders and saved
  variables. A script with only commands was listed as `0 handlers`.
- The unused `commands.messages.usage` entry was removed from the default configuration
  (it is still accepted).
- Verified on a live Paper 1.21.11 server with a self-test script and the wiki's recipes.

### Documentation

- New pages: commands, scheduling, saved data, databases, modules, the world, menus,
  integrations, errors and a migration guide from Skript; every example is compiled by
  the tests. The reference now shows details and examples of members.
- A GitHub wiki in `wiki/`: 32 pages (installation, tutorials, the language, the server
  API, 17 complete recipes, a guide for Skript users, configuration, administration,
  performance and Folia, addons, FAQ) and the API reference generated by
  `tys docs --wiki`. Tests compile every example and keep the reference current.

## 0.1.0-SNAPSHOT

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
  "did you mean" suggestions.

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
