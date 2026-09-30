# Functions and lambdas

```tys
/// Heals a player completely and tells them.
function heal(target: Player, message: string = "You have been healed.") {
    target.health = target.maxHealth
    target.food = 20
    target.send("<green>{message}")
}

function hearts(target: LivingEntity): int {
    return math.round(target.health / 2.0)
}

event player.join {
    if player.hasPermission("server.vip") {
        heal(player)
        heal(player, "VIP bonus: full health!")
    }
    player.send("You have {hearts(player)} hearts.")
}
```

* Parameters always have a type; the return type follows `:` and is omitted for
  functions that return nothing.
* Parameters can have default values (`= ...`); callers may leave out the trailing
  ones.
* Every path of a function with a return type must `return` a value; the compiler
  checks this.
* Functions can be called before their declaration and can call themselves.
  Recursion deeper than `safety.recursion-limit` (128 by default) is stopped with an
  error.
* Arguments are checked like assignments: a `Player` can be passed where an `Entity`
  is expected, an `int` where a `double` is expected, but not the other way round.
* `///` comments before a function (or event handler, or constant) document it.

Other scripts can call a file's functions by [importing it](modules.md).

## Lambdas and function values

A lambda is a function written where a value is expected:

```tys-body
let numbers = [5, 1, 8, 3]
let big = numbers.filter(n => n > 2)                 // one parameter
let sorted = numbers.sortedBy(n => -n)
let add = (a: int, b: int) => a + b                  // several, with types
let greet = () => {                                  // a block body
    player.send("Hello!")
}
greet()
player.send("{big} {sorted} {add(2, 3)}")
```

Parameter types are inferred when the lambda is passed where a function type is
expected (`filter` expects `function(int): bool` here); write them otherwise.

Named functions are values too, and function types can be written for parameters,
variables and results:

```tys
function twice(f: function(int): int, x: int): int {
    return f(f(x))
}

function square(x: int): int {
    return x * x
}

function adder(amount: int): function(int): int {
    return x => x + amount
}

event player.join {
    let add3 = adder(3)
    player.send("{twice(add3, 1)} {twice(square, 2)}")    // 7 16
}
```

A lambda captures the *values* of the local variables it uses when it is created,
so a lambda made in a loop sees the value of that iteration. It can read script
variables and change them. Menus, database queries and web requests take lambdas to
call later; they only run while their script is loaded.
