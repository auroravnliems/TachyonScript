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

Historical measurements with JMH on a shared 4-vCPU cloud VM (not a new benchmark of
0.5.1-SNAPSHOT; lower is better). The full setup and results
are in the repository's [benchmarks](https://github.com/auroravnliems/TachyonScript/blob/main/docs/benchmarks.md):

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
server (moving entities, changing blocks, sending packets), where this does not matter. The
opt-in bytecode backend closes most of the gap (below).

## Bytecode backend

`runtime.backend: bytecode` (in `config.yml`, restart to change) turns every script function
into a JVM class that HotSpot compiles to machine code. Scripts behave exactly the same:
errors, `try`/`catch`, the execution-time watchdog and security revocation work as in the
interpreter, and error messages keep their `.tys` lines. Measured on the same machine:

| Workload | interpreter | bytecode |
|---|---|---|
| Integer loop, 1,000 iterations | 30.1 µs | 2.75 µs |
| Double loop, 1,000 iterations | 29.8 µs | 2.77 µs |
| Recursive fib(20) | 815 µs | 292 µs |
| One event handler | 74 ns | 45 ns |

Loading is slower: generating the classes for 32 real server scripts (371 functions) adds
about 150–270 ms to a full load; `/tys reload <file>` only generates that file's functions.
HotSpot does not compile a method above 8,000 bytes of bytecode, so such a function stays
interpreted and the console names it. The interpreter stays the default until the bytecode
backend has run on more servers; scripts that mostly call the server see little difference.

## Compiler optimization in 0.6.0

The IR pipeline propagates copies/constants inside a block, folds primitive
operations and branches, removes dead local stores with exception-aware liveness,
threads forwarding jumps and removes unreachable blocks. Unused registers do not
consume frame slots. Calls, host equality/string conversion, runtime errors,
globals and watchdog back edges remain observable.

```sh
tys dump ir script.tys --no-optimize
tys dump passes script.tys --disable-pass=dead-code
tys check scripts --disable-pass=constant-propagation
```

The compiler tests compare all passes, each pass alone and each pass disabled,
plus seeded programs with Java oracles and stateful engine scenarios. This is not
a proof for every possible addon/program. Global propagation, CSE, live-range slot
reuse and superinstructions remain planned.

JMH on 2026-10-06 (i3-10105F, Microsoft JDK 21.0.12.1, two forks, 3 × 1 s warmup,
5 × 1 s measurement per fork) measured integer loops at 50.67/52.61 µs and double
loops at 53.66/50.30 µs with optimization off/on. Confidence intervals overlap;
there is no established speedup. The compile workload averaged 1.91/3.24 ms.
The repository [benchmark report](https://github.com/auroravnliems/TachyonScript/blob/main/docs/benchmarks.md)
includes error estimates and raw results.

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
and maximum time. Slow warnings are **off by default**. `/tys performance 50` enables
them and saves the threshold; `/tys performance off` disables them. Configuration uses
`performance.slow-execution-warnings: false` and `slow-execution-warning-ms: 50` by default.
Only outer executions on tick threads are warned about, at most once per function per
30 seconds. Async work is measured by the profiler without slow-warning messages. See
[Admin commands](Admin-Commands#tys-profile).

## Runaway scripts

The engine limits runaway script execution:

* an execution that runs longer than `safety.max-execution-time-ms` (1 second) is stopped with an
  error pointing at the loop;
* functions calling each other deeper than `safety.recursion-limit` (128) are stopped;
* security's per-script pending task quota defaults to 1024;
* the same error at the same place is logged once and then counted, so a failing handler of a
  frequent event cannot flood the console.

The only thing the watchdog cannot interrupt is a single call into the server or another plugin
that itself blocks.

For a script that compiles but has faulty game logic, use `/tys disable <file>` or
`/tys disable all`. The stop persists across restart and skips unload hooks; it does not
undo completed changes. See [Admin commands](Admin-Commands#tys-disable-and-tys-enable).

## Folia

[Folia](https://papermc.io/software/folia) is Paper with **regionized multithreading**: the world
is divided into regions, and each region runs on its own thread at the same time as the others.
Plugins must not touch an entity or a block from a thread that does not own it.

TachyonScript declares `folia-supported: true`. The 0.7.0 core probe passed 32 checks
on Folia 1.21.11 build 14 and 28 on Paper build 132, with each backend, including two-region ownership,
teleport, timers, reload/rollback, watchdog and SQLite shutdown. A Paper/Folia CI
matrix has been added; its remote run is still pending. Player clients/menus,
external integrations and sustained load need separate Folia coverage. See
[the evidence and reproduction guide](https://github.com/auroravnliems/TachyonScript/blob/main/docs/integration.md).
The integration provides:

* **Event handlers run on the thread that fired the event** — the region of the player or block.
  Handlers for different areas of the world run in parallel.
* **Writes are routed to their owner.** When a handler changes something another region owns —
  the health of a player far away, a block in another region — the change is handed to the
  owning thread and applied on its next tick. Ownership-sensitive reads must run
  on the owner; cross-region reads are not a synchronization mechanism.
* **Script variables, lists and maps are thread-safe.** `count += 1`, `player.coins -= 5` and
  `map[key] += 1` are atomic; loops iterate over snapshots.
* **Timers follow what they belong to.** `after ... for player { }` and `every ... for entity { }`
  run on the thread that owns the player or entity, wherever it moves. Top-level `every`/`at`
  tasks, `sync { }` blocks and database/web callbacks use the global region thread.
  Menu click callbacks run on the clicking player's thread.
* **Menus, boss bars, titles and messages** use the same API; live Folia client coverage remains incomplete.

A few things behave differently on Folia:

| Feature | On Folia |
|---------|----------|
| Sidebars | not available (Folia has no scoreboards): `Sidebar(...)` is a runtime error |
| `server.createWorld`, `server.unloadWorld` | not available while the server runs |
| Creating entities (`spawn`, `dropItem`, `launchProjectile`) from another region's thread | a runtime error — run it with `after 1 tick for <entity> { }` or from an event of that region |
| Ownership-sensitive read of another region's entity | a script error from a tick/global thread; an async caller waits for the owner |

Deferred writes do not provide a completion barrier. Use an entity-owned block for
related reads and writes. `sync` means the global thread on Folia, so it does not
give ownership of every entity. Entity-to-entity teleport reads the target's
location first; that read requires the target's owner too.

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
