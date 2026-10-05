# Control flow

## if / else

```tys-body
if player.health < 5.0 {
    player.send("<red>You are almost dead!")
} else if player.health < 10.0 {
    player.send("<yellow>You are hurt.")
} else {
    player.send("<green>You are healthy.")
}
```

Conditions must be `bool` — `if player.food` is an error; write `if player.food > 0`.
For a value, use `? :`:

```tys-body
let state = player.flying ? "flying" : "walking"
```

## Loops

```tys-body
for other in server.players {
    other.send("{player.name} joined")
}

for i in 1..5 {            // 1 to 5
    player.send("{i}")
}

for i in 0..<5 {           // 0 to 4
    player.send("{i}")
}

let kills = {"Steve": 3, "Alex": 5}
for name, count in kills {
    player.send("{name}: {count}")
}

var tries = 0
while tries < 10 {
    tries++
    if tries % 2 == 0 {
        continue            // next round
    }
    if tries > 7 {
        break               // leave the loop
    }
}
```

Loops over lists and maps work on a snapshot, so changing the list inside the loop is
safe. A loop that runs too long (1 second by default) is stopped with an error pointing
at it; the server never freezes.

## switch

```tys-body
switch player.gameMode {
    case GameMode.SURVIVAL -> player.send("Stay alive!")
    case GameMode.CREATIVE, GameMode.SPECTATOR -> player.send("Build or watch.")
    default -> player.send("Have fun.")
}

let medal = switch player.level {
    case 0 -> "none"
    case 1, 2, 3 -> "bronze"
    default -> player.level >= 30 ? "gold" : "silver"
}
player.send("Medal: {medal}")
```

A case with several statements uses braces: `case 1 -> { ... }`.

## Leaving early

`return` leaves the current handler, command or function (with a value in functions
that return one). It is the usual way to stop when a check fails:

```tys
@playerOnly
command fly {
    if !player.hasPermission("server.fly") {
        player.send("<red>You cannot fly.")
        return
    }
    player.allowFlight = !player.allowFlight
    player.send("Flight: {player.allowFlight}")
}
```

## try / catch

Errors (division by zero, a list index out of range, a failed operation, `throw`) can be
caught; see [Error handling](Error-Handling):

```tys-body
try {
    let parts = "1,2".split(",")
    player.send("{parts[5]}")
} catch e {
    player.send("<red>Something went wrong: {e.message}")
}
```
