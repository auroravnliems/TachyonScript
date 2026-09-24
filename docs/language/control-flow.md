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
`&&` and `||` stop as soon as the result is known, `!` negates.

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

`a..b` includes `b`, `a..<b` does not. Iterating over `server.players` iterates over a
snapshot: players joining or leaving during the loop do not affect it.

Loops cannot hang the server: an execution that runs longer than
`safety.max-execution-time-ms` (1 second by default) is stopped with an error that
points at the loop, and the server keeps running.
