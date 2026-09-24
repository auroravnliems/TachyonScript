# Variables and constants

## Local variables

`let` declares a variable that cannot be reassigned, `var` one that can:

```tys-body
let name = player.name        // string
var visits = 1                // int
visits += 1
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
ERROR scripts/example.tys:3:5 [TYS0303]
Cannot assign to 'name': it was declared with 'let'.
```

Compound assignments: `+=`, `-=`, `*=`, `/=`, `%=`. They also work on properties
that can be written, such as `player.health -= 2.0` or `player.food += 1`.

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

Variables inside functions and handlers live only while that code runs. Values that
must survive between events or reloads need persistent data, which is planned
(see [`../status.md`](../status.md)).
