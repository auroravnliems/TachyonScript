# Saved data

Values that must survive a reload or a restart are declared with `persistent var`
(one value for the server) or `playerdata var` (one value per player). Scripts use them
like any other variable; TachyonScript loads, caches and saves them.

```tys
persistent var totalVotes: int = 0
persistent var lastWinner: string? = null
playerdata var votes: int = 0
playerdata var lastVote: Instant? = null

@playerOnly
command vote {
    let last = player.lastVote
    if last != null && time.since(last) < 1 day {
        player.send("<red>You already voted today.")
        return
    }
    player.votes += 1
    player.lastVote = time.now
    totalVotes += 1
    lastWinner = player.name
    player.send("<green>Thanks! You voted {player.votes} times.")
}

placeholder votes {
    return "{player?.votes ?? 0}"
}
```

## How it works

* **Reading costs nothing.** Values are kept in memory; a `persistent var` is read like
  a script variable, a `playerdata var` from a per-player table.
* **Player data is loaded before the player joins** (while they connect), so handlers of
  `player.join` see it at once. It is written and dropped when they leave. Reading the
  data of an offline player (`server.offlinePlayer("Notch").votes`) loads it on demand.
* **Writing is batched.** Changed values are written in the background every
  `storage.flush-interval-seconds` (30 by default), when a player leaves, on reload and
  when the server stops. Only values that changed are written.
* **Changes are atomic.** `player.votes += 1` and `totalVotes += 1` are safe when
  handlers run at the same time on Folia.
* **Editing a script keeps the data.** Values are stored under the script's module name
  and the variable's name; reloading, even with a different initial value, keeps them.
  Renaming the variable starts fresh.

## Where it is stored

In `config.yml`:

```yaml
storage:
  type: sqlite          # sqlite (a file, default), mysql (a server) or memory (not saved)
  sqlite-file: data.db
  mysql:
    host: localhost
    port: 3306
    database: minecraft
    user: root
    password: ''
  flush-interval-seconds: 30
```

SQLite needs no setup. MySQL (or MariaDB) lets several servers share the data. The
values are stored as readable JSON in one table (`tys_data`).

## What can be saved

Numbers, `bool`, `string`, `Component`, `Duration`, `Instant`, `UUID`, `Location`,
`World`, `OfflinePlayer`, `ItemStack` (with all its data), `Vector`, `Color`,
`PotionEffect`, constants such as `Material` or `Sound`, [records](types.md#records)
made of these, and lists and maps of them. Saving a `Player` is not possible (players
come and go); save the player's `uuid` or the `OfflinePlayer`.

For data that must be searched, sorted or shared with other programs, use a
[database](databases.md).
