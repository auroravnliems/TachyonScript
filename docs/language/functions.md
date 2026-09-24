# Functions

```tys
/// Heals a player completely and tells them.
function heal(target: Player) {
    target.health = target.maxHealth
    target.food = 20
    target.send("<green>You have been healed.")
}

function hearts(target: LivingEntity): int {
    return math.round(target.health / 2.0)
}

event player.join {
    if player.hasPermission("server.vip") {
        heal(player)
    }
    player.send("You have {hearts(player)} hearts.")
}
```

* Parameters always have a type; the return type follows `:` and is omitted for
  functions that return nothing.
* Every path of a function with a return type must `return` a value; the compiler
  checks this.
* Functions can be called before their declaration and can call themselves.
  Recursion deeper than `safety.recursion-limit` (128 by default) is stopped with an
  error.
* Arguments are checked like assignments: a `Player` can be passed where an `Entity`
  is expected, an `int` where a `double` is expected, but not the other way round.
* `///` comments before a function (or event handler, or constant) document it.

Functions are private to their script file. Sharing functions between files through
modules and imports is planned.
