# Admin commands

Everything about scripts is managed with `/tys`. Every sub-command works from the console too.

| Command | Permission | Does |
|---------|------------|------|
| `/tys help` | `tachyonscript.admin` | lists the sub-commands you may use |
| `/tys reload [script]` | `tachyonscript.reload` | compiles changed scripts (or the given one) and activates them |
| `/tys scripts` | `tachyonscript.admin` | lists the scripts and what each declares |
| `/tys info <script>` | `tachyonscript.admin` | details of one script |
| `/tys errors` | `tachyonscript.admin` | compile errors of the last load and runtime errors since then |
| `/tys status` | `tachyonscript.admin` | storage, databases, script commands and placeholders |
| `/tys profile start\|stop\|report` | `tachyonscript.profile` | measures how long handlers, commands, tasks and placeholders take |
| `/tys dump <script> [ir\|code]` | `tachyonscript.debug` | prints the compiled form of a script to the console |
| `/tys version` | `tachyonscript.admin` | versions, server and addons |

`tachyonscript.*` grants all of them. Every permission defaults to operators. Tab completion
suggests sub-commands and script names.

Commands that scripts declare have their own permissions (`@permission("...")`); see
[Commands](Commands).

## /tys reload

```text
/tys reload
/tys reload shop
/tys reload minigames/spleef.tys
```

* Only files that changed since the last load are compiled again — plus the scripts that
  [import](Modules) a changed module. `/tys reload <script>` compiles that script even if it did
  not change.
* Compilation runs in the background; the server keeps running the old scripts meanwhile.
* When it is done, the new scripts replace the old ones **at once**: an event is handled either
  entirely by the old or entirely by the new scripts, never by a mix.
* A replaced script runs its `on unload` code; its timers stop, its menus close, its boss bars
  and sidebars disappear and its commands and placeholders are updated. Saved variables keep
  their values.
* Scripts that did not change keep running untouched, with their variables and timers.

The console (and the player who reloaded) sees a summary:

```text
[TachyonScript] Loaded 12 scripts, 31 event handlers (2 compiled, 10 unchanged) in 48.3 ms
```

When a script has errors, they are printed in full in the console (a short version in chat),
and in the default `lenient` mode the previous version of that script stays active:

```text
[TachyonScript] ERROR scripts/shop.tys:14:12 [TYS0201]
  ...
[TachyonScript] Loaded 12 scripts, 31 event handlers (2 compiled, 10 unchanged) in 45.9 ms; 1 script failed (1 errors)
[TachyonScript] Kept the previous working version of: shop.tys
```

Only one reload runs at a time; `/tys reload` during a reload answers `A reload is already
running.`

### Adding, disabling and removing scripts

* **Add**: put a `.tys` file into `plugins/TachyonScript/scripts/` (subfolders are fine) and run
  `/tys reload`.
* **Disable** without deleting: rename the file (or a folder) so its name starts with `-`, for
  example `-shop.tys` or `-old/`, then `/tys reload`. It is unloaded like a removed script.
* **Remove**: delete the file and `/tys reload`. Its saved variables stay in the database; if you
  add the script back later, they come back too.

## /tys scripts

```text
Scripts (generation 7, activated 14:02:31):
  chat.tys - 2 handlers, 2 commands
  eco/bank.tys - no handlers or commands
  homes.tys - 4 commands
  playtime.tys - 1 command, 1 task, 1 placeholder
  shop.tys - 4 handlers, 2 commands, 1 task, 1 placeholder, has errors (previous version active)
  welcome.tys - 1 handler
```

Each load creates a new *generation* of scripts; the number tells how many loads happened since
the server started. A script with "no handlers or commands" is usually a module that other
scripts import (`eco/bank.tys` above).

## /tys info

```text
/tys info shop
shop.tys
  Status: active
  Module: shop (imports eco.bank)
  Events: block.break, player.join (2), player.quit
  Commands: /shop, /shop admin
  Tasks: every 10m
  Placeholders: %tys_shop_sales%
  Saved variables: 1 persistent, 2 playerdata
  Functions: 9 (1284 code words)
  Source: 4212 characters, sha256 3f9a0c21b7de
```

*Module* is the name other scripts import this one by. *Commands*, *Tasks*, *Placeholders* and
*Saved variables* only appear when the script has some.

## /tys errors

```text
Last load: 1 errors, 2 warnings
ERROR scripts/shop.tys:14:12 [TYS0201] Unknown member 'sned' on Player.
WARNING scripts/shop.tys:3:9 [TYS0301] Variable 'x' is never used.
Runtime errors since the last reload:
  37x shop.tys:48 List index 5 is out of bounds (size 2).
  2x arena.tys:102 Division by zero.
```

Runtime errors are counted per place in a script, most frequent first. The full report of each
one (with the script line and the event or command) is in the console the first time it
happens. See [Error handling](Error-Handling).

## /tys status

```text
TachyonScript status
  Saved variables: SQLite plugins/TachyonScript/data.db, written every 30 s
  Databases: main, network
  Script commands: /daily, /heal, /shop, /warp
  Placeholders: %tys_kills%, %tys_kdr%
  PlaceholderAPI: connected
  Vault: installed
```

## /tys profile

Measures how long each piece of script takes while the server runs normally: event handlers,
commands, `every`/`at` tasks, `after` blocks, placeholders and callbacks (menu clicks, web and
database answers), one line each:

```text
/tys profile start
... wait a few minutes while players play ...
/tys profile report
Profile (301.2 s), slowest first:
  arena.tys event player.move - 184,220 calls, 1930.6 ms total, 10.48 µs avg, 2.1 ms max
  board.tys placeholder rank - 18,000 calls, 612.4 ms total, 34.02 µs avg, 890.10 µs max
  chat.tys event player.chat - 1,204 calls, 38.2 ms total, 31.74 µs avg, 402.13 µs max
  rewards.tys every 1m (rewards.tys:4) - 5 calls, 12.3 ms total, 2.46 ms avg, 3.1 ms max
/tys profile stop
```

Profiling costs a little time itself; stop it when you are done. `stop` also prints the report.

## /tys dump

Prints the compiled form of a script to the console — for curiosity, or for bug reports:

```text
/tys dump shop ir       the typed intermediate representation
/tys dump shop code     the interpreter's instructions
```

## /tys version

```text
TachyonScript 0.2.0
  Language level: 2
  IR format: 2
  Backend: interpreter
  Reload mode: lenient
  Server: Paper 1.21.11
  Java: 21.0.5
  Addons: none
```

Include it in bug reports.

## Checking scripts without a server

The `tys` command-line tool uses the same compiler and standard library as the plugin. It checks
scripts on your computer or in CI, without starting a server:

```sh
./gradlew :tachyon-cli:installDist
tachyon-cli/build/install/tys/bin/tys check plugins/TachyonScript/scripts
```

```text
ERROR plugins/TachyonScript/scripts/shop.tys:14:12 [TYS0201]
  ...
Checked 12 files: 1 error, 0 warnings (212.4 ms)
```

A folder is checked like the plugin's scripts folder, so scripts can import each other; a file
given on its own is checked alone (an `import` of another file then fails, and `tys` says so).
`tys check` exits with status 1 when a script has errors, so a CI pipeline can refuse broken
scripts before they reach the server. Other commands: `tys dump tokens|ast|bound|ir|code <file>`
(compiler stages), `tys docs` (the [API reference](API-Reference) as Markdown), `tys version`.

## Next

* [Configuration](Configuration)
* [Error handling](Error-Handling)
* [Performance and Folia](Performance-and-Folia)
