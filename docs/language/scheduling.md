# Scheduling

## Doing something later

`after` runs a block later; the code after it continues at once:

```tys
event player.join {
    player.send("Welcome!")
    after 3 seconds {
        player.send("Did you read the rules? Type /rules.")
    }
}
```

`every` repeats a block. Inside it, `task` is the repetition: `task.runs` counts the
runs and `task.cancel()` stops it:

```tys
@playerOnly
command countdown(seconds: int = 5) {
    every 1 second {
        let left = seconds - task.runs + 1
        if left <= 0 {
            player.title("<green>Go!", "")
            task.cancel()
        } else {
            player.title("<yellow>{left}", "")
        }
    }
}
```

The block sees the variables around it as they were when it was created. With
`for <entity>`, the block runs on the thread that owns that entity (on Folia) and is
skipped once the entity is gone — use it for anything that changes a player later:

```tys
event player.join {
    after 5 seconds for player {
        player.addPotionEffect(PotionEffectType.SPEED, 30 seconds, 1)
    }
}
```

Durations shorter than a tick are rounded up to one tick (50 ms).

## Background work

`async { }` runs a block off the server thread, for slow work such as reading files or
waiting for a database; `sync { }` comes back to the server thread:

```tys
@playerOnly
command stats {
    async {
        let text = files.read("stats/{player.uuid}.txt") ?? "no stats yet"
        sync {
            player.send("Your stats: {text}")
        }
    }
}
```

Most slow operations already work in the background on their own
([databases](databases.md), [web requests](integrations.md)), so `async` is rarely
needed.

## Script lifecycle and tasks

Code can run when a script is loaded or unloaded (by `/tys reload`, by editing the
file, or when the server stops), and at fixed times:

```tys
var ticks = 0

on load {
    log.info("Loaded")
}

on unload {
    log.info("Stopping after {ticks} ticks")
}

every 5 minutes {
    broadcast("<gray>Remember to vote! /vote")
}

@async
every 30 seconds {
    // runs off the server thread
    ticks += 1
}

at "04:00" {
    broadcast("<red>The daily restart is in 5 minutes.")
}
```

`at "HH:mm"` runs every day at that time in the server's time zone.

## Everything stops with the script

Everything a script starts — `after` and `every` blocks, tasks, menus, boss bars,
sidebars, permissions given with `player.addPermission` — belongs to that script. When
the script is reloaded or removed, all of it is cancelled or cleaned up, so a reload
never leaves timers running twice or menus that do nothing. A script that did not
change keeps running (with its variables and timers) when other scripts reload.
