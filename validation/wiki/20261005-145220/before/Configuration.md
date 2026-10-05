# Configuration

TachyonScript is configured in `plugins/TachyonScript/config.yml`, created with sensible
defaults on the first start. Most servers never change anything. Changes take effect after a
**server restart** (`/tys reload` reloads scripts, not the configuration).

Invalid values are reported in the console and replaced by their default, so a typo never stops
the plugin:

```text
[TachyonScript] Unknown reload.mode 'sometimes'; using 'lenient'.
[TachyonScript] safety.recursion-limit must be positive; using 128.
```

## The whole file

```yaml
reload:
  mode: lenient

safety:
  recursion-limit: 128
  max-execution-time-ms: 1000

performance:
  slow-execution-warning-ms: 5

runtime:
  backend: interpreter

storage:
  type: sqlite
  sqlite-file: data.db
  mysql:
    host: localhost
    port: 3306
    database: minecraft
    user: root
    password: ''
    properties: {}
  flush-interval-seconds: 30

databases: {}

commands:
  messages:
    no-permission: '<red>You do not have permission to use this command.'
    player-only: '<red>Only players can use this command.'
    cooldown: '<red>Please wait <remaining> before using this command again.'
    not-a-number: "<red>'<input>' is not a number."
    invalid-argument: '<red><message>'
    missing-argument: '<red>Missing <name>. Usage: <usage>'
    too-many-arguments: '<red>Too many arguments. Usage: <usage>'
    error: '<red>An error occurred while running this command.'
    subcommands: '<gold>/<label></gold> <gray>sub-commands:</gray> <commands>'

debug:
  enabled: false
```

## reload

| Option | Default | Meaning |
|--------|---------|---------|
| `mode` | `lenient` | What happens when some scripts fail to compile on a load or reload. |

* **lenient** — every script that compiles is activated; a script with errors keeps its
  previous working version (or stays inactive if it never worked). Best for live servers: one
  broken file never takes the others down.
* **strict** — if any script fails, nothing changes at all; the previous scripts stay active.
  Useful when scripts depend on each other and must always be updated together.

## safety

| Option | Default | Meaning |
|--------|---------|---------|
| `recursion-limit` | `128` | How deeply script functions may call each other. |
| `max-execution-time-ms` | `1000` | A single execution (one event handler, command, timer run, ...) running longer than this is stopped. |

These limits make it impossible for a script to freeze the server: an endless loop or an
endless recursion is stopped with an error that points at the script line, and the server
continues. The errors cannot be caught with `try`/`catch`. Raise the time only for scripts that
really do heavy work in one go — and consider `async` or splitting the work over ticks instead.

## performance

| Option | Default | Meaning |
|--------|---------|---------|
| `slow-execution-warning-ms` | `5` | Script executions (handlers, commands, tasks, placeholders...) slower than this are logged (0 disables it). |

A tick lasts 50 ms; a handler taking 5 ms uses a tenth of it. The warning names the handler (or
command, task, placeholder...) and is repeated at most every 30 seconds for each of them:

```text
[TachyonScript] Slow script execution: shop.tys:12, event player.move, 7.41 ms
```

It helps find handlers worth optimising; `/tys profile` measures all of them (see
[Admin commands](Admin-Commands)).

## runtime

| Option | Default | Meaning |
|--------|---------|---------|
| `backend` | `interpreter` | How scripts are executed. Only `interpreter` exists today; a bytecode backend is planned. Other values fall back to `interpreter` with a warning. |

## storage

Where `persistent var` and `playerdata var` values are saved (see [Saving data](Saving-Data)).

| Option | Default | Meaning |
|--------|---------|---------|
| `type` | `sqlite` | `sqlite` (a file), `mysql` (a MySQL or MariaDB server) or `memory` (not saved — for tests) |
| `sqlite-file` | `data.db` | the SQLite file, relative to `plugins/TachyonScript/` |
| `mysql.host`, `mysql.port` | `localhost`, `3306` | the database server |
| `mysql.database`, `mysql.user`, `mysql.password` | `minecraft`, `root`, `''` | login |
| `mysql.properties` | `{}` | extra connection properties, e.g. `useSSL: 'false'` |
| `flush-interval-seconds` | `30` | how often changed values are written (also on quit, reload and shutdown); `0` writes only then |

With MySQL several servers can share the same values — for example coins on every server of a
network. The table `tys_data` is created automatically.

```yaml
storage:
  type: mysql
  mysql:
    host: 10.0.0.5
    port: 3306
    database: network
    user: minecraft
    password: 'a strong password'
    properties:
      useSSL: 'false'
  flush-interval-seconds: 15
```

## databases

Databases scripts open by name with `Database("name")` (see [Databases](Databases)). Scripts can
also open their own SQLite files with `Database.sqlite("file.db")` without any configuration.

```yaml
databases:
  main:
    type: sqlite
    file: databases/main.db     # relative to plugins/TachyonScript/ (default: databases/<name>.db)
  network:
    type: mysql
    host: localhost
    port: 3306
    database: network
    user: minecraft
    password: secret
    pool-size: 4                # connections used at the same time: 1 to 32, default 2
    properties:
      useSSL: 'false'
```

`type` is `sqlite` or `mysql` (MariaDB works with `mysql`). Keeping passwords here means scripts
never contain them, and scripts can be shared or published safely.

## commands.messages

The messages script commands send by themselves, in [MiniMessage](Messages-and-Text). Values in
`<...>` are inserted as plain text — what a player typed can never add formatting.

| Key | Sent when | Values |
|-----|-----------|--------|
| `no-permission` | the sender lacks the command's `@permission` (unless the command has `@permissionMessage`) | |
| `player-only` | the console runs a `@playerOnly` command | |
| `cooldown` | the command is on cooldown (unless it has `@cooldownMessage`) | `<remaining>` |
| `not-a-number` | a number argument is not a number | `<input>` |
| `invalid-argument` | an argument is invalid (unknown player, world, material, ...) | `<message>` |
| `missing-argument` | a required argument is missing | `<name>`, `<usage>` |
| `too-many-arguments` | there are more arguments than the command takes | `<usage>` |
| `error` | the command's script failed with an error (the details go to the console) | |
| `subcommands` | a command group is run without a sub-command | `<label>`, `<commands>` |

A translated example:

```yaml
commands:
  messages:
    no-permission: '<red>Bạn không có quyền dùng lệnh này.'
    player-only: '<red>Chỉ người chơi mới dùng được lệnh này.'
    cooldown: '<red>Hãy đợi <remaining> rồi dùng lại lệnh.'
    not-a-number: "<red>'<input>' không phải là số."
    invalid-argument: '<red><message>'
    missing-argument: '<red>Thiếu <name>. Cách dùng: <usage>'
    too-many-arguments: '<red>Thừa tham số. Cách dùng: <usage>'
    error: '<red>Đã có lỗi khi chạy lệnh này.'
    subcommands: '<gold>/<label></gold> <gray>gồm các lệnh con:</gray> <commands>'
```

`<message>` in `invalid-argument` is written by TachyonScript in English (for example
`Player 'Stve' is not online.`).

## debug

| Option | Default | Meaning |
|--------|---------|---------|
| `enabled` | `false` | Adds the Java cause to runtime error reports and shows `log.debug(...)` output and compiler hints. |

Turn it on while developing scripts or investigating a problem, and off on a live server.

## Files and folders

| Path | Content |
|------|---------|
| `config.yml` | this file |
| `scripts/` | your scripts (`.tys`, subfolders allowed; names starting with `-` are ignored) |
| `data.db` | saved variables with the default SQLite storage |
| `databases/` | SQLite files opened by scripts |
| `files/` | files scripts read and write with `files.*` |
| `logs/` | reports of internal compiler errors (`compiler-error-*.log`) |

## Next

* [Admin commands](Admin-Commands)
* [Saving data](Saving-Data) and [Databases](Databases)
* [Performance and Folia](Performance-and-Folia)
