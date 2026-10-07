# Saving data

Ordinary variables start again from their initial value when a script is reloaded or the
server restarts. Two kinds of variables are **saved** instead:

| Declared with | One value... | Used as |
|---------------|--------------|---------|
| `persistent var` | for the whole server | `totalJoins`, `warps["spawn"]` |
| `playerdata var` | for each player | `player.coins`, `target.homes["base"]` |

You use them like any other variable. TachyonScript loads them, keeps them in memory, and
writes changes to a database in the background — scripts never wait for the database.

```tys
persistent var totalJoins: int = 0
persistent var firstPlayer: string? = null
playerdata var joins: int = 0
playerdata var lastVisit: Instant? = null

event player.join {
    totalJoins += 1
    player.joins += 1
    if firstPlayer == null {
        firstPlayer = player.name
    }
    let last = player.lastVisit
    if last != null {
        player.send("<gray>Welcome back! Your last visit was {format.duration(time.since(last))} ago.")
    }
    player.send("<gray>Visit #{player.joins} for you, #{totalJoins} for the server. First ever: {firstPlayer}")
}

event player.quit {
    player.lastVisit = time.now
}
```

## Server-wide values: `persistent var`

```tys
persistent var warps: Map<string, Location> = {}
persistent var motd: string = "Welcome!"
persistent var lastWinner: string? = null
persistent var bannedWords: List<string> = ["badword"]
```

* The type must be written (or be obvious from the initial value) and must be one that can be
  saved (see [below](#what-can-be-saved)).
* The initial value is used the very first time, when nothing is saved yet.
* After that the saved value is used, even if you change the initial value in the script.

## Per-player values: `playerdata var`

A `playerdata var` gives every player their own value. It is read and written as if it were a
property of the player:

```tys
playerdata var coins: int = 100
playerdata var homes: Map<string, Location> = {}

@playerOnly
command sethome(name: string = "home") {
    if player.homes.size >= 3 && !(name in player.homes) {
        player.send("<red>You already have 3 homes.")
        return
    }
    player.homes[name] = player.location
    player.send("<green>Home {name} saved.")
}

@playerOnly
command home(name: string = "home") {
    let target = player.homes[name]
    if target == null {
        player.send("<red>No home called {name}. Your homes: {player.homes.keys.join(", ")}")
        return
    }
    player.teleport(target)
}

@playerOnly
command pay(target: Player, amount: int) {
    if amount <= 0 || player.coins < amount {
        player.send("<red>You cannot pay that.")
        return
    }
    player.coins -= amount
    target.coins += amount
    player.send("<green>Sent {amount} coins to {target.name}.")
    target.send("<green>{player.name} sent you {amount} coins.")
}
```

The name of a `playerdata var` must not be a member the player already has: `playerdata var
level` or `playerdata var lastSeen` are compile errors (`'lastSeen' is already a member of
Player.`), so the name always says unambiguously which value is meant.

A player's data is loaded **while they connect**, before they are in the world, so
`player.join` handlers see it at once. It is written when they leave and then dropped from
memory.

### Offline players

`playerdata` values also work on an `OfflinePlayer` — any player who has played before:

```tys
playerdata var coins: int = 100

@permission("server.eco.admin")
command eco.give(target: OfflinePlayer, amount: int) {
    target.coins += amount
    sender.send("<green>{target.name ?? "?"} now has {target.coins} coins.")
}
```

When the player is offline their data is read from the database on demand. That takes a moment,
so avoid it in frequent events; if it takes longer than 50 ms the console warns:

```text
[TachyonScript] Loading the saved data of 069a79f4-44e9-4726-a5be-fca90e38aaf5 took 83.4 ms while a script waited for it.
```

For rankings over all players (the richest, the top killers) use a [database](Databases): it
can sort and search thousands of players without loading each one.

## Changes are safe

* `player.coins += 5`, `totalJoins++` and `map[key] += 1` are **atomic**: handlers running at the
  same time on Folia never lose an update.
* Lists and maps in saved variables can be changed in place (`warps["spawn"] = ...`,
  `player.homes.remove("old")`); the change is detected and saved.

## When values are written

* Every `storage.flush-interval-seconds` (30 by default), in the background: only values that
  changed since the last write are written.
* When a player leaves (their values).
* When scripts are reloaded and when the server stops.

With `flush-interval-seconds: 0` values are written only on quit, reload and shutdown. If the
server crashes, changes since the last write are lost — at most one interval's worth.

## Reloading and editing scripts

Saved values belong to the script's **module** and the variable's **name**. So:

* reloading a script keeps its values — also when you edit the code or the initial value;
* renaming a variable starts a new, empty variable (the old value stays in the database, unused);
* renaming or moving the file changes its module name (`shop.tys` is `shop`,
  `eco/bank.tys` is `eco.bank`) and therefore also starts fresh — unless the file declares its
  name with `module shop`, which keeps it stable (see [Modules](Modules));
* changing a variable's type to one the saved text does not fit (for example `int` to
  `Location`) starts it from its initial value, with a warning:

```text
[TachyonScript] The saved value of shop.stock cannot be loaded as Location (...); the variable starts with its initial value. Saved text: 42
```

* adding a field to a saved record keeps the data when the new field has a default value or a
  nullable type: records saved before the field existed get the default (or `null`). A new
  field with neither makes the saved records unreadable, and the variable starts from its
  initial value — for `playerdata var homes: Map<string, Home>`, every player's homes:

```tys
// Before: record Home(spot: Location, icon: Material)
record Home(spot: Location, icon: Material, visits: int = 0, note: string? = null)

playerdata var homes: Map<string, Home> = {}
```

## What can be saved

| Can be saved | Examples |
|--------------|----------|
| numbers, `bool`, `string` | `int`, `long`, `double`, `"text"` |
| text components | `Component` |
| time | `Duration`, `Instant` |
| identifiers | `UUID` |
| places | `Location`, `World`, `Vector` |
| players | `OfflinePlayer` (not `Player`) |
| items | `ItemStack` — with its name, lore, enchantments, tags and all other data |
| effects and colors | `PotionEffect`, `Color` |
| constants | `Material`, `Sound`, `EntityType`, `Enchantment`, `GameMode`, ... |
| records | `record Home(name: string, spot: Location)` whose fields can be saved |
| collections | `List<T>`, `Map<K, V>` and `T?` of any of the above |

An online `Player` cannot be saved — players come and go. Save the player's `uuid` or the
`OfflinePlayer` instead. The compiler tells you when a type cannot be saved:

```text
ERROR scripts/game.tys:13:24 [TYS0239]

  13 | persistent var winner: Player? = null
     |                        ^^^^^^^

Values of type Player? cannot be saved.

Saved variables can hold numbers, bool, string, Duration, Instant, UUID, Location, ItemStack, world and Minecraft constants, records of those, and lists and maps of them.
```

## Where values are stored

In `plugins/TachyonScript/config.yml`:

```yaml
storage:
  type: sqlite          # sqlite (a file, the default), mysql (a server) or memory (not saved)
  sqlite-file: data.db
  mysql:
    host: localhost
    port: 3306
    database: minecraft
    user: root
    password: ''
    properties: {}      # extra connection properties, e.g. useSSL: 'false'
  flush-interval-seconds: 30
```

* **sqlite** (the default) needs no setup: values go to `plugins/TachyonScript/data.db`.
* **mysql** (MySQL or MariaDB) lets several servers share the same values — for example coins
  on every server of a network.
* **memory** saves nothing; useful for a test server.

Everything is stored in one table, `tys_data`, with the columns `owner` (a player's UUID, or
empty for `persistent` values), `scope` (the module), `name` (the variable), `value` (the value
as readable JSON) and `updated` (when it was written). `/tys status` shows where values are
saved.

## Saved variables or a database?

| Need | Use |
|------|-----|
| A few values for the server or per player (coins, homes, settings, statistics) | `persistent var` / `playerdata var` |
| Rankings over all players, searches, history, logs, large tables | a [database](Databases) |
| Sharing data with a website or another program | a MySQL [database](Databases) |

## Next

* [Databases](Databases) — SQL written in scripts
* [Lists, maps and records](Lists-Maps-and-Records) — structuring saved values
* [Configuration](Configuration) — all storage options
