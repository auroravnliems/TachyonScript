# Titles, boss bars, sidebars and holograms

Besides chat, a script can show text in six places on the screen:

| Where | How |
|-------|-----|
| a big title and subtitle in the middle | `player.title(title, subtitle)` |
| above the hotbar | `player.actionBar(text)` |
| the tab list header and footer | `player.tabHeader`, `player.tabFooter` |
| a player's name in the tab list | `player.playerListName` |
| a bar at the top of the screen | `BossBar(...)` |
| a scoreboard at the right side | `Sidebar(...)` |

…and floating text in the world, with holograms (`location.spawnText(...)`).

All of them take [MiniMessage](Messages-and-Text) text with `{values}`.

## Titles

```tys
event player.join {
    player.title("<gold><bold>Welcome", "<gray>to the server, {player.name}")
}

@playerOnly
command countdown {
    every 1 second {
        let left = 4 - task.runs
        if left == 0 {
            player.title("<green>GO!", "", 0 seconds, 1 second, 250 milliseconds)
            task.cancel()
            return
        }
        player.title("<yellow>{left}", "<gray>get ready", 0 seconds, 1 second, 0 seconds)
    }
}
```

`player.title(title, subtitle, fadeIn, stay, fadeOut)` sets the timing; without it Minecraft's
default is used (0.5 s fade in, 3.5 s stay, 1 s fade out). Pass `""` for an empty title or
subtitle. `player.clearTitle()` removes the title early.

## Action bar

```tys
every 1 second {
    for p in server.players {
        p.actionBar("<red>❤ {math.round(p.health)}/{math.round(p.maxHealth)}  <gold>🍗 {p.food}/20  <green>✦ Lv {p.level}")
    }
}
```

An action bar message fades after about two seconds; to keep one on screen, send it again every
second.

## Tab list

```tys
every 5 seconds {
    for p in server.players {
        p.tabHeader = "<gradient:#00c6ff:#0072ff><bold>MY SERVER</bold></gradient>\n<gray>play.example.com"
        p.tabFooter = "<gray>Online: <white>{server.onlineCount}</white>  TPS: <white>{math.roundTo(server.tps, 1)}</white>  Ping: <white>{p.ping} ms"
    }
}

event player.join {
    if player.hasPermission("server.staff") {
        player.playerListName = "<red>[Staff]</red> <white>{player.name}"
    }
}
```

## Boss bars

```tys
let bar = BossBar("<yellow>Event starts soon", 1.0, BarColor.YELLOW, BarStyle.NOTCHED_10)

event player.join {
    bar.show(player)
}

@permission("server.event")
command event.start {
    every 1 second {
        let left = 60 - task.runs
        bar.progress = left / 60.0
        bar.title = "<yellow>Event starts in <white>{left}</white> seconds"
        if left <= 10 {
            bar.color = BarColor.RED
        }
        if left <= 0 {
            bar.hideAll()
            broadcast("<green>The event has started!")
            task.cancel()
        }
    }
}
```

| Member | Meaning |
|--------|---------|
| `BossBar(title)` | a full white bar |
| `BossBar(title, progress, color, style)` | progress from `0.0` (empty) to `1.0` (full) |
| `bar.title`, `bar.progress`, `bar.color`, `bar.style` | read and change; viewers see changes at once |
| `bar.darkenScreen` | darken the sky while the bar is shown |
| `bar.show(player)`, `bar.hide(player)`, `bar.hideAll()` | who sees the bar |
| `bar.viewers` | the players who see it |

Colors: `BarColor.PINK`, `BLUE`, `RED`, `GREEN`, `YELLOW`, `PURPLE`, `WHITE`.
Styles: `BarStyle.PROGRESS` (one piece), `NOTCHED_6`, `NOTCHED_10`, `NOTCHED_12`, `NOTCHED_20`
(divided). Boss bars work the same on Paper and Folia and may be changed from any thread.

A boss bar belongs to the script that created it: when that script reloads, it is hidden from
everyone, so an old version's bar never stays on screen. A bar created in a top-level `let`
(like `bar` above) is recreated by the new version; players who were shown the old one must be
shown the new one again — the `on load` below does that:

```tys
let bar = BossBar("<aqua>Welcome to the server!")

on load {
    for p in server.players {
        bar.show(p)
    }
}

event player.join {
    bar.show(player)
}
```

## Sidebars

A sidebar is the scoreboard on the right side of the screen: a title and up to 15 lines, without
score numbers.

```tys
playerdata var kills: int = 0

var boards: Map<UUID, Sidebar> = {}

function sidebarOf(p: Player): Sidebar {
    let existing = boards[p.uuid]
    if existing != null {
        return existing
    }
    let created = Sidebar("<gold><bold>MY SERVER")
    created.show(p)
    boards[p.uuid] = created
    return created
}

every 1 second {
    for p in server.players {
        sidebarOf(p).lines = [
            "<gray>{time.format(time.now, "dd/MM/yyyy")}",
            "",
            "<white>Player: <green>{p.name}",
            "<white>Kills: <red>{p.kills}",
            "<white>Level: <aqua>{p.level}",
            "",
            "<white>Online: <yellow>{server.onlineCount}",
            "",
            "<gold>play.example.com"
        ]
    }
}

event player.quit {
    boards.remove(player.uuid)
}

event entity.death {
    if killer != null && entity is Player {
        killer.kills += 1
    }
}
```

| Member | Meaning |
|--------|---------|
| `Sidebar(title)` | a new, empty sidebar |
| `sidebar.title` | the title (changeable) |
| `sidebar.lines` | all lines, top to bottom (assign a list to replace them) |
| `sidebar.line(index, text)` | changes one line (0 is the top one), adding empty lines before it if needed |
| `sidebar.removeLine(index)`, `sidebar.clear()` | removes one line / all lines |
| `sidebar.show(player)`, `sidebar.hide(player)` | who sees it |
| `sidebar.viewers` | the players who see it |

* One sidebar can be shown to many players (the same lines for everyone), or each player gets
  their own, as above, for personal values.
* Showing a sidebar gives the player a scoreboard of their own; `hide` gives them back the
  server's main scoreboard. Plugins that color names through teams of the main scoreboard do
  not apply to a player while a script sidebar is shown.
* Like boss bars, sidebars belong to their script and disappear when it reloads — the
  `every 1 second` above creates new ones on the next second.
* Folia has no scoreboards: creating a sidebar there is a runtime error (`Sidebars are not
  available on Folia (it has no scoreboards).`).

## Holograms

`location.spawnText(text)` creates floating text — a text display entity — that always faces
the player:

```tys
@playerOnly
@permission("server.holograms")
command hologram(message: string...) {
    let hologram = player.location.add(0, 2, 0).spawnText("<gold>{message}")
    hologram.scale = 1.5
    hologram.shadowed = true
    player.send("<green>Hologram created.")
}
```

| Member of `TextDisplay` | Meaning |
|-------------------------|---------|
| `text` | the text (changeable at any time) |
| `scale` | the size, `1.0` is normal |
| `billboard` | whether it turns towards the viewer (on by default) |
| `background` | background color, `null` for the default translucent gray |
| `seeThrough` | visible through blocks |
| `shadowed` | text shadow |
| `lineWidth` | wrap width in pixels |

A hologram is an entity like any other: it is **saved with its chunk** and survives reloads and
restarts. A script that creates holograms when it loads should therefore make them temporary and
remove them when it unloads, otherwise every reload adds another copy:

```tys
var holograms: List<TextDisplay> = []

on load {
    let spawn = server.defaultWorld.spawnLocation
    let lines = ["<gold><bold>WELCOME", "<gray>Type <yellow>/menu</yellow> to begin", "<gray>Online: {server.onlineCount}"]
    for i in 0..<lines.size {
        let hologram = spawn.add(0, 3.0 - i * 0.3, 0).spawnText(text.mini(lines[i]))
        hologram.persistent = false        // not saved with the chunk
        holograms.add(hologram)
    }
}

on unload {
    for hologram in holograms {
        hologram.remove()
    }
}
```

Entities are managed by the region that owns them; changing a hologram far away from the
script's thread is handed to that region automatically (see
[Performance and Folia](Performance-and-Folia)).

## Next

* [Messages and text](Messages-and-Text)
* [Timers and tasks](Timers-and-Tasks) — updating these regularly
* [Players](Players)
