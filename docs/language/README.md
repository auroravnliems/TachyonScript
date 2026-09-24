# The TachyonScript language

TachyonScript is a small, statically typed language for Paper and Folia servers.
Scripts are plain text files ending in `.tys` in `plugins/TachyonScript/scripts/`.
They are compiled when they are loaded; mistakes are reported with the file, line,
column and a suggestion before anything runs.

> Status: this describes what is implemented today. Features that are designed but
> not built yet (commands, scheduling, persistent data, imports, ...) are listed in
> [`../status.md`](../status.md) and are reported by the compiler as "not supported
> yet" if you use them.

## A first script

```tys
// plugins/TachyonScript/scripts/welcome.tys

const PREFIX = "<gold>[Server]</gold> "

event player.join {
    player.send("{PREFIX}<green>Welcome, {player.name}!")
    if player.health < 10.0 {
        player.health = player.maxHealth
    }
}
```

Save the file and run `/tys reload`. If the script has an error, the console shows
it and the previous version keeps running:

```text
ERROR scripts/welcome.tys:6:12 [TYS0201]

  6 |     player.sned("{PREFIX}<green>Welcome, {player.name}!")
    |            ^^^^

Unknown member 'sned' on Player.

Did you mean:
    send
```

## Contents

* [Variables and constants](variables.md)
* [Types, lists and null safety](types.md)
* [Control flow](control-flow.md)
* [Functions](functions.md)
* [Events](events.md)
* [Messages and MiniMessage](messages.md)
* [Standard library reference](reference.md) (generated from the declarations)

For the precise rules (grammar, precedence, conversions) see
[`../compiler/`](../compiler/).
