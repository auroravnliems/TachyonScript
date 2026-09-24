# Running TachyonScript on a server

## Requirements

* Paper or Folia 1.21.x (built against 1.21.11), Java 21.

## Installation

1. Put `TachyonScript-<version>.jar` into `plugins/` and start the server.
2. TachyonScript creates `plugins/TachyonScript/config.yml` and
   `plugins/TachyonScript/scripts/example.tys`.
3. Write scripts in `plugins/TachyonScript/scripts/` (subfolders are fine) and run
   `/tys reload`.

Files and folders whose name starts with `-` are ignored, which disables a script
without deleting it (`-old-shop.tys`).

## Commands and permissions

| Command | Permission | Description |
|---------|------------|-------------|
| `/tys help` | `tachyonscript.admin` | Lists the commands you may use |
| `/tys reload [script]` | `tachyonscript.reload` | Recompiles changed scripts (or the given one) and activates them |
| `/tys scripts` | `tachyonscript.admin` | Lists scripts and their handlers |
| `/tys info <script>` | `tachyonscript.admin` | Events, functions and size of a script |
| `/tys errors` | `tachyonscript.admin` | Compile errors of the last load and runtime errors since then |
| `/tys profile start\|stop\|report` | `tachyonscript.profile` | Measures time spent per handler |
| `/tys dump <script> [ir\|code]` | `tachyonscript.debug` | Prints the compiled form to the console |
| `/tys version` | `tachyonscript.admin` | Versions, server type and loaded addons |

`tachyonscript.*` grants everything. All permissions default to operators.

## Reloading

`/tys reload` compiles on a background thread; the server keeps running the old
scripts meanwhile. Only files that changed are recompiled. When compilation
finishes:

* **lenient** mode (default): every script that compiled is activated; a script
  with errors keeps its previous working version (or stays inactive if it never
  worked). The errors are printed to the console and to whoever ran the command.
* **strict** mode: if any script fails, nothing changes.

The switch to the new scripts is atomic: an event is handled entirely by the old
or entirely by the new scripts.

## Configuration

```yaml
reload:
  mode: lenient                  # or strict
safety:
  recursion-limit: 128           # maximum nesting of script function calls
  max-execution-time-ms: 1000    # a single event execution is stopped after this
performance:
  slow-execution-warning-ms: 5   # log executions slower than this; 0 disables
runtime:
  backend: interpreter           # the bytecode backend is planned
debug:
  enabled: false                 # Java causes in error reports, log.debug output
```

Invalid values are reported and replaced by defaults. Changes need a restart.

## Errors

* Compile errors name the file, line and column, show the code and usually a
  suggestion. A script with errors never replaces a working version.
* Runtime errors (a list index out of range, a failed `as`, a runaway loop) stop
  that one handler, are logged with a TachyonScript stack trace, and are counted:
  the same error at the same place is logged once and then summarized, so a
  handler failing on every move event cannot flood the console. `/tys errors`
  shows the counts.
* Internal compiler errors (bugs in TachyonScript) print a short message and write
  details to `plugins/TachyonScript/logs/compiler-error-*.log`, without the script
  contents; please attach that file to bug reports.

## Folia

TachyonScript declares `folia-supported: true`. Handlers run on the region thread
that fired the event; changes to entities owned by other regions are scheduled on
their owners automatically. Nothing needs to be configured.
