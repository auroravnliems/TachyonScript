# Types, lists, maps and null safety

Every value has a type known when the script is compiled. The full rules are in
[`../compiler/type-system.md`](../compiler/type-system.md); this page is the
overview.

## Built-in types

| Type             | Example                              | Notes                                   |
|------------------|--------------------------------------|-----------------------------------------|
| `int`            | `42`, `-7`, `1_000`, `0xFF`          | 32-bit whole numbers                    |
| `long`           | `42L`, `3000000000`                  | 64-bit whole numbers                    |
| `double`         | `1.5`, `2e3`                         | decimal numbers                         |
| `float`          | `1.5f`                               | rarely needed; used by `yaw`/`pitch`    |
| `bool`           | `true`, `false`                      |                                         |
| `string`         | `"Hello {player.name}"`              | text                                    |
| `Component`      | `"<red>Hi"` where a message is expected | formatted chat text                  |
| `Duration`       | `5 seconds`, `20 ticks`, `2 minutes` | a length of time                        |
| `Instant`        | `time.now`                           | a point in time (milliseconds)          |
| `List<T>`        | `[1, 2, 3]`                          | a list of values of type `T`            |
| `Map<K, V>`      | `{"gold": 20, "iron": 5}`            | keys to values, in insertion order      |
| `function(A): R` | `n => n * 2`                         | a function value                        |
| `T?`             | `null`                               | a `T` or nothing                        |
| `any`            |                                      | any value (see casts below)             |

Minecraft types such as `Player`, `Entity`, `World`, `Location`, `Block`, `ItemStack`,
`Inventory` and `Menu` come from the standard library; their members are listed in the
[reference](reference.md). A `Player` is also a `LivingEntity`, an `Entity`, a
`CommandSender` and an `OfflinePlayer`, so it has all their members.

## Numbers

Mixing number types widens to the larger one: `int + double` is a `double`. There is
no automatic narrowing; convert explicitly:

```tys-body
let half = player.health / 2.0           // double
let hearts = math.round(half)            // int
let level = math.max(player.level, 5)    // int
let exact = half as int                  // explicit conversion (truncates)
```

Whole-number division truncates (`7 / 2` is `3`). Dividing a whole number by zero is
a runtime error (and a compile error when the divisor is the literal `0`). Whole
numbers also have bitwise operators: `&`, `|`, `^`, `~`, `<<`, `>>`, `>>>`.

## Time

`Duration` is a length of time and `Instant` a point in time. Durations are written
with a unit (`milliseconds`, `ticks`, `seconds`, `minutes`, `hours`, `days`, singular or plural)
and can be added, compared and multiplied:

```tys-body
let cooldown = 1 minute + 30 seconds
let joined = time.now
if time.since(joined) < cooldown {
    player.send("Wait {cooldown}")
}
let tomorrow = time.nextDaily(0, 0)
player.send("Next reset in {format.duration(time.until(tomorrow))}")
```

## Text

Strings are written in double quotes and can contain values in braces:

```tys-body
let greeting = "Hello {player.name}, you have {player.food} food"
let joined = "a" + "b" + 3               // "ab3"
let upper = "{player.name.upper()} ({"x,y".split(",").size} parts)"
```

Where a message is expected (`player.send(...)`, `broadcast(...)`), a string is
[MiniMessage](messages.md) and values are inserted safely as plain text.
Escapes: `\n`, `\t`, `\r`, `\0`, `\"`, `\'`, `\\`, `\{` and `\}` for literal braces, and
`\u00e9` for a character by its code (`é`).

## Lists

```tys-body
let names: List<string> = []
names.add("Steve")
names.add("Alex")
if names.contains("Steve") && !names.isEmpty {
    player.send("{names.size} names, first: {names[0]}")
}
names[1] = "Notch"
let long = names.filter(n => n.length > 4).map(n => n.upper())
let sorted = names.sortedBy(n => n.length)
let total = [3, 4, 5].sum()
```

Lists have about fifty operations (`filter`, `map`, `sortedBy`, `groupBy`, `random`,
`shuffle`, `sum`, `average`, `maxBy`, `join`, ...); see the [reference](reference.md).
Reading or writing outside the list (`names[5]` when it has 2 elements) is a runtime
error that names the index and the size.

## Maps

```tys-body
let prices = {"diamond": 100, "gold": 20}
prices["iron"] = 5
prices["gold"] += 10
for item, price in prices {
    player.send("{item}: {price}")
}
let emerald = prices["emerald"] ?? 0         // missing keys give null
if "diamond" in prices {
    player.send("{prices.size} prices, cheapest first: {prices.keysSortedByValue()}")
}
```

Lists and maps can be shared by handlers running at the same time on Folia: their
operations are thread-safe, and loops iterate over a snapshot.

## Records

A record is a small data type with named fields, compared by value:

```tys
record Warp(name: string, location: Location, cost: int = 0) {
    function describe(): string {
        return "{name} ({cost} coins)"
    }
}

persistent var warps: Map<string, Warp> = {}

@playerOnly
command warp.set(name: string, cost: int = 0) {
    warps[name] = Warp(name, player.location, cost)
    player.send("Saved {warps[name]?.describe()}")
}
```

Fields are read with `warp.name`; missing trailing arguments take their defaults.
Records can be saved in `persistent` and `playerdata` variables when their fields can.

## Function types

Functions are values: they can be stored, passed and returned.

```tys-body
let double = (n: int) => n * 2
let isAdmin: function(Player): bool = p => p.hasPermission("server.admin")
let admins = server.players.filter(isAdmin)
player.send("{double(21)} {admins.size}")
```

A lambda captures the values of the variables it uses when it is created.

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

## Type tests and casts

```tys
event entity.damage {
    if entity is Player && cause == "fall" {
        entity.send("<gray>Ouch.")                   // entity is a Player here
    }
    let player = entity as? Player                   // Player? (null if not a player)
}
```

`as` (without `?`) fails with a runtime error when the value is not of that type.
Values of unknown type (`any`, for example from `json.parse`) can be cast to lists and
maps; the elements are not checked:

```tys-body
let data = json.parse("\{\"scores\": [1, 2]}") as Map<string, any?>
let scores = data["scores"] as? List<any?> ?? []
player.send("{scores.size} scores")
```

## Constants of Minecraft types

Materials, entity types, sounds, particles, potion effects, enchantments, biomes,
attributes and a few other kinds of values are written as constants of their type:

```tys-body
player.give(ItemStack(Material.DIAMOND_SWORD))
player.playSound(Sound.ENTITY_PLAYER_LEVELUP)
player.addPotionEffect(PotionEffectType.SPEED, 30 seconds, 2)
```

Constant names are checked when the script is compiled (with suggestions for typos),
and resolved once when it is loaded, so using them costs nothing at run time. On a
server the list of names comes from the server's own registries, so blocks, items
and sounds of newer versions or data packs work too.
