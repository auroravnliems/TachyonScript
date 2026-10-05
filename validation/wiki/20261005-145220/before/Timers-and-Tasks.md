# Timers and tasks

Scripts often need time: a welcome message a few seconds after joining, a countdown, an
announcement every ten minutes, a reset every night at midnight. TachyonScript has four tools:

| Tool | Where | Runs |
|------|-------|------|
| `after <duration> { }` | inside handlers, commands, functions | once, later |
| `every <duration> { }` | inside handlers, commands, functions | repeatedly, until cancelled |
| `every <duration> { }` | at the top of the file | repeatedly, while the script is loaded |
| `at "HH:mm" { }` | at the top of the file | every day at that time |

Plus `on load { }` / `on unload { }` for when the script starts and stops, and
`async { }` / `sync { }` to move work off and back onto the server thread.

## Durations

A duration is a number followed by a unit:

| Unit | Example | Length |
|------|---------|--------|
| `milliseconds`, `millisecond` | `250 milliseconds` | 1/1000 s |
| `ticks`, `tick` | `20 ticks` | 1/20 s (a server tick) |
| `seconds`, `second` | `30 seconds` | |
| `minutes`, `minute` | `5 minutes` | |
| `hours`, `hour` | `2 hours` | |
| `days`, `day` | `1 day` | |

Durations can be added, subtracted, multiplied by a number, compared, stored in variables and
constants, and read in any unit:

```tys-body
let cooldown = 1 minute + 30 seconds
let longer = cooldown * 2
if longer > 2 minutes {
    player.send("{longer} is {longer.seconds} seconds or {longer.ticks} ticks")   // 3m is 180 seconds or 3600 ticks
}
```

`d.millis`, `d.ticks`, `d.seconds`, `d.minutes`, `d.hours` and `d.days` give the duration as a
whole number in that unit (rounded down). Inserted into text, a duration reads `1m 30s`;
`format.duration(d)` writes `1 minute, 30 seconds` and `format.timer(d)` writes `01:30`.

## Doing something later: `after`

```tys
event player.join {
    player.send("<green>Welcome!")
    after 3 seconds {
        player.send("<gray>Type /rules to read the rules.")
    }
    player.send("<gray>This line is sent before the rules reminder.")
}
```

The code after the block continues at once; the block runs when the time is up. The delay can
be any duration expression — a variable, a sum, a multiplication, `(base + extra) * 2`:

```tys-body
let delay = 2 seconds
after delay + 500 milliseconds {
    player.send("2.5 seconds later")
}
for i in 1..3 {
    after 1 second * i {
        player.send("<yellow>{4 - i}...")
    }
}
```

## Repeating: `every` inside code

`every` inside a handler, command or function starts a repeating block. Inside it, **`task`**
is the running repetition:

| Member | Meaning |
|--------|---------|
| `task.runs` | how many times the block has started, this run included (1, 2, 3, ...) |
| `task.cancel()` | stops the repetition (the current run finishes) |
| `task.cancelled` | whether it was cancelled |
| `task.location` | where it was started, e.g. `countdown.tys:12` |

```tys
@playerOnly
command countdown(from: int = 5) {
    every 1 second {
        let left = from - task.runs + 1
        if left <= 0 {
            player.title("<green>Go!", "")
            player.playSound(Sound.ENTITY_PLAYER_LEVELUP)
            task.cancel()
            return
        }
        player.title("<yellow>{left}", "")
        player.playSound(Sound.BLOCK_NOTE_BLOCK_HAT)
    }
}
```

The first run happens one interval after the block starts, not immediately.

To stop a repetition from somewhere else, keep its `task` in a script variable:

```tys
var rainbow: Task? = null

command rainbow.start {
    rainbow?.cancel()
    every 10 ticks {
        rainbow = task
        let colors = ["red", "gold", "yellow", "green", "aqua", "blue", "light_purple"]
        let color = colors[(task.runs % colors.size) as int]
        for p in server.players {
            p.actionBar(text.mini("<{color}>★ Party time ★"))
        }
    }
}

command rainbow.stop {
    rainbow?.cancel()
    rainbow = null
}
```

## Blocks that follow an entity: `for`

The player may have left by the time an `after` block runs. Add `for <entity>` and the block is
skipped once the entity is gone (the player quit, the mob died). On Folia the block also runs on
the thread that owns the entity, which is required to change it safely:

```tys
event player.join {
    after 5 seconds for player {
        player.addPotionEffect(PotionEffectType.SPEED, 30 seconds, 1)
        player.send("<aqua>Have a speed boost!")
    }
}

event entity.spawn {
    if entity.type == EntityType.ZOMBIE {
        every 2 seconds for entity {
            entity.world.spawn(EntityType.SILVERFISH, entity.location)
            if task.runs >= 3 {
                task.cancel()
            }
        }
    }
}
```

Use `for player` for anything that changes a player later. Without it the block still runs
after the player left, and changing an offline player does nothing.

## Repeating tasks of the script: `every` at the top level

An `every` block written at the top of the file (outside any handler) is a task of the
script. It starts when the script loads and stops when the script is reloaded or removed:

```tys
let tips = ["Use /spawn to go back to spawn.", "Vote with /vote for rewards!", "Report cheaters with /report."]

every 5 minutes {
    broadcast("<gold>[Tip]</gold> <gray>{tips.random() ?? ""}")
}

every 1 second {
    for p in server.players {
        p.actionBar("<gray>Online: <white>{server.onlineCount} <gray>| TPS: <white>{math.roundTo(server.tps, 1)}")
    }
}
```

The interval must be known when the script loads (a literal or a constant) and at least one
tick. Top-level tasks run on the main thread (the global region on Folia); their first run is
one interval after the script loads. They have no `task` variable: they cannot be cancelled,
only removed with their script.

`@async` runs a top-level task on a background thread, for work that should not use the server
thread (large file writes, slow computations). Asynchronous tasks must not change the world
directly; see [below](#background-work-async-and-sync).

```tys
var joinsThisHour = 0

event player.join {
    joinsThisHour++
}

@async
every 1 hour {
    files.append("stats/joins.log", "{time.format(time.now, "yyyy-MM-dd HH:mm")} {joinsThisHour}")
    joinsThisHour = 0
}
```

## Every day at a time: `at`

```tys
at "04:55" {
    broadcast("<red>The server restarts in 5 minutes.")
}

at "00:00" {
    broadcast("<gold>A new day begins: daily rewards are available again!")
}
```

`at "HH:mm"` uses the 24-hour clock and the server's time zone (`time.zone`). It runs once a
day, every day, while the script is loaded. `@async` works here too.

## When the script starts and stops: `on load` and `on unload`

```tys
let db = Database.sqlite("stats.db")
var started = time.now

on load {
    db.execute("CREATE TABLE IF NOT EXISTS joins (uuid TEXT, time INTEGER)")
    log.info("Stats ready")
}

on unload {
    log.info("Stats stopping after {time.since(started)}")
}
```

* `on load` runs after the script's variables are initialized and before its commands, tasks
  and event handlers become active — on server start and every time the script is (re)loaded.
* `on unload` runs when the script is reloaded (a new version replaces it), removed, or when the
  server stops.
* A script can have several of each; they run in the order they are written.

## Background work: `async` and `sync`

`async { }` runs a block on a background thread; the code after it continues at once.
`sync { }` inside it comes back to the server thread (the global region on Folia):

```tys
@playerOnly
command stats {
    async {
        let lines = files.lines("stats/{player.uuid}.txt")
        let total = lines.size
        sync {
            player.send("You have {total} saved entries.")
        }
    }
}
```

Use `async` for work that takes time without touching the world: reading and writing files,
waiting for a database with `querySync`, heavy computations. Most slow operations already work
in the background on their own — [database queries](Databases), [web requests](Files-Web-and-JSON)
— so `async` is rarely needed.

In an `async` block, reading the world is fine. Changes to players, entities and blocks are
handed to the thread that owns them and applied on its next tick; operations that must return a
new entity (like `spawn`) wait for the server thread. Keep world changes in `sync` blocks to
make the order obvious.

## Everything stops with its script

Every `after`, `every`, top-level task and daily task belongs to the script that started it.
When the script is reloaded or removed they are cancelled, together with its menus, boss bars,
sidebars and the permissions it gave with `player.addPermission`. A reload therefore never
leaves a timer running twice or a countdown of an old version.

A script that did **not** change keeps running — with its variables and its timers — while
other scripts reload.

## Timing details

* The server runs 20 ticks per second, so delays and intervals are rounded up to whole ticks
  (50 ms). `every 1 tick` is the fastest repetition. `@async` tasks are not tied to ticks.
* When the server lags, ticks are late and so are timers: `after 20 ticks` means "after 20
  server ticks", which can be longer than one second.
* A block that fails with an error is logged like any other error; a repeating block keeps
  repeating.
* Like every script execution, a block is stopped if it runs longer than
  `safety.max-execution-time-ms` (see [Configuration](Configuration)).

## Next

* [Saving data](Saving-Data) — values that survive restarts
* [Titles, boss bars and sidebars](Titles-Boss-Bars-and-Sidebars) — countdowns on screen
* [Performance and Folia](Performance-and-Folia)
