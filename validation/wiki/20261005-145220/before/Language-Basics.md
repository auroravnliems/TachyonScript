# Language basics

TachyonScript reads like Kotlin, Swift or modern JavaScript. This page covers the
building blocks; each links to a page with the details.

## Comments

```tys
// a comment until the end of the line
/* a comment
   over several lines */
/// a documentation comment, shown by tools (before functions, handlers, constants)
const MAX = 10
```

## Variables

```tys-body
let name = player.name          // let: cannot be reassigned
var score = 0                   // var: can be reassigned
score += 10
score++
let ratio: double = 1           // with an explicit type
```

Variables declared at the top of a file live as long as the script is loaded; `const`
values are fixed when the script is compiled; `persistent var` and `playerdata var` are
saved (see [Saving data](Saving-Data)).

```tys
const PREFIX = "<gold>[Server]</gold> "     // compile-time constant
var online = 0                              // script variable
persistent var totalJoins: int = 0          // saved for the server
playerdata var kills: int = 0               // saved per player

event player.join {
    online++
    totalJoins++
    player.send("{PREFIX}Online: {online}, joins so far: {totalJoins}, your kills: {player.kills}")
}
```

## Types

Every value has a type, usually inferred:

| Type | Values |
|------|--------|
| `int`, `long` | whole numbers: `42`, `3_000_000_000L` |
| `double`, `float` | decimals: `1.5`, `1.5f` |
| `bool` | `true`, `false` |
| `string` | text: `"Hello {player.name}"` |
| `Component` | formatted chat text (MiniMessage) |
| `Duration` | `5 seconds`, `20 ticks`, `2 minutes`, `1 day` |
| `Instant` | a point in time: `time.now` |
| `List<T>` | `[1, 2, 3]` |
| `Map<K, V>` | `{"gold": 20}` |
| `T?` | a `T` or `null` |
| `Player`, `ItemStack`, `Location`, ... | server objects from the [standard library](API-Reference) |

Numbers widen automatically (`int` to `double`); narrowing needs `as`:
`(player.health / 2.0) as int`.

## Operators

| Kind | Operators |
|------|-----------|
| Arithmetic | `+ - * / %` |
| Comparison | `== != < <= > >=` |
| Logic | `&& || !` |
| Bits (whole numbers) | `& | ^ ~ << >> >>>` |
| Null | `?.` (safe member access), `??` (default) |
| Membership | `in`, `!in` (lists, maps, text, ranges) |
| Types | `is`, `!is`, `as`, `as?` |
| Choice | `condition ? a : b` |
| Assignment | `= += -= *= /= %= &= |= ^= <<= >>=`, `++`, `--` |

`+` joins text: `"Level " + player.level`.

## Text and templates

```tys-body
let line = "Hello {player.name}, you are level {player.level}"
let math2 = "2 + 2 = {2 + 2}"
let nested = "Your name backwards: {player.name.reversed()}"
let braces = "Literal \{braces\}"
```

Values inside `{...}` are inserted as plain text. Where a message is expected, the text
around them is MiniMessage — see [Messages and text](Messages-and-Text).

## Null safety

A type without `?` never holds `null`, so there is nothing to check. With `?` the
compiler requires a check before use:

```tys-body
let target = server.player("Notch")          // Player?
if target != null {
    target.send("Hi!")                        // target is a Player here
}
let name = target?.name ?? "nobody"
```

## Blocks and statements

Braces group statements; a line ends a statement (no semicolons needed). A long
expression can continue on the next line when the line ends with an operator, or when
the next line starts with `.`, `?.`, `&&`, `||`, `??`, `+`, `*`, `/`, `%`, `?` or `:`:

```tys-body
let total = player.level * 100
    + player.food * 10
let admins = server.players
    .filter(p => p.hasPermission("server.admin"))
    .map(p => p.name)
```

## Next

The repository's `docs/compiler/parser.md` lists the complete 0.2 declarations
and precedence table. `try`/`catch`/`finally`,
commands, modules, saved variables and tasks are implemented. `gui`, `eventtype`,
`script`, declaration-style `cooldown` and `wait` are rejected with alternatives;
`@cooldown` is a supported command annotation.

* [Control flow](Control-Flow) — `if`, loops, `switch`, `try`
* [Functions and lambdas](Functions-and-Lambdas)
* [Lists, maps and records](Lists-Maps-and-Records)
