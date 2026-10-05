# Databases

For data that has to be searched, sorted or shared — leaderboards, logs, reports, auctions,
data read by a website — scripts can use SQL databases directly:

* **SQLite**: a file in `plugins/TachyonScript/databases/`, created on first use, no setup;
* **MySQL / MariaDB**: a database server configured by the server owner in `config.yml`.

Queries run on background threads, so a slow database never freezes the server. When the
answer is ready, your function is called back on the server thread.

```tys
let db = Database.sqlite("kills.db")

on load {
    db.execute("CREATE TABLE IF NOT EXISTS kills (killer TEXT, victim TEXT, weapon TEXT, time INTEGER)")
}

event player.death {
    if killer != null {
        let weapon = killer.mainHand?.type?.key ?? "hand"
        db.execute("INSERT INTO kills VALUES (?, ?, ?, ?)", [killer.name, victim.name, weapon, time.now])
    }
}

command topkillers {
    db.query("SELECT killer, COUNT(*) AS total FROM kills GROUP BY killer ORDER BY total DESC LIMIT 5", rows => {
        sender.send("<gold>Top killers:")
        var place = 1
        for row in rows {
            sender.send("<yellow>{place}. <white>{row.string("killer") ?? "?"} <gray>- {row.long("total") ?? 0} kills")
            place++
        }
    })
}
```

## Opening a database

| Call | Opens |
|------|-------|
| `Database.sqlite("shop.db")` | the SQLite file `plugins/TachyonScript/databases/shop.db` (folders allowed: `"games/arena.db"`) |
| `Database("network")` | the database named `network` in `config.yml` |

Keep the database in a script variable (`let db = ...` at the top of the file). A database is
opened once and shared: two scripts opening `Database.sqlite("shop.db")` use the same
connection. Connections are opened on first use, reopened if they break, and closed when the
server stops.

Configured databases keep passwords out of scripts:

```yaml
databases:
  network:
    type: mysql            # MySQL or MariaDB
    host: db.example.com
    port: 3306
    database: network
    user: minecraft
    password: secret
    pool-size: 4           # connections used at the same time (1 to 32, default 2)
    properties:            # extra connection properties
      useSSL: 'false'
  local:
    type: sqlite
    file: databases/local.db   # relative to plugins/TachyonScript
```

`Database("name")` with a name that is not configured is a runtime error that lists the
configured names. Nothing needs to be installed: Paper already contains the SQLite and MySQL
drivers.

## Running statements

| Call | What happens |
|------|--------------|
| `db.execute(sql)` | runs a statement in the background (`CREATE TABLE`, `INSERT`, `DELETE`, ...) |
| `db.execute(sql, values)` | the same, with values for the `?` placeholders |
| `db.update(sql, values, count => ...)` | runs a statement, then calls the function with the number of changed rows |
| `db.query(sql, rows => ...)` | runs a query, then calls the function with the rows (`List<Row>`) |
| `db.query(sql, values, rows => ...)` | the same, with values |
| `db.queryFirst(sql, values, row => ...)` | runs a query, then calls the function with the first row, or `null` |
| `db.querySync(sql, values)` | runs a query and **waits** for the rows — only off the server thread |
| `db.updateSync(sql, values)` | runs a statement and waits for the count — only off the server thread |

The functions are called on the server thread (the global region on Folia), and only if the
script that started the query is still loaded.

```tys
let db = Database.sqlite("shop.db")

on load {
    db.execute("CREATE TABLE IF NOT EXISTS stock (item TEXT PRIMARY KEY, amount INTEGER NOT NULL)")
}

@permission("shop.admin")
command stock.set(material: Material, amount: int) {
    db.update("INSERT INTO stock VALUES (?, ?) ON CONFLICT(item) DO UPDATE SET amount = excluded.amount",
        [material.key, amount], count => {
        sender.send("<green>Stock of {material.prettyName} set to {amount}.")
    })
}

command stock.show(material: Material) {
    db.queryFirst("SELECT amount FROM stock WHERE item = ?", [material.key], row => {
        if row == null {
            sender.send("<gray>{material.prettyName} is not sold here.")
            return
        }
        sender.send("<gray>{material.prettyName}: <white>{row.int("amount") ?? 0} in stock")
    })
}
```

## Values and placeholders

Values are never pasted into the SQL text. Write `?` where a value goes and pass the values in
a list, in the same order:

```tys-body
let db = Database.sqlite("logs.db")
db.execute("INSERT INTO logins (uuid, name, address, time) VALUES (?, ?, ?, ?)",
    [player.uuid, player.name, player.address, time.now])
```

| Script value | Stored as |
|--------------|-----------|
| `string` | text |
| `int`, `long` | a whole number |
| `double`, `float` | a decimal number |
| `bool` | a boolean (`1`/`0` in SQLite) |
| `null` | SQL `NULL` |
| `UUID` | its text (`069a79f4-44e9-...`) |
| `Instant` | milliseconds since 1970 (read it back with `row.instant(...)`) |
| `Duration` | milliseconds |
| anything else | its text, as `"{value}"` would show it |

The number of values must match the number of `?`; otherwise the statement fails with
`The statement has 3 '?' placeholders but 2 values were given.`

## SQL injection is impossible

SQL text has the type `Sql`, which accepts only text written in the script: a string literal or
a `const`. Building SQL from values while the script runs — the classic SQL injection — is a
compile error:

```text
ERROR scripts/shop.tys:3:14 [TYS0235]

  3 |     db.query("SELECT * FROM sales WHERE item = '" + item + "'", rows => {
    |              ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Sql text must be written in the script, not built while it runs.

Values must never be pasted into Sql text: a player could put commands into it. Write '?' where a value goes and pass the values separately:

db.query("SELECT coins FROM bank WHERE uuid = ?", [player.uuid], rows => { ... })
```

Long statements can be kept in constants:

```tys
const TOP_BALANCES = "SELECT name, coins FROM bank ORDER BY coins DESC LIMIT 10"

let bank = Database.sqlite("bank.db")

command baltop {
    bank.query(TOP_BALANCES, rows => {
        for row in rows {
            sender.send("{row.string("name") ?? "?"}: {row.long("coins") ?? 0}")
        }
    })
}
```

## Reading rows

A `Row` is read by column name (or by the alias given with `AS`):

| Member | Result |
|--------|--------|
| `row.string("name")` | `string?` |
| `row.int("n")`, `row.long("n")` | `int?`, `long?` |
| `row.double("n")` | `double?` |
| `row.bool("n")` | `bool?` (numbers: `0` is false) |
| `row.uuid("n")` | `UUID?` (from text) |
| `row.instant("n")` | `Instant?` (from milliseconds, as saved from an `Instant`) |
| `row.get("n")` | `any?` — the raw value (text, `long`, `double`, `bool` or `null`) |
| `row.columns` | `List<string>` — the column names, in query order |

Every reader returns `null` for SQL `NULL`, so give defaults with `??`. Asking for a column the
query did not return is a runtime error that lists the available columns; asking for a number
from a column holding text that is not a number is an error too.

A query returns at most 100,000 rows; for more, the query fails and asks for a `LIMIT`.

## Waiting for results: `querySync` and `updateSync`

Sometimes it is simpler to wait for the result — for example to run several statements one
after the other. Waiting on the server thread would freeze the server, so the `...Sync`
functions only work off it, typically inside `async { }`:

```tys
let db = Database.sqlite("bank.db")

@playerOnly
command transfer(target: OfflinePlayer, amount: long) {
    if amount <= 0 {
        player.send("<red>The amount must be positive.")
        return
    }
    async {
        let taken = db.updateSync("UPDATE bank SET coins = coins - ? WHERE uuid = ? AND coins >= ?",
            [amount, player.uuid, amount])
        if taken == 0 {
            sync {
                player.send("<red>You do not have {amount} coins.")
            }
            return
        }
        db.updateSync("UPDATE bank SET coins = coins + ? WHERE uuid = ?", [amount, target.uuid])
        sync {
            player.send("<green>Sent {amount} coins to {target.name ?? "?"}.")
        }
    }
}
```

On the server thread they fail at once with a clear message
(`querySync would freeze the server while the database works. Use the version with a function
(query, update), or call it inside async { }.`). A call waits at most 30 seconds.

Note how the example checks the balance **inside** the SQL (`AND coins >= ?`): the database
checks and changes in one step, so two transfers at the same time can never both spend the same
coins.

## Order and threads

* An SQLite database runs its statements one at a time, in the order the scripts sent them.
* A MySQL database runs up to `pool-size` statements at the same time. Statements sent one after
  the other may finish in a different order; when the order matters, run them in one
  `async { }` block with the `...Sync` functions, or combine them into a single statement.
* Callbacks run on the server thread. The player who started a query may have left by the time
  it answers — `player.online` tells.

## Errors

A statement that fails does not stop the script. The error is logged with the database, the
script and the SQL, and the callback is not called:

```text
[TachyonScript] Database 'shop.db': a statement from shop.tys failed: [SQLITE_ERROR] SQL error or missing database (no such table: stok) (SQL: SELECT amount FROM stok WHERE item = ?)
```

The `...Sync` functions throw instead, so their errors can be caught with `try`/`catch`.

## SQLite or MySQL?

| | SQLite | MySQL / MariaDB |
|--|--------|-----------------|
| Setup | none | a database server |
| Shared by several Minecraft servers | no | yes |
| Readable by a website | only on the same machine | yes |
| Writes at the same time | one at a time (fast enough for most servers) | many |

The SQL dialects differ a little: an "insert or update" is
`INSERT ... ON CONFLICT(key) DO UPDATE SET ...` in SQLite and
`INSERT ... ON DUPLICATE KEY UPDATE ...` in MySQL.

## Databases or saved variables?

For a few values per player or for the server, [saved variables](Saving-Data) are simpler:
no SQL, no callbacks. Use a database when you need to search, sort or count over many
players, keep a history, or share the data with other programs.

## Next

* [Recipes](Recipes) — a complete economy, a report system and a leaderboard with databases
* [Timers and tasks](Timers-and-Tasks) — `async` and `sync`
* [Error handling](Error-Handling)
