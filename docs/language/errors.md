# Errors

## Compile errors

Scripts are checked completely before they run: names, types, arguments, nullability,
unreachable code, missing returns, SQL and MiniMessage safety. Every problem is shown
with its place, an explanation and usually a suggestion:

```text
ERROR scripts/kit.tys:3:36 [TYS0207]

  3 |     player.give(ItemStack(Material.DIAMOND_SWROD))
    |                                    ^^^^^^^^^^^^^

Unknown Material 'DIAMOND_SWROD'.

Did you mean:
    DIAMOND_SWORD
```

A script with errors is not loaded; if an older version of it was running, that
version keeps running (`reload.mode: lenient`). With `reload.mode: strict`, any error
cancels the whole reload. `/tys errors` shows the problems of the last load again.

Warnings (an unused variable, a deprecated function) do not stop the script.

## Runtime errors

Some problems can only happen while a script runs: dividing by zero, reading past the
end of a list, a `null` where none was expected, a failed cast, a native operation
refused by the server (an unknown world, a menu slot that does not exist), or a
`throw`. The code that failed stops, and the error is logged with the script, line and
the event or command that ran it:

```text
[TachyonScript] TachyonRuntimeError: Slot 40 does not exist (the menu has slots 0 to 26).
  at shop.tys:18 (command buy)
      menu.set(40, ItemStack(Material.BREAD))
      ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
```

The same error is logged once per location and then counted, so a failing handler of
a frequent event cannot flood the log; `/tys errors` lists the counts. The other
handlers, and the rest of the server, keep running.

## Catching errors

```tys
@playerOnly
command pay(target: Player, amount: int) {
    try {
        if amount <= 0 {
            throw "The amount must be positive"
        }
        let share = 100 / amount
        player.send("Share: {share}")
    } catch e {
        player.send("<red>{e.message}")
    }
}
```

`e.message` is the message, `e.kind` what went wrong (`script`, `thrown`, `division`,
`index`, `null`, `cast`, `native`) and `e.location` where (`pay.tys:5`). A `finally`
block runs however the `try` block is left. It may contain a lambda or a scheduled block
such as `sync { ... }`: each exit creates a closure, while the function body is compiled
once. A scheduled cleanup still runs at the scheduled time, not immediately.

## Limits

An execution that runs too long (`safety.max-execution-time-ms`, 1 second by default)
or calls functions too deeply (`safety.recursion-limit`, 128) is stopped. These errors
cannot be caught, so a runaway script can never freeze the server.
