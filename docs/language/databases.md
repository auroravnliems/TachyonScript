# Databases

Scripts can use SQL databases directly: SQLite files that need no setup, or MySQL /
MariaDB servers configured by the server owner. Queries run on background threads, so
a slow database never freezes the server, and their results come back to the server
thread.

```tys
let db = Database.sqlite("shop.db")         // plugins/TachyonScript/databases/shop.db

on load {
    db.execute("CREATE TABLE IF NOT EXISTS sales (player TEXT, item TEXT, price REAL, time INTEGER)")
}

@playerOnly
command buy(item: string, price: double) {
    db.update("INSERT INTO sales VALUES (?, ?, ?, ?)", [player.uuid, item, price, time.now], count => {
        player.send("<green>Saved your purchase of {item}.")
    })
}

@playerOnly
command history {
    db.query("SELECT item, price FROM sales WHERE player = ? ORDER BY time DESC LIMIT 5", [player.uuid], rows => {
        if rows.isEmpty {
            player.send("<gray>You have not bought anything yet.")
        }
        for row in rows {
            let item = row.string("item")
            let price = row.double("price")
            player.send("<gray>- {item} for {price}")
        }
    })
}
```

## Opening a database

* `Database.sqlite("file.db")` opens (and creates) an SQLite file in
  `plugins/TachyonScript/databases/`.
* `Database("name")` opens a database configured in `config.yml`, so scripts never
  contain passwords:

```yaml
databases:
  network:
    type: mysql
    host: localhost
    port: 3306
    database: network
    user: minecraft
    password: secret
    pool-size: 4
  local:
    type: sqlite
    file: databases/local.db
```

A database is opened once and shared by all scripts; its connections are closed when
the server stops.

## Running statements

| Call | Result |
|------|--------|
| `db.execute(sql)`, `db.execute(sql, values)` | Runs a statement in the background. |
| `db.update(sql, values, count => ...)` | Then calls the function with the number of changed rows. |
| `db.query(sql, values, rows => ...)` | Then calls the function with the rows (`List<Row>`). |
| `db.query(sql, rows => ...)` | The same, without values. |
| `db.queryFirst(sql, values, row => ...)` | Then calls the function with the first row, or `null`. |
| `db.querySync(sql, values)` | Waits for the rows; only off the server thread (inside `async { }`). |
| `db.updateSync(sql, values)` | Waits for the number of changed rows; only off the server thread. |

Statements of one database run in order. The functions are called on the server
thread, and only if their script is still loaded. If a statement fails, the error is
logged with the script's name and the SQL.

A `Row` is read by column name: `row.string("name")`, `row.int(...)`, `row.long(...)`,
`row.double(...)`, `row.bool(...)`, `row.uuid(...)`, `row.instant(...)`, `row.get(...)`
(any value) and `row.columns`. Each returns `null` for SQL `NULL`.

## SQL injection is impossible

SQL text has the type `Sql`, which only accepts text written in the script. Values go
into `?` placeholders and are passed separately, so a player's input can never change
a query. Building SQL from values is a compile error:

```text
ERROR scripts/shop.tys:4:14 [TYS0235]

  4 |     db.query("SELECT * FROM sales WHERE item = '" + item + "'", [], rows => {
    |              ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Sql text must be written in the script, not built while it runs.
```

Values can be text, numbers, `bool`, `null`, `UUID` (stored as text), `Instant` and
`Duration` (stored as milliseconds); other values are stored as their text.

## When to use what

| Need | Use |
|------|-----|
| A few values per player or for the server | [`playerdata var` / `persistent var`](storage.md) |
| Searching, sorting, top lists, logs, large tables | a database |
| Sharing data with a website or another program | a MySQL database |
