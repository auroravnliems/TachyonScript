# Functions and lambdas

A function is a named piece of code that you can run from anywhere in the script — from
event handlers, commands, timers and other functions. Functions keep scripts short: write
the code once, call it wherever it is needed.

## Declaring a function

```tys
/// Heals a player completely and tells them.
function heal(target: Player) {
    target.health = target.maxHealth
    target.food = 20
    target.send("<green>You have been healed.")
}

@playerOnly
command heal {
    heal(player)
}

event player.respawn {
    heal(player)
}
```

* `function` is followed by the name and the **parameters** in parentheses. Every parameter
  has a name and a type: `target: Player`.
* The code between the braces is the **body**. It runs every time the function is called.
* `///` before a function documents it; tools show the comment when you use the function.

A function can be declared anywhere at the top level of the file, before or after the code
that calls it.

## Returning a value

A function that computes something declares the type of its result after the parameters and
gives the value back with `return`:

```tys
function hearts(target: LivingEntity): int {
    return math.round(target.health / 2.0)
}

function rank(level: int): string {
    if level >= 50 {
        return "<gold>Legend"
    }
    if level >= 20 {
        return "<aqua>Veteran"
    }
    return "<gray>Newcomer"
}

event player.join {
    player.send("You have {hearts(player)} hearts.")
    player.send(text.mini(rank(player.level)))
}
```

The compiler checks that **every path** of the function returns a value. Forgetting one is
an error, not a surprise at run time:

```text
ERROR scripts/price.tys:5:1 [TYS0221]

  5 | }
    | ^
  1 | function price(item: string): int {
    |          ^^^^^ declared here

Function 'price' must return a value of type int on every path.
```

`return` on its own (without a value) leaves a function that returns nothing, a handler or a
command early.

## Default values

Parameters can have a default value. Callers may leave out the trailing parameters that have
one:

```tys
function reward(target: Player, coins: int = 10, reason: string = "playing") {
    target.send("<gold>+{coins} coins for {reason}!")
}

event player.join {
    reward(player)                        // 10 coins for playing
    reward(player, 50)                    // 50 coins for playing
    reward(player, 100, "your first join")
}
```

## Parameter types and conversions

Arguments are checked like assignments:

* a `Player` can be passed where an `Entity` or a `LivingEntity` is expected (a player *is* a
  living entity), but not the other way round;
* an `int` can be passed where a `double` or a `long` is expected;
* a value of type `T` can be passed where `T?` is expected, but a `T?` (maybe `null`) cannot be
  passed where a `T` is expected until it has been checked.

```tys
function launch(target: Entity, power: double) {
    target.velocity = Vector(0, power, 0)
}

event player.join {
    launch(player, 1)        // Player -> Entity, int -> double
}
```

## Several functions with the same name

Functions may share a name when their parameter types differ. The compiler picks the one that
matches the arguments:

```tys
function describe(amount: int): string {
    return "{amount} coins"
}

function describe(item: ItemStack): string {
    return "{item.amount}x {item.type.prettyName}"
}

event player.join {
    player.send("You won {describe(25)} and {describe(ItemStack(Material.BREAD, 3))}.")
}
```

> Text returned by a function is only known while the script runs, so it is inserted into a
> message through a template (`"{describe(25)}"`) and shown as plain text. See
> [Messages and text](Messages-and-Text) for why, and for `text.mini(...)` when the text is
> trusted MiniMessage.

## Recursion

A function can call itself:

```tys
function factorial(n: int): long {
    if n <= 1 {
        return 1
    }
    return n * factorial(n - 1)
}

command fact(n: int) {
    sender.send("{n}! = {factorial(n)}")
}
```

Calls cannot nest more than `safety.recursion-limit` levels (128 by default, see
[Configuration](Configuration)). A function that calls itself forever is stopped with an error
that names the script line; the server keeps running.

## Lambdas

A **lambda** is a small function written right where a value is expected, without a name. It
is how you tell a list how to filter or sort, what a menu button does, or what to do when a
database answers:

```tys-body
let numbers = [5, 1, 8, 3]
let big = numbers.filter(n => n > 2)            // [5, 8, 3]
let doubled = numbers.map(n => n * 2)           // [10, 2, 16, 6]
let sorted = numbers.sortedBy(n => -n)          // [8, 5, 3, 1]
player.send("{big} {doubled} {sorted}")
```

The forms of a lambda:

| Form | Meaning |
|------|---------|
| `x => x * 2` | one parameter, the result of one expression |
| `(a, b) => a + b` | several parameters |
| `(a: int, b: int) => a + b` | parameters with explicit types |
| `() => player.send("Hi")` | no parameter |
| `x => { ... }` | a block body; use `return value` inside it to give a result |

When a lambda is passed where a function type is expected (for example `filter` expects a
`function(int): bool` for a `List<int>`), the parameter types are inferred. When you store a
lambda in a variable, write the types:

```tys-body
let add = (a: int, b: int) => a + b
let greet = () => {
    player.send("<green>Hello!")
}
let clamp = (value: int) => {
    if value < 0 {
        return 0
    }
    return math.min(value, 100)
}
greet()
player.send("{add(2, 3)} {clamp(250)}")
```

## Function types

Functions are values: they can be stored in variables, lists and maps, passed to other
functions and returned from them. Their type is written `function(parameter types): result`:

| Type | A function that... |
|------|--------------------|
| `function(int): int` | takes an `int`, returns an `int` |
| `function(Player): bool` | takes a `Player`, returns `true` or `false` |
| `function(Player): void` | takes a `Player`, returns nothing |
| `function(): string` | takes nothing, returns text |
| `function(int, string): void` | takes an `int` and a `string` |

```tys
function isStaff(p: Player): bool {
    return p.hasPermission("server.staff")
}

function twice(f: function(int): int, x: int): int {
    return f(f(x))
}

function adder(amount: int): function(int): int {
    return x => x + amount
}

event player.join {
    let staff = server.players.filter(isStaff)          // a named function as a value
    let add3 = adder(3)
    player.send("{staff.size} staff online, {twice(add3, 1)}")   // 7
}
```

A map of functions makes a simple command dispatcher:

```tys
let actions: Map<string, function(Player): void> = {
    "heal": p => {
        p.health = p.maxHealth
    },
    "feed": p => {
        p.food = 20
    },
    "day": p => {
        p.world.time = 1000
    }
}

@playerOnly
command quick(action: string) {
    let run = actions[action]
    if run == null {
        player.send("<red>Unknown action. Try: {actions.keys.join(", ")}")
        return
    }
    run(player)
    player.send("<green>Done: {action}")
}
```

`actions[action]` is `null` when the key is missing, so the function value may be `null` and
must be checked before it is called. An assignment (`p.food = 20`) is a statement, not a
value, so a lambda that assigns uses a block body `p => { ... }`.

## What a lambda can see

A lambda (and an `after`/`every` block, which is a lambda too) **copies the local variables it
uses when it is created**. That is why a lambda made in a loop sees the value of its own
iteration:

```tys-body
for i in 1..3 {
    after 1 second * i {
        player.send("Countdown {4 - i}")      // 3, 2, 1 — each block kept its own i
    }
}
```

Because the value is copied, a lambda may only use local variables that **never change** after
they are declared. Using one that changes later is a compile error, because the lambda would
silently keep an old value:

```text
ERROR scripts/capture.tys:3:5 [TYS0236]

  3 |     after 1 second {
    |     ^^^^^^^^^^^^^^^^
  2 |     var count = 0
    |         ^^^^^ declared here

This block uses 'count', which is changed after it is declared.

A lambda or scheduled block copies the variables it uses when it is created, so it can only use variables that never change. Copy the value first: let countNow = count
```

Script variables (declared at the top of the file) are different: they are shared, so a lambda
can read and change them at any time:

```tys
var clicks = 0

@playerOnly
command clicker {
    let menu = Menu(1, "<gold>Click me")
    menu.set(4, ItemStack(Material.EMERALD), click => {
        clicks += 1                                   // a script variable: allowed
        click.player.send("Total clicks: {clicks}")
    })
    menu.open(player)
}
```

## Lambdas and reloads

Functions that other code calls later — menu buttons, database callbacks, web requests,
`after` blocks — belong to the script that created them. When the script is reloaded or
removed, they are not called any more: a menu of the old version is closed, a database answer
that arrives after the reload is dropped. You never have two versions of a script reacting to
the same click.

## Next

* [Lists, maps and records](Lists-Maps-and-Records) — where lambdas are used the most
* [Modules](Modules) — calling functions of other script files
* [Timers and tasks](Timers-and-Tasks) — `after` and `every` blocks
