# Configuration

TachyonScript is configured in `plugins/TachyonScript/config.yml`, created with sensible
defaults on the first start. Most servers never change anything. General configuration edits
take effect after a **server restart**; `/tys reload` reloads scripts. Two commands apply
specific settings live: `/tys performance <milliseconds|off>` saves slow-warning settings,
and `/tys security reload` reads security settings and rescans sources.

Invalid values are reported in the console and replaced by their default, so a typo never stops
the plugin:

```text
[TachyonScript] Unknown reload.mode 'sometimes'; using 'lenient'.
[TachyonScript] safety.recursion-limit must be positive; using 128.
```

## The whole file

```yaml
# TachyonScript configuration.
# Every option has a sensible default; most servers never need to change anything.

reload:
  # What happens when some scripts fail to compile on load or reload:
  #   lenient - failed scripts keep their previous working version (or stay disabled);
  #             all other scripts are updated.
  #   strict  - any failure cancels the whole load; the previous scripts stay active.
  mode: lenient

safety:
  # Maximum nesting of script function calls.
  recursion-limit: 128
  # A single script execution running longer than this is stopped (runaway loops).
  max-execution-time-ms: 1000

performance:
  # Optional tick-thread warnings. Async work is measured by /tys profile only.
  # Use /tys performance <milliseconds|off> to change this without restarting.
  slow-execution-warnings: false
  # At most one warning per function per 30 seconds. 0 also disables warnings.
  slow-execution-warning-ms: 50

runtime:
  # How scripts execute (restart to change):
  #   interpreter - the reference implementation; fast to load, the default
  #   bytecode    - functions are translated to JVM bytecode for HotSpot to compile; faster
  #                 loops and calls, slower loading. Functions too large for HotSpot to
  #                 compile stay interpreted, with a warning in the console.
  backend: interpreter

storage:
  # Where 'persistent var' and 'playerdata var' values are saved:
  #   sqlite - a file in the plugin folder (the default; nothing to set up)
  #   mysql  - a MySQL or MariaDB server (share data between servers)
  #   memory - not saved at all (for testing)
  type: sqlite
  sqlite-file: data.db
  mysql:
    host: localhost
    port: 3306
    database: minecraft
    user: root
    password: ''
    # Extra JDBC connection properties, for example useSSL: 'false'.
    properties: {}
  # How often changed values are written, in seconds. They are also written when a player
  # leaves, on reload and on shutdown. 0 writes only then.
  flush-interval-seconds: 30

# Databases scripts open with Database("name"). SQLite files are relative to the plugin folder.
# Scripts can also open their own SQLite files with Database.sqlite("file.db") (in the
# 'databases' folder) without any configuration.
databases: {}
#  main:
#    type: sqlite
#    file: databases/main.db
#  network:
#    type: mysql
#    host: localhost
#    port: 3306
#    database: network
#    user: minecraft
#    password: secret
#    pool-size: 4
#    properties:
#      useSSL: 'false'

commands:
  # Messages of script commands (MiniMessage). Values in <...> are inserted as plain text.
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
  # Include Java causes in runtime error reports and enable log.debug(...) output.
  enabled: false
# Security approval happens before linking and activation. Quarantine preserves immutable source snapshots.
security:
  enabled: true
  provider: qwen
  max-source-nodes: 25000
  max-findings: 500
  max-pending-tasks: 1024
  allowed-network-hosts: [] # Exact, administrator-trusted exceptions, including private service hosts.
  native-capabilities: {} # Resolved native declaration ID -> capability for addons.
  ai:
    enabled: false # Enable after configuring an endpoint, model and secret.
    # AI outages warn by default; deterministic security enforcement remains active.
    # Set keep-pending to explicitly hold new/modified revisions when AI is unavailable.
    # Replaces legacy required (including required: true in older configurations).
    failure-policy: warn
    # background: loading never waits for the AI. A new or changed script keeps running its
    # previous version (a new script waits) until its review is done, then activates by itself.
    # Reviews are stored in security/ai-reviews.json, so unchanged scripts are never sent again.
    # blocking: every load waits for the reviews of the scripts it changes.
    review-mode: background
    max-concurrent-reviews: 2 # Reviews sent to the provider at the same time (1-8).
    endpoint: "" # Full Qwen-compatible chat/completions URL; no endpoint/model is hard-coded.
    model: ""
    api-key: "${QWEN_API_KEY}"
    connect-timeout-ms: 5000
    read-timeout-ms: 15000
    max-retries: 2
    max-request-bytes: 1048576
    max-response-bytes: 262144
    max-review-parts: 32
    max-output-tokens: 4096 # Bounded per-request completion budget; truncated reviews cannot approve scripts.
    confidence:
      warn: 0.80
      auto-disable: 0.95
  discord:
    enabled: false
    webhook: "${TACHYON_SECURITY_DISCORD_WEBHOOK}"
    include-source-snippets: true
    timeout-ms: 10000
    max-retries: 2
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

These modes describe full reloads. A targeted `/tys reload <file>` reads only that file,
uses active sources for importers, and rolls back the selected group together if any member
fails, even in lenient mode. Operator stops and security revocations cannot be rolled back
into active scripts. See [Admin commands](Admin-Commands#tys-reload).

## safety

| Option | Default | Meaning |
|--------|---------|---------|
| `recursion-limit` | `128` | How deeply script functions may call each other. |
| `max-execution-time-ms` | `1000` | A single execution (one event handler, command, timer run, ...) running longer than this is stopped. |

These limits stop runaway script loops and recursion with an error that points at the script
line. They cannot interrupt an individual blocking native call into the server or an addon.
The limit errors cannot be caught with `try`/`catch`. Raise the time only for scripts that
really do heavy work in one go — and consider `async` or splitting the work over ticks instead.

## performance

| Option | Default | Meaning |
|--------|---------|---------|
| `slow-execution-warnings` | `false` | Enables optional warnings on tick threads. |
| `slow-execution-warning-ms` | `50` | Threshold in milliseconds when warnings are enabled; `0` also disables them. |

A tick normally has a 50 ms budget. Warnings are **off by default**, including when an old
configuration contains only a numeric threshold. Set both keys or use `/tys performance 50`.
The warning names the outer handler, command or task and repeats at most every 30 seconds
for that function. Async work is excluded from warnings but remains in `/tys profile`:

```text
[TachyonScript] Slow script execution: shop.tys:12, event player.move, 57.41 ms
```

It helps find handlers worth optimising; `/tys profile` measures all of them (see
[Admin commands](Admin-Commands)).

## runtime

| Option | Default | Meaning |
|--------|---------|---------|
| `backend` | `interpreter` | How scripts are executed; restart to change. `interpreter` is the reference and loads fastest. `bytecode` turns every function into a JVM class that HotSpot compiles: loops about 11 times and calls about 3 times faster, loading slower (about 150–270 ms for 32 scripts). A function too large for HotSpot to compile stays interpreted, with a console warning naming it. Other values fall back to `interpreter` with a warning. See [Performance and Folia](Performance-and-Folia#bytecode-backend). |

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

## security

Deterministic checks are enabled by default; AI review and Discord delivery are disabled.
Security review runs before activation and can leave a revision pending, disabled or
quarantined even when it compiles. Settings and operator recovery are explained in
[Security](Security). `/tys security reload` applies this section and rescans sources.
Turning the scanner off does not release existing quarantines.

The 0.5.1 security hotfix uses `security.ai.failure-policy: warn`, including for old
configurations containing `required: true`. Choose `keep-pending` explicitly for a
mandatory AI gate. Provider failures are aggregated and throttled; deterministic
denials remain effective in either mode.

Since 0.6.0, `security.ai.review-mode: background` (the default) keeps loading fast: a
reload returns at once, a changed script keeps its previous version until its AI review
finishes and then activates by itself, and finished reviews are stored in
`security/ai-reviews.json` so unchanged scripts are not sent again after a restart.
`blocking` makes every load wait for its reviews, as before. `max-concurrent-reviews`
(1-8, default 2) limits parallel provider requests.

## Files and folders

| Path | Content |
|------|---------|
| `config.yml` | this file |
| `scripts/` | your scripts (`.tys`, subfolders allowed; names starting with `-` are ignored) |
| `data.db` | saved variables with the default SQLite storage |
| `databases/` | SQLite files opened by scripts |
| `files/` | files scripts read and write with `files.*` |
| `logs/` | reports of internal compiler errors (`compiler-error-*.log`) |
| `disabled-scripts.properties` | persistent operator stops created by `/tys disable` |
| `security/` | security audit, preserved source/report snapshots and delivery state |

## Next

* [Admin commands](Admin-Commands)
* [Security](Security)
* [Saving data](Saving-Data) and [Databases](Databases)
* [Performance and Folia](Performance-and-Folia)
