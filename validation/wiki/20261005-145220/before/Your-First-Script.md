# Your first script

Create `plugins/TachyonScript/scripts/welcome.tys`:

```tys
// Greets players when they join.
event player.join {
    player.send("<green>Welcome, {player.name}!")
}
```

Run `/tys reload`. The console answers:

```text
[TachyonScript] Loaded 2 scripts, 1 event handler (1 compiled, 1 unchanged) in 35.2 ms
```

Join the server: you are greeted in green.

## What happened

* `event player.join { ... }` is an **event handler**: the code between the braces runs
  every time a player joins.
* Inside it, `player` is the player who joined. `player.send(...)` sends them a chat
  message.
* The text is [MiniMessage](https://docs.advntr.dev/minimessage/format.html):
  `<green>` colors it. `{player.name}` inserts a value.

## Making a mistake on purpose

Change `send` to `sned` and run `/tys reload`:

```text
ERROR scripts/welcome.tys:3:12 [TYS0201]

  3 |     player.sned("<green>Welcome, {player.name}!")
    |            ^^^^

Unknown member 'sned' on Player.

Did you mean:
    send

[TachyonScript] Loaded 2 scripts (1 errors)
[TachyonScript] Kept the previous working version of: welcome.tys
```

The script was not replaced: players are still greeted by the previous version until
you fix the typo. That is true for every mistake the compiler can find — wrong types,
missing arguments, `null` values not checked, unknown materials and more.

## Adding a command

```tys
event player.join {
    player.send("<green>Welcome, {player.name}!")
}

@description("Shows how healthy you are")
@playerOnly
command health {
    let hearts = math.round(player.health / 2.0)
    player.send("You have <red>{hearts}</red> hearts and {player.food} food.")
}
```

After `/tys reload`, `/health` exists (with Tab completion and `/help` text). No
restart and no relog are needed.

## Remembering things

Variables declared with `playerdata var` are saved for each player, even across
restarts:

```tys
playerdata var joins: int = 0

event player.join {
    player.joins += 1
    if player.joins == 1 {
        broadcast("<yellow>Everyone welcome {player.name}, who joined for the first time!")
    } else {
        player.send("<gray>Welcome back! This is visit #{player.joins}.")
    }
}
```

## Where to go next

* [Language basics](Language-Basics) — variables, types and operators
* [Events](Events) and [Commands](Commands)
* [Recipes](Recipes) — complete scripts for common needs
