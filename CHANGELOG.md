# Changelog

All notable changes are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/) once 1.0 is released.

## [Unreleased] — 0.2.0-SNAPSHOT

Language level 2: everything a server usually scripts, without addons.

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
