# Control flow

## Conditions

```tys-body
if player.health < 5.0 {
    player.send("<red>You are almost dead!")
} else if player.health < 10.0 {
    player.send("<yellow>You are hurt.")
} else {
    player.send("<green>You are healthy.")
}
```

Conditions must be `bool`: `if player.food` is an error (write `player.food > 0`).
`&&` and `||` stop as soon as the result is known, `!` negates. `c ? a : b` chooses a
value:

```tys-body
let state = player.flying ? "flying" : "walking"
```

`in` tests membership in a list, a map (its keys), a text or a range:

```tys-body
if player.name in ["Steve", "Alex"] || player.level in 10..20 {
    player.send("Special!")
}
```

## Loops

```tys-body
for other in server.players {
    if other != player {
        other.send("{player.name} joined")
    }
}

for i in 1..3 {             // 1, 2, 3
    player.send("Countdown {i}")
}

for i in 0..<3 {            // 0, 1, 2
    log.info("slot {i}")
}

let prices = {"gold": 20, "iron": 5}
for name, price in prices { // keys and values of a map
    player.send("{name} costs {price}")
}

var tries = 0
while tries < 3 {
    tries += 1
    if tries == 2 {
        continue
    }
    if player.food >= 20 {
        break
    }
}
```

`a..b` includes `b`, `a..<b` does not. Iterating over `server.players` (or any list or
map) iterates over a snapshot: players joining or leaving during the loop do not
affect it.

Loops cannot hang the server: an execution that runs longer than
`safety.max-execution-time-ms` (1 second by default) is stopped with an error that
points at the loop, and the server keeps running.

## switch

`switch` compares a value with several cases; `default` handles everything else.
It is a statement or, when every branch gives a value, an expression:

```tys-body
let rank = switch player.level {
    case 0 -> "beginner"
    case 1, 2, 3 -> "novice"
    default -> player.level > 30 ? "master" : "regular"
}

switch player.gameMode {
    case GameMode.CREATIVE -> player.send("You can fly.")
    case GameMode.SURVIVAL -> {
        player.send("Good luck!")
        player.food = 20
    }
    default -> player.send("Have fun.")
}
```

## Errors: try, catch, finally, throw

A failing operation (dividing by zero, reading `names[5]` of a shorter list, a
failed native call, `throw`) stops the code with an error. `try` catches it:

```tys
function price(item: string): int {
    if item.isEmpty {
        throw "The item name is empty"
    }
    return 100
}

@playerOnly
command buy(item: string) {
    try {
        let cost = price(item)
        player.send("{item} costs {cost}")
    } catch e {
        player.send("<red>Cannot buy: {e.message}")
        log.warn("{e.kind} at {e.location}")
    } finally {
        player.send("<gray>Thanks for visiting.")
    }
}
```

The caught value has `message`, `kind` (`script`, `thrown`, `division`, `index`,
`null`, `cast` or `native`) and `location` (`shop.tys:12`). `catch { ... }` without a
name ignores the value; `throw e` passes a caught error on. `finally` runs however the
block is left, including by `return`, `break` and `continue`. Time limits and too deep
recursion cannot be caught: they always stop the script. See [Errors](errors.md).
