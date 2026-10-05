# Performance and Folia

## Why scripts are fast

TachyonScript does as much work as possible **once, when a script loads**, so that nothing is
left to figure out while the server runs:

* **Compiled, not interpreted from text.** Scripts are checked and compiled to a compact
  register code. Names, types, overloads and member lookups are resolved when the script loads;
  running it involves no text parsing and no reflection.
* **Constants are resolved once.** `Material.DIAMOND`, `Sound.ENTITY_PLAYER_LEVELUP` and other
  constants are looked up when the script is linked, not every time the line runs.
* **Messages are pre-parsed.** A message such as `"<gold>[Shop]</gold> {player.name} bought
  {amount}"` is parsed as MiniMessage once; sending it only fills in the values.
* **No allocation per call.** The interpreter reuses its frames; a handler that does not create
  objects does not create garbage.
* **Unused events cost nothing.** The server only listens to an event while a loaded script
  handles it.

Measured with JMH on a shared 4-vCPU cloud VM (lower is better; the full setup and all results
are in the repository's [benchmarks](https://github.com/auroravnliems/TachyonScript/blob/main/docs/benchmarks.md)):

| Operation | Time |
|-----------|------|
| Run a small event handler through the engine | ~44 ns |
| An event that no script handles | ~1.6 ns in the engine, and no listener at all on the server |
| Send a message with two values (pre-parsed template) | ~0.26 µs |
| The same message parsed with MiniMessage for every send (the usual way) | ~6.6–8.2 µs |
| Compile a 900-line script | ~2 ms |

For scale: 100 players each firing 20 move events per second is 2,000 handler runs per second —
about 0.1 ms of CPU time per second at this cost.

The interpreter is slower than Java compiled by the JIT for tight arithmetic loops (20–80×)
and a call between script functions costs about 27 ns. Typical scripts spend their time in the
server (moving entities, changing blocks, sending packets), where this does not matter. A
bytecode backend that closes the gap is planned.

## Writing fast scripts

**Leave frequent events early.** `player.move` fires many times per second per player,
`block.redstone`, `block.flow`, `entity.target` and `inventory.moveItem` (hoppers) can fire
thousands of times per second. Check the cheapest condition first and `return`:

```tys
event player.move {
    if !event.changedBlock {
        return                                     // most move events end here
    }
    if player.world.name != "arena" {
        return
    }
    if to.block.relative(BlockFace.DOWN).type == Material.MAGMA_BLOCK {
        player.damage(1.0)
    }
}
```

**Keep slow work out of handlers.** Database queries, web requests and file writes do not
belong in `player.move`. Queries and requests already run in the background; files should be
written from an `async` block or a timer.

**Use the right storage.** A `playerdata var` is read from memory in nanoseconds; a database
query takes milliseconds (in the background). Keep values you read all the time in saved
variables and use databases for searches and history.

**Choose sensible intervals.** `every 1 tick` runs 20 times per second. Scoreboards, action bars
and tab lists rarely need more than one update per second.

**Keep placeholders cheap.** Scoreboard and tab plugins may ask for placeholders every second for
every player.

**Measure.** `/tys profile start`, play for a few minutes, `/tys profile report` lists every
handler, command, task, scheduled block and placeholder with its number of calls, total, average
and maximum time. Handlers slower than
`performance.slow-execution-warning-ms` (5 ms) are also reported in the console. See
[Admin commands](Admin-Commands#tys-profile).

## Runaway scripts

A script cannot freeze the server:

* an execution that runs longer than `safety.max-execution-time-ms` (1 second) is stopped with an
  error pointing at the loop;
* functions calling each other deeper than `safety.recursion-limit` (128) are stopped;
* the same error at the same place is logged once and then counted, so a failing handler of a
  frequent event cannot flood the console.

The only thing the watchdog cannot interrupt is a single call into the server or another plugin
that itself blocks.

## Folia

[Folia](https://papermc.io/software/folia) is Paper with **regionized multithreading**: the world
is divided into regions, and each region runs on its own thread at the same time as the others.
Plugins must not touch an entity or a block from a thread that does not own it.

TachyonScript declares `folia-supported: true`, and **the same scripts run on Paper and Folia
without changes**. What it does for you:

* **Event handlers run on the thread that fired the event** — the region of the player or block.
  Handlers for different areas of the world run in parallel.
* **Writes are routed to their owner.** When a handler changes something another region owns —
  the health of a player far away, a block in another region — the change is handed to the
  owning thread and applied on its next tick. Reading is always allowed.
* **Script variables, lists and maps are thread-safe.** `count += 1`, `player.coins -= 5` and
  `map[key] += 1` are atomic; loops iterate over snapshots.
* **Timers follow what they belong to.** `after ... for player { }` and `every ... for entity { }`
  run on the thread that owns the player or entity, wherever it moves. Top-level `every`/`at`
  tasks, `sync { }` blocks and callbacks (menus, databases, web requests) run on the global
  region thread.
* **Menus, boss bars, titles and messages** work everywhere.

A few things behave differently on Folia:

| Feature | On Folia |
|---------|----------|
| Sidebars | not available (Folia has no scoreboards): `Sidebar(...)` is a runtime error |
| `server.createWorld`, `server.unloadWorld` | not available while the server runs |
| Creating entities (`spawn`, `dropItem`, `launchProjectile`) from another region's thread | a runtime error — run it with `after 1 tick for <entity> { }` or from an event of that region |
| Reading a value right after changing it on an entity another region owns | returns the old value until the owner's next tick |

Everything else — commands, saved data, databases, placeholders, Vault — works the same.

### Writing scripts that are good on both

* Change a player from the player's own handlers, commands or `for player` blocks — which is what
  scripts naturally do.
* Use `after ... for player` rather than plain `after` for anything that changes a player later.
* For loops over `server.players` that change every player (a broadcast of titles, rewards for
  everyone), TachyonScript routes each change to each player's region; nothing special is needed.

## Next

* [Timers and tasks](Timers-and-Tasks)
* [Configuration](Configuration) — safety limits
* [Admin commands](Admin-Commands) — the profiler
