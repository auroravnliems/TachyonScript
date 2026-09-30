# The TachyonScript language

TachyonScript is a statically typed scripting language for Paper and Folia servers.
Scripts are plain text files ending in `.tys` in `plugins/TachyonScript/scripts/`.
They are compiled when they are loaded; mistakes are reported with the file, line,
column and a suggestion before anything runs, and a script that does not compile
never replaces the working version that is already running.

Everything a server usually needs is built in: events, commands with typed arguments,
timers, variables saved across restarts, per-player data, SQL databases, menus (GUIs),
items, inventories, worlds, boss bars, sidebars, Vault and PlaceholderAPI — more than
900 functions and properties and 111 events, all checked by the compiler.

## A first script

```tys
// plugins/TachyonScript/scripts/welcome.tys

const PREFIX = "<gold>[Server]</gold> "

playerdata var visits: int = 0

event player.join {
    player.visits += 1
    player.send("{PREFIX}<green>Welcome, {player.name}! Visit #{player.visits}.")
    if player.health < 10.0 {
        player.health = player.maxHealth
    }
}

@cooldown(30 seconds)
@playerOnly
command spawn {
    player.teleport(server.defaultWorld.spawnLocation)
    player.send("{PREFIX}Teleported to spawn.")
}
```

Save the file and run `/tys reload`. If the script has an error, the console shows
it and the previous version keeps running:

```text
ERROR scripts/welcome.tys:9:12 [TYS0201]

  9 |     player.sned("{PREFIX}<green>Welcome, {player.name}!")
    |            ^^^^

Unknown member 'sned' on Player.

Did you mean:
    send
```

## Contents

The language:

* [Variables and constants](variables.md) — local, script, saved and per-player variables
* [Types, lists, maps and null safety](types.md) — records, function types, casts
* [Control flow](control-flow.md) — conditions, loops, `switch`, `try`/`catch`
* [Functions and lambdas](functions.md)
* [Modules and imports](modules.md)

Running things:

* [Events](events.md) — handlers, priorities, cancelled events
* [Commands](commands.md) — arguments, permissions, cooldowns, sub-commands
* [Scheduling](scheduling.md) — `after`, `every`, `async`, lifecycle hooks, daily tasks
* [Saved data](storage.md) — `persistent var`, `playerdata var`, where it is stored
* [Databases](databases.md) — SQL in scripts, safely

The server:

* [Messages and MiniMessage](messages.md) — templates, titles, action bars, boss bars, sidebars
* [The world](world.md) — players, entities, items, inventories, blocks, locations, effects
* [Menus](gui.md) — chest GUIs with click handlers
* [Integrations](integrations.md) — Vault, PlaceholderAPI, files, the web, JSON
* [Errors](errors.md) — compile errors, runtime errors, `try`/`catch`
* [Coming from Skript](from-skript.md)
* [Standard library reference](reference.md) (generated from the declarations)

For the precise rules (grammar, precedence, conversions) see
[`../compiler/`](../compiler/).
