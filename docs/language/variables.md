# Variables and constants

TachyonScript has four kinds of variables, depending on how long a value must live:

| Declared with                | Lives                                              | Example                          |
|------------------------------|----------------------------------------------------|----------------------------------|
| `let` / `var` in a block     | while the block runs                               | `let name = player.name`         |
| `let` / `var` at the top     | while the script is loaded (reset by a reload)     | `var online = 0`                 |
| `persistent var`             | forever: saved, survives reloads and restarts      | `persistent var joins: int = 0`  |
| `playerdata var`             | forever, one value per player                      | `playerdata var coins: int = 100` |

`const` declares a value fixed when the script is compiled.

## Local variables

`let` declares a variable that cannot be reassigned, `var` one that can:

```tys-body
let name = player.name        // string
var visits = 1                // int
visits += 1
visits++
visits = visits * 2
```

The type is inferred from the initial value. Write it explicitly when the value
does not determine it, or to document intent:

```tys-body
let scores: List<int> = []
var best: Player? = null
let ratio: double = 1         // the literal 1 is a double here
```

Every variable must be initialized. A variable is visible from its declaration to
the end of the block (`{ ... }`) it is declared in; a name cannot be declared twice
in the same scope. Assigning to a `let`, or using an undeclared name, is a compile
error:

```text
ERROR scripts/example.tys:3:5 [TYS0220]

  3 |     name = "x"
    |     ^^^^
  2 |     let name = player.name
    |         ^^^^ declared here

Cannot assign to variable 'name'.

It is declared with 'let'. Use 'var' for a variable that can change.
```

Compound assignments: `+=`, `-=`, `*=`, `/=`, `%=`, and for whole numbers `&=`, `|=`,
`^=`, `<<=`, `>>=`; `x++` and `x--` add or subtract one. They also work on properties
that can be written, such as `player.health -= 2.0` or `player.food += 1`.

## Script variables

A `let` or `var` outside any function belongs to the script: every handler, command
and function of the file sees it, and it keeps its value between events.

```tys
var online = 0
let started = time.now

event player.join {
    online++
    broadcast("<gray>{online} players online")
}

event player.quit {
    online--
}

command uptime {
    sender.send("Up for {time.since(started)}")
}
```

Script variables are initialized in order when the script loads, and start again
from their initial value when the file is changed and reloaded. Updates such as
`online++` and `total += amount` are atomic, so handlers running at the same time on
Folia cannot lose an update.

## Saved variables

`persistent var` keeps its value across reloads **and** server restarts:

```tys
persistent var totalJoins: int = 0
persistent var firstPlayer: string? = null

event player.join {
    totalJoins += 1
    if firstPlayer == null {
        firstPlayer = player.name
    }
    player.send("You are visitor #{totalJoins}. The first was {firstPlayer}.")
}
```

`playerdata var` keeps one value per player, read and written as if it were a property
of the player:

```tys
playerdata var coins: int = 100
playerdata var homes: Map<string, Location> = {}

@playerOnly
command pay(target: Player, amount: int) {
    if amount <= 0 || player.coins < amount {
        player.send("<red>You cannot pay that.")
        return
    }
    player.coins -= amount
    target.coins += amount
    player.send("<green>Paid {amount} coins to {target.name}.")
}
```

Values of these types can be saved: numbers, `bool`, `string`, `Component`, `Duration`,
`Instant`, `UUID`, `Location`, `World`, `OfflinePlayer`, `ItemStack`, `Vector`, `Color`,
`PotionEffect`, constants such as `Material`, records made of these, and lists and maps
of them. Where and how often they are written is explained in [Saved data](storage.md).

## Constants

`const` declares a value computed when the script is compiled, visible in the whole
file:

```tys
const MAX_HOMES = 3
const PREFIX = "<gold>[Homes]</gold> "
const SPAWN_RADIUS = MAX_HOMES * 16.5
const COOLDOWN = 30 seconds

event player.join {
    player.send("{PREFIX}You can set {MAX_HOMES} homes.")
}
```

A constant can use literals, other constants, arithmetic, comparisons and string
concatenation — nothing that needs the server. Constants cost nothing at run time:
they are inlined where they are used.
