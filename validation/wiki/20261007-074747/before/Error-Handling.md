# Error handling

Mistakes in scripts show up in two ways:

* **compile errors** — found when the script loads, before any of it runs: an unknown name, a
  wrong type, a missing `null` check, a typo in a material. The script is not activated;
* **runtime errors** — things that can only go wrong while the script runs: dividing by zero,
  reading past the end of a list, a world that does not exist. The code that failed stops, the
  error is logged, and everything else keeps running.

## Compile errors

The plugin console uses a compact form with the location, diagnostic code, message and
source/caret on consecutive lines. The CLI retains the expanded examples below. Both
report the same compiler locations and diagnostic IDs; multiline reports no longer add
blank log records between every line on Paper.

Every problem the compiler finds is shown with its place, the code around it, an explanation and
usually a way to fix it:

```text
ERROR scripts/welcome.tys:2:12 [TYS0201]

  2 |     player.sned("<green>Welcome, {player.name}!")
    |            ^^^^

Unknown member 'sned' on Player.

Did you mean:
    send
```

* `ERROR` or `WARNING`, then the file, the **line** and the **column**;
* `[TYS0201]` — the error code, the same for every error of that kind (see the
  [table below](#error-codes));
* the line of code, with `^^^^` under the exact place;
* what is wrong, and what to do about it.

More examples of what the compiler catches:

```text
ERROR scripts/kit.tys:3:36 [TYS0207]

  3 |     player.give(ItemStack(Material.DIAMOND_SWROD))
    |                                    ^^^^^^^^^^^^^

Unknown Material 'DIAMOND_SWROD'.

Did you mean:
    DIAMOND_SWORD
```

```text
ERROR scripts/heal.tys:3:5 [TYS0216]

  3 |     target.health = 20.0
    |     ^^^^^^

'target' may be null (type Player?).

Check for null first:
    if target != null {
        ...
    }
or use '?.' to skip null values: target?.health
```

```text
ERROR scripts/assign.tys:4:22 [TYS0210]

  4 |     let level: int = player.health
    |                      ^^^^^^^^^^^^^

Type mismatch for variable 'level'.

Expected:
    int

Received:
    double
```

```text
ERROR scripts/assign.tys:3:5 [TYS0220]

  3 |     name = "x"
    |     ^^^^
  2 |     let name = player.name
    |         ^^^^ declared here

Cannot assign to variable 'name'.

It is declared with 'let'. Use 'var' for a variable that can change.
```

```text
ERROR scripts/event.tys:1:7 [TYS0203]

  1 | event player.jion {
    |       ^^^^^^^^^^^

Unknown event 'player.jion'.

Did you mean:
    player.join
    player.drop
    player.fish
```

### What happens to a script with errors

With the default `reload.mode: lenient`:

* a script with errors is **not** activated;
* if an older version of it was working, that version **keeps running** — a typo never takes a
  feature down;
* every other script is loaded normally.

The console shows every error in full; a player who ran `/tys reload` sees a short list;
`/tys errors` shows them again later. With `reload.mode: strict`, any error cancels the whole
reload and all previous scripts stay active.

### Warnings

Warnings point at code that is probably wrong but still works — an unused variable, code that
can never run, a `?.` on a value that is never `null`. They do not stop a script from loading.

```text
WARNING scripts/unused.tys:2:9 [TYS0301]

  2 |     let x = 5
    |         ^

Variable 'x' is never used.
```

### Checking before uploading

The `tys` command-line tool checks scripts on your computer with the same compiler, without a
server — see [Admin commands](Admin-Commands#checking-scripts-without-a-server).

## Runtime errors

When something fails while a script runs, that execution stops — the rest of the handler,
command or timer is skipped — and the error is logged with the place in the script and what was
running:

```text
[TachyonScript] TachyonRuntimeError: Slot 40 does not exist (the menu has slots 0 to 26).
[TachyonScript]   --> shop.tys:18:5 [script]
[TachyonScript]   at shop.tys:18 (function openShop)
[TachyonScript]   18 |     menu.set(40, ItemStack(Material.BREAD))
[TachyonScript]      |     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
[TachyonScript]   at shop.tys:31 (command /shop)
[TachyonScript]   Use /tys errors to review; /tys disable shop.tys to stop this script.
```

The lines after the first `at` show the chain of calls (innermost first). The other handlers of
the event, the other scripts and the server keep running. A player whose command failed sees the
`commands.messages.error` message (`An error occurred while running this command.`).

**The console is never flooded.** The first occurrence of an error at a place is logged in full;
repetitions are counted and summarized at most once a minute:

```text
[TachyonScript] TachyonRuntimeError at arena.tys:48:12 occurred 2317 more times: Division by zero.
```

`/tys errors` lists each place with its count. A targeted reload clears only the selected
scripts' error history; unrelated running scripts retain theirs. An error site includes the
line and column, so different failures on one line remain distinguishable.

A script can have an economic or permission bug without throwing an error. Use the
[emergency stop](Admin-Commands#tys-disable-and-tys-enable) while repairing it. A
[security decision](Security) is separate from both a compile error and a runtime error;
use the incident's inspect/approve/restore flow for a quarantined revision.

### Kinds of runtime errors

| `e.kind` | Example message | Typical cause |
|----------|-----------------|---------------|
| `division` | `Division by zero.` | dividing a whole number by `0` |
| `index` | `List index 5 is out of bounds (size 2).` | reading or writing past the end of a list |
| `null` | `Unexpected null value.` | a `null` where a value was required (rare: the compiler checks most of them) |
| `cast` | `Cannot cast this value to Mob.` | `value as Type` when the value is something else |
| `script` | `Slot 40 does not exist (the menu has slots 0 to 26).` | the server refused an operation: unknown world, invalid slot, bad amount, ... |
| `native` | | an operation of the server or an addon failed unexpectedly |
| `thrown` | your message | `throw "..."` in a script |
| `timeout` | `Script ran for more than 1000 ms and was stopped. Is a loop running forever?` | an endless or very long loop |
| `recursion` | `Too many nested function calls (limit 128). Is a function calling itself forever?` | a function calling itself without end |

`timeout` and `recursion` always stop the script and cannot be caught — a runaway script can
never freeze the server.

## Catching errors: try / catch / finally

```tys
@playerOnly
command divide(a: int, b: int) {
    try {
        player.send("{a} / {b} = {a / b}")
    } catch e {
        player.send("<red>That did not work: {e.message}")
    }
}
```

* `try { ... }` runs a block; if something in it fails, the rest of the block is skipped and
  `catch` runs.
* `catch e { ... }` receives the error: `e.message`, `e.kind` (see the table) and `e.location`
  (`shop.tys:12`).
* `catch { ... }` without a name ignores the details.
* `finally { ... }` runs **however** the `try` block is left — normally, by an error, or by
  `return`, `break` or `continue`. Use it to clean up.
* `throw e` inside `catch` passes the same error on.

```tys
function withdraw(target: Player, amount: int) {
    if amount <= 0 {
        throw "The amount must be positive, not {amount}."
    }
    if target.level < amount {
        throw "{target.name} only has {target.level} levels."
    }
    target.level -= amount
}

@playerOnly
command sacrifice(levels: int) {
    try {
        withdraw(player, levels)
        player.addPotionEffect(PotionEffectType.STRENGTH, 1 minute * levels, 2)
        player.send("<dark_red>The gods accept your offering.")
    } catch e {
        if e.kind == "thrown" {
            player.send("<red>{e.message}")
        } else {
            log.warn("sacrifice failed at {e.location}: {e.message}")
            throw e
        }
    } finally {
        player.playSound(Sound.BLOCK_BEACON_DEACTIVATE)
    }
}
```

`throw` accepts a message (`string`) or a caught error. A thrown error that nobody catches is
reported like any other runtime error, with the kind `thrown`.

`finally` may contain lambdas and scheduled blocks, including `sync { ... }` inside
`async { ... }`. Cleanup is emitted at each exit, but each lambda function is compiled
once. Scheduling cleanup queues it as usual; it does not make that work immediate.

## Avoiding errors in the first place

Most runtime errors can be prevented with a check that costs nothing:

| Instead of | Write |
|------------|-------|
| `list[5]` | `if list.size > 5 { ... }`, or `list.first()`, `list.last()`, `list.random()` (they give `null` for empty lists) |
| `a / b` | `if b != 0 { ... }` |
| `value as Mob` | `let mob = value as? Mob` and `if mob != null { ... }` |
| `"12x".toInt()` returning `null` | `text.toInt() ?? 0`, or check the result |
| `map[key]` being `null` | `map[key] ?? default` |
| `server.world("arena")` being `null` | `let world = server.world("arena")` then `if world == null { return }` |

Commands with typed parameters already validate what players type before your code runs — an
`int` parameter can never receive `"lots"`.

## Internal errors

If the compiler itself fails — a bug in TachyonScript, not in your script — the console shows a
short message and the details are written to `plugins/TachyonScript/logs/compiler-error-*.log`
(without the contents of your scripts). Please attach that file and `/tys version` to a bug
report.

## Error codes

| Code | Severity | Meaning |
|------|----------|---------|
| TYS0001 | error | a character that cannot appear in a script |
| TYS0002 | error | a string without its closing `"` |
| TYS0003 | error | an unknown escape such as `\q` in a string |
| TYS0004 | error | a `/* ... */` comment that is never closed |
| TYS0005 | error | a malformed number |
| TYS0006 | error | a number too large for its type |
| TYS0007 | error | an empty `{}` in a string |
| TYS0100 | error | something expected is missing (a `)`, a `}`, a name, ...) |
| TYS0101 | error | a block `{` that is never closed |
| TYS0102 | error | an expression was expected |
| TYS0103 | error | something that is not a declaration at the top level of the file |
| TYS0104 | error | two statements on one line without `;` |
| TYS0105 | error | chained comparisons such as `a < b < c` |
| TYS0106 | error | code nested too deeply |
| TYS0107 | error | assigning to something that cannot be assigned |
| TYS0108 | error | a declaration in the wrong place (a function inside a function, ...) |
| TYS0109 | error | a feature that is not supported |
| TYS0110 | error | an unexpected word or symbol |
| TYS0111 | error | a script file that is too large |
| TYS0200 | error | an unknown name |
| TYS0201 | error | an unknown member (property or function) of a type |
| TYS0202 | error | an unknown type |
| TYS0203 | error | an unknown event |
| TYS0204 | error | an unknown function |
| TYS0205 | error | a name declared twice |
| TYS0206 | error | an unknown module in `import` |
| TYS0207 | error | an unknown constant such as `Material.DIAMOND_SWROD` |
| TYS0210 | error | a value of the wrong type |
| TYS0211 | error | no version of a function accepts these arguments |
| TYS0212 | error | several versions of a function match equally well |
| TYS0213 | error | the wrong number of arguments |
| TYS0214 | error | calling something that is not a function |
| TYS0215 | error | an operator that does not work on these types |
| TYS0216 | error | using a value that may be `null` without checking it |
| TYS0217 | error | wrong type arguments, such as `List<int, int>` |
| TYS0218 | error | a name that is not a value (a type or a module used as a value) |
| TYS0220 | error | assigning to a `let`, a read-only property or a record field |
| TYS0221 | error | a function that does not return a value on every path |
| TYS0222 | error | `return value` in code that returns nothing |
| TYS0223 | error | `return` without a value in a function that returns one |
| TYS0224 | error | `break` or `continue` outside a loop |
| TYS0225 | error | cancelling an event that cannot be cancelled |
| TYS0226 | error | a `const` (or annotation, or default) that is not a constant |
| TYS0227 | error | constants that depend on each other in a circle |
| TYS0228 | error | a literal outside the range of its type |
| TYS0229 | error | dividing by the constant `0` |
| TYS0230 | error | a variable declared without a value |
| TYS0231 | error | a cast or type test that is not possible |
| TYS0232 | error | a `for` loop over something that is not a list, map or range |
| TYS0233 | error | using the result of something that returns nothing |
| TYS0234 | error | text known only at run time used as a MiniMessage message (see [Messages and text](Messages-and-Text)) |
| TYS0235 | error | SQL built while the script runs (see [Databases](Databases)) |
| TYS0236 | error | a lambda or scheduled block using a local variable that changes |
| TYS0237 | error | modules importing each other in a circle |
| TYS0238 | error | an unknown or invalid annotation |
| TYS0239 | error | a saved variable of a type that cannot be saved |
| TYS0240 | error | an invalid command declaration (name, parameter type, order of parameters) |
| TYS0241 | error | a `switch` used as a value without a `default` |
| TYS0242 | error | `return`, `break` or `continue` leaving a `finally` block |
| TYS0243 | error | a variable used before it is declared |
| TYS0244 | error | a type of the standard library is missing (a broken installation) |
| TYS0300 | warning | code that can never run |
| TYS0301 | warning | a variable that is never used |
| TYS0302 | warning | a deprecated function |
| TYS0303 | warning | a variable hiding another one with the same name |
| TYS0304 | warning | a condition that is always true or always false |
| TYS0305 | warning | `?.` on a value that is never `null` |
| TYS0306 | warning | a `null` check on a value that is never `null` |
| TYS0307 | hint | a value inserted into text contains tags that will be shown literally |
| TYS0308 | warning | an expression whose result is not used |
| TYS0309 | warning | changing an event in an `after` block, when the event is over |
| TYS0310 | warning | the same `case` twice in a `switch` |
| TYS0399 | info | too many problems; the rest are not shown |
| TYS0900 | error | an internal error of the compiler (please report it) |

## Next

* [Control flow](Control-Flow)
* [Admin commands](Admin-Commands) — `/tys errors`
* [FAQ](FAQ)
