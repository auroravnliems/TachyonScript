# Commands

A script adds commands with `command`. The arguments have types: TachyonScript reads what the
player typed, converts it, completes it with Tab and answers with a usage message when
something is missing or wrong — all before your code runs.

```tys
@description("Heals you or another player")
@permission("server.heal")
command heal(target: Player? = null) {
    let who = target ?? player
    if who == null {
        sender.send("<red>From the console, name a player: /heal <player>")
        return
    }
    who.health = who.maxHealth
    who.food = 20
    sender.send("<green>Healed {who.name}.")
}
```

After `/tys reload` the command exists immediately — for everyone, with Tab completion — and
disappears again when the script is removed. No restart and no relog.

## Inside a command

Every command has two variables besides its parameters:

| Variable | Type | Meaning |
|----------|------|---------|
| `sender` | `CommandSender` | who ran the command: a player, the console or a command block |
| `player` | `Player?` | the player who ran it, or `null` for the console |

With `@playerOnly` the console is refused with a message and `player` is a `Player` (never
`null`), which is what most player commands want:

```tys
@playerOnly
command spawn {
    player.teleport(server.defaultWorld.spawnLocation)
    player.send("<green>Welcome to spawn!")
}
```

## Arguments

Parameters are written like function parameters. What the player types is converted to their
type:

```tys
@permission("server.give")
command giveitem(target: Player, material: Material, amount: int = 1) {
    target.give(material, amount)
    sender.send("<green>Gave {amount} {material.prettyName} to {target.name}.")
}
```

`/giveitem Steve diamond_sword` and `/giveitem Steve diamond 64` work; Tab completes player names
and materials.

| Type | The player types | Tab completes |
|------|------------------|---------------|
| `string` | one word | — |
| `string...` (last parameter only) | the rest of the line, spaces included | — |
| `int`, `long` | a whole number | — |
| `double`, `float` | a number such as `2.5` | — |
| `bool` | `true`/`false`, `yes`/`no`, `on`/`off`, `1`/`0` | `true`, `false` |
| `Duration` | `90` (seconds), `30s`, `5m`, `1h30m`, `2d`, `20t` (ticks), `500ms` | — |
| `Player` | the name of an online player | online players |
| `OfflinePlayer` | the name of anyone who has played on the server | online players |
| `World` | the name of a loaded world | world names |
| `GameMode` | `survival`, `creative`, `adventure`, `spectator` | the game modes |
| `Material`, `EntityType`, `Sound`, `Enchantment`, `PotionEffectType`, `Particle`, `Biome`, ... | a key such as `diamond_sword` or `minecraft:stone` | the keys |

### Optional arguments

A parameter is optional when it has a default value or a nullable type:

```tys
@playerOnly
command fly(enabled: bool? = null, speed: double = 0.1) {
    let on = enabled ?? !player.allowFlight
    player.allowFlight = on
    player.flySpeed = speed
    player.send("Flight {on ? "enabled" : "disabled"} (speed {speed}).")
}
```

* `name: Type = value` — the value is used when the argument is left out. It must be a
  constant (a number, text, a duration, a constant such as `Material.STONE`...).
* `name: Type?` — the parameter is `null` when the argument is left out.
* Required parameters come first; optional ones after them.

### The rest of the line

The last parameter can take everything that follows, spaces included, with `string...`:

```tys
@permission("server.broadcast")
command announce(message: string...) {
    broadcast("<gold>[Announcement]</gold> {message}")
}

command msg(target: Player, message: string...) {
    target.send("<gray>[{sender.name} -> you]</gray> {message}")
    sender.send("<gray>[you -> {target.name}]</gray> {message}")
}
```

The text a player typed is inserted as plain text, so `/announce <red>hi` shows `<red>hi`
literally — players cannot inject formatting or click actions (see
[Messages and text](Messages-and-Text)).

## What players see when something is wrong

The script only runs when every argument is valid. Otherwise the player gets one of these
messages (they can all be changed in [`config.yml`](Configuration#commandsmessages)):

| Situation | Default message |
|-----------|-----------------|
| An argument is missing | `Missing target. Usage: /giveitem <target> <material> [amount]` |
| Too many arguments | `Too many arguments. Usage: /giveitem <target> <material> [amount]` |
| Not a number | `'lots' is not a number.` |
| Player not online | `Player 'Stve' is not online.` |
| Unknown player (`OfflinePlayer`) | `No player named 'Stve' has played on this server.` |
| Unknown world | `World 'wrld' is not loaded.` |
| Unknown constant | `Unknown Material 'diamnd'.` |
| Not a duration | `'soon' is not a duration (examples: 30s, 5m, 1h30m, 2d).` |
| No permission | `You do not have permission to use this command.` |
| Console runs a `@playerOnly` command | `Only players can use this command.` |
| On cooldown | `Please wait 4s before using this command again.` |
| The script fails with an error | `An error occurred while running this command.` (the error itself goes to the console) |

The usage line is generated from the parameters: `<name>` for required ones, `[name]` for
optional ones, `name...` for the rest of the line. `@usage("...")` replaces it.

On Paper, a root command hidden by its permission may instead produce
`Unknown or incomplete command`, because Minecraft rejects it before TachyonScript
handles it. `@permissionMessage` and `commands.messages.no-permission` do not replace
that server message. Test the command with an authorized sender too.

## Annotations

Annotations are written on the lines before `command`:

| Annotation | Meaning |
|------------|---------|
| `@permission("node")` | Only senders with this permission may use the command. Players without it do not see it in Tab completion. |
| `@permissionMessage("<red>...")` | The message for senders without the permission (MiniMessage). |
| `@playerOnly` | The console cannot use the command; `player` is never `null`. |
| `@aliases("h", "hp")` | Other names of the command (top-level commands only). |
| `@description("...")` | The description shown in `/help`. Defaults to the `///` comment above the command. |
| `@usage("/heal [player]")` | Replaces the generated usage line. |
| `@cooldown(30 seconds)` | How long a sender must wait between two uses. |
| `@cooldownMessage("<red>Wait <remaining>!")` | The cooldown message of this command; `<remaining>` is the time left. |
| `@cooldownBypass("node")` | Senders with this permission have no cooldown. |

```tys
/// Gives a small reward once a day.
@playerOnly
@aliases("reward")
@cooldown(1 day)
@cooldownMessage("<red>Come back in <remaining> for your next reward.")
@cooldownBypass("server.daily.bypass")
command daily {
    player.give(Material.DIAMOND, 1)
    player.send("<gold>Here is your daily diamond!")
}
```

Cooldowns are counted per player (per name for the console) and per command. They start when
the command actually runs — a use that fails because of a wrong argument does not start the
cooldown — and are kept in memory: a server restart resets them. For cooldowns that must
survive restarts, save the last use in a `playerdata var` (see
[Recipes](Recipes#daily-reward-that-survives-restarts)).

## Sub-commands

A dotted name declares a sub-command: `command warp.set` is `/warp set`. Sub-commands of one
command form a tree with its own arguments, permissions and cooldowns:

```tys
persistent var warps: Map<string, Location> = {}

/// Saves your position as a warp.
@playerOnly
@permission("warps.admin")
command warp.set(name: string) {
    warps[name.lower()] = player.location
    player.send("<green>Warp {name} saved.")
}

@permission("warps.admin")
command warp.delete(name: string) {
    if warps.remove(name.lower()) == null {
        sender.send("<red>There is no warp called {name}.")
        return
    }
    sender.send("<green>Warp {name} deleted.")
}

command warp.list {
    if warps.isEmpty {
        sender.send("<gray>No warps yet.")
        return
    }
    sender.send("<gold>Warps: <white>{warps.keys.join(", ")}")
}

@playerOnly
command warp.go(name: string) {
    let target = warps[name.lower()]
    if target == null {
        player.send("<red>There is no warp called {name}. Try /warp list.")
        return
    }
    player.teleport(target)
}
```

Running `/warp` alone lists the sub-commands (`/warp sub-commands: set, delete, list, go`).
A sub-command can go deeper (`command shop.admin.reset`). The sub-commands of one command may
come from several scripts, but keeping them in one script is easier to follow. When two scripts
declare the same command or sub-command, the one in the script that comes first by path is used
and the console shows a warning (`Command /warp set is declared by both ...`).

## Names and conflicts

Command names are lower-case letters, digits and `_`. Commands are registered with the
fallback prefix `tachyonscript`, so `/heal` is also `/tachyonscript:heal`. When another plugin
already has a command with the same name, the console says so and the script command stays
available as `/tachyonscript:heal`.

## Commands that call other commands

`sender.dispatch("...")`, `player.performCommand("...")` and `server.dispatch("...")` run other
commands (without the `/`):

```tys
@playerOnly
@cooldown(10 seconds)
command kit {
    server.dispatch("give {player.name} bread 16")
    server.dispatch("give {player.name} stone_sword 1")
    player.send("<green>Kit received.")
}
```

`server.dispatch` runs the command as the console, so it can do things the player may not.
Only pass values you control, or values of a known shape such as a player's name.

## Next

* [Timers and tasks](Timers-and-Tasks) — countdowns, delays, repeating tasks
* [Saving data](Saving-Data) — remembering what commands change
* [Menus](Menus) — commands that open a GUI
