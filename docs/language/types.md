# Types, lists and null safety

Every value has a type known when the script is compiled. The full rules are in
[`../compiler/type-system.md`](../compiler/type-system.md); this page is the
overview.

## Built-in types

| Type        | Example                         | Notes                                  |
|-------------|---------------------------------|----------------------------------------|
| `int`       | `42`, `-7`, `1_000`             | 32-bit whole numbers                   |
| `long`      | `42L`, `3000000000`             | 64-bit whole numbers                   |
| `double`    | `1.5`, `2e3`                    | decimal numbers                        |
| `float`     | `1.5f`                          | rarely needed; used by `yaw`/`pitch`   |
| `bool`      | `true`, `false`                 |                                        |
| `string`    | `"Hello {player.name}"`         | text                                   |
| `Component` | `"<red>Hi"` where a message is expected | formatted chat text           |
| `Duration`  | `5 seconds`, `20 ticks`, `2 minutes` | a length of time                  |
| `List<T>`   | `[1, 2, 3]`                     | a list of values of type `T`           |
| `T?`        | `null`                          | a `T` or nothing                       |

Minecraft types such as `Player`, `Entity`, `World`, `Location` and `Block` come from
the standard library; their members are listed in the [reference](reference.md).
A `Player` is also a `LivingEntity`, an `Entity` and a `CommandSender`, so it has all
their members.

## Numbers

Mixing number types widens to the larger one: `int + double` is a `double`. There is
no automatic narrowing; convert explicitly:

```tys-body
let half = player.health / 2.0           // double
let hearts = math.round(half)            // int
let level = math.max(player.level, 5)    // int
```

Whole-number division truncates (`7 / 2` is `3`). Dividing a whole number by zero is
a runtime error (and a compile error when the divisor is the literal `0`).

## Text

Strings are written in double quotes and can contain values in braces:

```tys-body
let greeting = "Hello {player.name}, you have {player.food} food"
let joined = "a" + "b" + 3               // "ab3"
```

Where a message is expected (`player.send(...)`, `broadcast(...)`), a string is
[MiniMessage](messages.md) and values are inserted safely as plain text.
Escapes: `\n`, `\t`, `\"`, `\\`, `\{` for a literal brace, `é`.

## Lists

```tys-body
let names: List<string> = []
names.add("Steve")
names.add("Alex")
if names.contains("Steve") && !names.isEmpty {
    player.send("{names.size} names, first: {names[0]}")
}
names[1] = "Notch"
```

Reading or writing outside the list (`names[5]` when it has 2 elements) is a runtime
error that names the index and the size.

## Null safety

A value of type `T` is never `null`. Only `T?` can be, and the compiler makes you
handle that case before using the value:

```tys
event player.death {
    // killer: Player?
    if killer != null {
        killer.send("You killed {victim.name}.")    // here killer is a Player
    }
    let name = killer?.name ?? "the environment"   // ?. and ??
    victim.send("You were killed by {name}.")
}
```

* `x?.member` is `null` when `x` is `null`;
* `a ?? b` is `b` when `a` is `null`;
* after `if x != null`, `if x == null { return }`, or `x != null && ...`, the
  compiler knows `x` is not null.

## Type tests

```tys
event entity.damage {
    if entity is Player && cause == "fall" {
        entity.send("<gray>Ouch.")                   // entity is a Player here
    }
    let player = entity as? Player                   // Player? (null if not a player)
}
```

`as` (without `?`) fails with a runtime error when the value is not of that type.
