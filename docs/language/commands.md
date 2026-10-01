# Commands

A script declares commands with `command`. Arguments have types: the command parses
and checks what the player typed, completes it with Tab, and shows a usage message
when something is missing or wrong — before the script runs.

```tys
@permission("server.heal")
@description("Heals a player")
@aliases("hl")
command heal(target: Player? = null, amount: int = 20) {
    let who = target ?? player
    if who == null {
        sender.send("<red>The console must name a player.")
        return
    }
    who.health = math.min(amount as double, who.maxHealth)
    sender.send("<green>Healed {who.name}.")
}
```

`/heal`, `/heal Steve` and `/heal Steve 10` all work; `/heal Steve lots` answers
`'lots' is not a number.`

## Inside a command

* `sender` is who ran the command (a player or the console);
* `player` is the player who ran it, or `null` for the console. With `@playerOnly` the
  command refuses the console and `player` is a `Player`;
* the parameters hold the parsed arguments.

## Argument types

| Type | Accepts | Completion |
|------|---------|------------|
| `string` | one word | — |
| `string...` (last parameter) | the rest of the line | — |
| `int`, `long`, `double`, `float` | numbers | — |
| `bool` | `true`/`false`, `yes`/`no`, `on`/`off` | yes |
| `Duration` | `90`, `30s`, `5m`, `1h30m`, `2d`, `20t` | — |
| `Player` | an online player's name | player names |
| `OfflinePlayer` | any player who has played before | online names |
| `World` | a world name | world names |
| `GameMode` | `survival`, `creative`, ... | yes |
| `Material`, `EntityType`, `Sound`, `Enchantment`, ... | a key such as `diamond_sword` | yes |

An argument is optional when it has a default value or a nullable type (`Player?`).

## Annotations

| Annotation | Meaning |
|------------|---------|
| `@permission("node")` | Only senders with the permission may run the command (it is hidden from others). |
| `@permissionMessage("<red>...")` | The message for senders without the permission. |
| `@aliases("a", "b")` | Other names of the command. |
| `@description("...")` | Shown in `/help` (defaults to the `///` comment). |
| `@usage("/heal [player]")` | Replaces the generated usage line. |
| `@cooldown(30 seconds)` | How long a sender must wait between uses. |
| `@cooldownMessage("<red>Wait <remaining>")` | The cooldown message of this command. |
| `@cooldownBypass("node")` | Senders with this permission have no cooldown. |
| `@playerOnly` | The console cannot use the command. |

## Sub-commands

A dotted name declares a sub-command. Running the group alone lists its sub-commands:

```tys
persistent var warps: Map<string, Location> = {}

@playerOnly
@permission("warps.set")
command warp.set(name: string) {
    warps[name] = player.location
    player.send("<green>Warp {name} set.")
}

@playerOnly
command warp.go(name: string) {
    let target = warps[name]
    if target == null {
        player.send("<red>No warp named {name}.")
        return
    }
    player.teleport(target)
}

command warp.list {
    sender.send("Warps: {warps.keys.join(", ")}")
}
```

Sub-commands of one command may come from several scripts; keeping them in one script is
easier to read. When two scripts declare the same command (or sub-command), the one in the
script that comes first by path is used and the console shows a warning.

## Messages

The messages commands send (usage, missing permission, cooldown, invalid numbers, ...)
are set in `config.yml` under `commands.messages`, in MiniMessage. Values such as the
usage line or what the player typed are inserted as plain text.

Commands are registered when their script loads and removed when it is unloaded;
players' command lists (and Tab completion) update immediately, without a relog.
