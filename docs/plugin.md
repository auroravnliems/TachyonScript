# Running TachyonScript on a server

## Requirements

* Paper or Folia 1.21.x (built against 1.21.11), Java 21.
* Optional: [Vault](https://www.spigotmc.org/resources/vault.34315/) (economy, chat
  prefixes, groups) and [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)
  (`%tys_...%` placeholders, `papi.parse`). Nothing else is needed: the SQLite and MySQL
  drivers come with Paper.

## Installation

1. Put `TachyonScript-<version>.jar` into `plugins/` and start the server.
2. TachyonScript creates `plugins/TachyonScript/config.yml` and
   `plugins/TachyonScript/scripts/example.tys`.
3. Write scripts in `plugins/TachyonScript/scripts/` (subfolders are fine) and run
   `/tys reload`.

Files and folders whose name starts with `-` are ignored, which disables a script
without deleting it (`-old-shop.tys`).

The plugin folder then contains:

| Path | Content |
|------|---------|
| `config.yml` | the configuration below |
| `disabled-scripts.properties` | persistent operator stop switches, managed by `/tys disable` and `/tys enable` |
| `scripts/` | the scripts |
| `data.db` | saved variables (`persistent var`, `playerdata var`) with the default SQLite storage |
| `databases/` | SQLite files opened by scripts with `Database.sqlite(...)` |
| `files/` | files scripts read and write with `files.*` |
| `logs/` | reports of internal compiler errors |

## Commands and permissions

| Command | Permission | Description |
|---------|------------|-------------|
| `/tys help` | `tachyonscript.admin` | Lists the commands you may use |
| `/tys reload [script\|all]` | `tachyonscript.reload` | Applies all changes, or only the selected file and necessary importers |
| `/tys disable [script\|all]` | `tachyonscript.manage` | Emergency stop, defaults to all; `diable` is an alias |
| `/tys enable <script\|all>` | `tachyonscript.manage` | Saves the enable switch and reloads the selected scripts |
| `/tys performance <ms\|off>` | `tachyonscript.profile` | Changes slow warnings live and saves the setting |
| `/tys scripts` | `tachyonscript.admin` | Lists scripts and what each declares (handlers, commands, tasks, placeholders) |
| `/tys info <script>` | `tachyonscript.admin` | Module name, events, commands, tasks, placeholders, saved variables and size of a script |
| `/tys errors` | `tachyonscript.admin` | Compile errors of the last load and runtime errors since then |
| `/tys status` | `tachyonscript.admin` | Storage, databases, script commands and placeholders |
| `/tys profile start\|stop\|report` | `tachyonscript.profile` | Measures time spent per handler, command, task, scheduled block and placeholder |
| `/tys dump <script> [ir\|code]` | `tachyonscript.debug` | Prints the compiled form to the console |
| `/tys version` | `tachyonscript.admin` | Versions, server type and loaded addons |
| `/tys security <action>` | `tachyonscript.security.admin` | Inspect, scan, approve and quarantine; see [security](security.md) |

`tachyonscript.*` grants everything. All permissions default to operators. Commands
declared by scripts have their own permissions (`@permission("...")`) and their own
`/help` pages (description, usage, aliases, sub-commands), kept up to date across reloads.

## Reloading

`/tys reload` compiles on a background thread; the server keeps running the old
scripts meanwhile. Only files that changed are recompiled (and the files that import
them). When compilation finishes:

* **lenient** mode (default): every script that compiled is activated; a script
  with errors keeps its previous working version (or stays inactive if it never
  worked). The errors are printed to the console and to whoever ran the command.
* **strict** mode: if any script fails, nothing changes.

The switch to the new scripts is atomic: an event is handled entirely by the old
or entirely by the new scripts. A reloaded script's `on unload` code runs, its timers
stop, its menus close and its boss bars and sidebars disappear; saved variables keep
their values.

`/tys reload <script>` reads only that file from disk. Importers that need recompiling
use their currently active source, as do all other dependencies. Unrelated edits,
new files and deletions are left for a later reload. The selected file is recompiled
even when unchanged. If the selected group cannot compile/link, the entire group
keeps its old versions, including in lenient mode. Unrelated timers, variables and
runtime-error history stay intact. `/tys reload all` explicitly applies all disk changes.

## Emergency stop

Use `/tys disable shop.tys` for a faulty shop, or `/tys disable` to stop all scripts.
Disabling also stops transitive importers, revokes old exports/callbacks, cancels
timers and releases resources. It skips `on unload` script code so a faulty cleanup
hook cannot keep paying rewards. Already completed external effects are not undone.
The stop survives reload/restart; an all-stop also blocks newly added scripts.

After fixing and checking the code, use `/tys enable shop.tys`. Importers disabled
alongside it need enabling separately, or use `/tys enable all`. A global stop
requires `enable all`. `/tys scripts` and `/tys info` show operator-disabled status.
The state file must be writable: failed persistence still stops execution in this
process and reports the save failure; a failed enable save keeps the stop gate closed.

## Configuration

```yaml
reload:
  mode: lenient                  # or strict
safety:
  recursion-limit: 128           # maximum nesting of script function calls
  max-execution-time-ms: 1000    # a single execution is stopped after this
performance:
  slow-execution-warnings: false # opt in; old 5/10 ms thresholds do not enable it
  slow-execution-warning-ms: 50  # tick-thread threshold; 0 also disables
runtime:
  backend: interpreter           # the bytecode backend is planned

storage:                         # where persistent/playerdata variables are saved
  type: sqlite                   # sqlite, mysql or memory
  sqlite-file: data.db
  mysql:
    host: localhost
    port: 3306
    database: minecraft
    user: root
    password: ''
    properties: {}               # extra JDBC properties
  flush-interval-seconds: 30     # changed values are written this often (and on quit, reload, stop)

databases:                       # opened by scripts with Database("name")
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

commands:
  messages:                      # MiniMessage; <values> are inserted as plain text
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
  enabled: false                 # Java causes in error reports, log.debug output
```

Invalid values are reported and replaced by defaults. Manual config edits need a
restart. `/tys performance 50` and `/tys performance off` apply immediately and save
the settings. Slow warnings measure only outermost tick-thread executions, at most
once per function per 30 seconds. Async work remains available in `/tys profile`.

## Errors

* Compile errors name the file, line and column, show the code and usually a
  suggestion. A script with errors never replaces a working version.
* Runtime errors (a list index out of range, a failed `as`, a runaway loop) stop
  that one handler, are logged with a TachyonScript stack trace, and are counted:
  the same error at the same place is logged once and then summarized, so a
  handler failing on every move event cannot flood the console. `/tys errors`
  shows the counts.
* Database statements that fail are logged with the script and the SQL.
* Internal compiler errors (bugs in TachyonScript) print a short message and write
  details to `plugins/TachyonScript/logs/compiler-error-*.log`, without the script
  contents; please attach that file to bug reports.

## Integrations

* **PlaceholderAPI**: when it is installed, TachyonScript registers the expansion
  `tys`, so `%tys_<name>%` shows the script placeholder `placeholder <name> { ... }`.
* **Vault**: `economy.*`, `chat.prefix/suffix` and `permissions.group(s)` use the
  plugins registered with Vault. Without Vault they return neutral values.

## Folia

TachyonScript declares `folia-supported: true`. Handlers run on the region thread
that fired the event; changes to entities and blocks owned by other regions are
scheduled on their owners automatically, and `after ... for <entity>` runs code on the
entity's thread. Sidebars (scoreboards) are the only feature Folia does not support.
Nothing needs to be configured.
