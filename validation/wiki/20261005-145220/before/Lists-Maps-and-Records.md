# Lists, maps and records

Three ways to keep several values together:

* a **list** is an ordered row of values of one type: `[3, 1, 2]`, the online players, the
  lines of a file;
* a **map** connects keys to values: prices by item name, homes by name, kills by player;
* a **record** is a small type of your own with named fields: a warp with a name, a location
  and a price.

All three can be kept in script variables and saved with `persistent var` and
`playerdata var` (see [Saving data](Saving-Data)).

## Lists

### Creating lists

```tys-body
let numbers = [3, 1, 4, 1, 5]               // List<int>
let names = ["Steve", "Alex"]               // List<string>
let empty: List<Location> = []              // an empty list needs its type
let grid: List<List<int>> = [[1, 2], [3]]   // lists of lists
player.send("{numbers.size} numbers, {names.size} names, {empty.size} locations, {grid}")
```

The element type is inferred from the values. `[1, 2.5]` is a `List<double>` (the `int` widens);
`[1, "a"]` is an error because the values have nothing in common.

### Reading and changing

```tys-body
let names = ["Steve", "Alex"]
names.add("Notch")                  // at the end
names.insert(0, "Herobrine")        // at an index
names[1] = "Jeb"                    // replace the element at index 1
let first = names[0]                // "Herobrine"
let removed = names.removeAt(0)     // removes and returns "Herobrine"
names.remove("Alex")                // removes the first "Alex"; false if there was none
player.send("{first} {removed} {names} size {names.size}")
```

Indexes start at `0`; the last element is `names[names.size - 1]`. Reading or writing outside
the list is a runtime error that names the index and the size:

```text
[TachyonScript] TachyonRuntimeError: List index 5 is out of bounds (size 2).
  at shop.tys:14 (event player.join)
          player.send("Winner: {names[5]}")
                                ^^^^^^^^
```

To read safely, check the size first or use `first()`, `last()` and `random()`, which return
`null` for an empty list:

```tys-body
let names: List<string> = []
let winner = names.random() ?? "nobody"
player.send("The winner is {winner}")
```

### Everything a list can do

| Member | Result | Meaning |
|--------|--------|---------|
| `size` | `int` | number of elements |
| `isEmpty`, `isNotEmpty` | `bool` | whether it has no / some elements |
| `list[i]`, `get(i)` | element | the element at index `i` |
| `add(x)` | — | adds at the end |
| `insert(i, x)` | — | adds at index `i` |
| `addAll(other)` | — | adds every element of another list |
| `remove(x)` | `bool` | removes the first element equal to `x` |
| `removeAt(i)` | element | removes and returns the element at `i` |
| `clear()` | — | removes everything |
| `contains(x)`, `x in list` | `bool` | whether `x` is in the list |
| `indexOf(x)`, `lastIndexOf(x)` | `int` | position of `x`, or `-1` |
| `first()`, `last()`, `random()` | element? | an element, or `null` if the list is empty |
| `copy()` | list | an independent copy |
| `filter(x => ...)` | list | the elements for which the condition is true |
| `map(x => ...)` | list | a new list made of the results |
| `forEach(x => ...)` | — | runs the function for each element |
| `any(...)`, `all(...)`, `none(...)` | `bool` | whether some / all / no elements match |
| `count(...)` | `int` | how many elements match |
| `find(...)` | element? | the first matching element, or `null` |
| `findIndex(...)` | `int` | the index of the first match, or `-1` |
| `sort()`, `sorted()` | — / list | natural order (numbers, text, durations, instants) |
| `sortBy(x => key)`, `sortedBy(...)` | — / list | order by a key |
| `sortByDescending(...)`, `sortedByDescending(...)` | — / list | order by a key, largest first |
| `sortWith((a, b) => ...)` | — | order with a comparison (negative, 0, positive) |
| `reverse()`, `reversed()` | — / list | reverse the order |
| `shuffle()` | — | random order |
| `distinct()` | list | without duplicates |
| `take(n)`, `drop(n)` | list | the first `n` elements / all but the first `n` |
| `subList(from, to)` | list | elements from `from` (included) to `to` (excluded) |
| `min()`, `max()` | element? | smallest / largest (numbers, text, durations, instants) |
| `minBy(x => key)`, `maxBy(...)` | element? | the element with the smallest / largest key |
| `sum()` | number | sum of a list of numbers |
| `average()` | `double` | average of a list of numbers |
| `join()`, `join(separator)` | `string` | the elements as text, separated by `", "` or the separator |
| `groupBy(x => key)` | `Map<K, List<T>>` | the elements grouped by a key |
| `associateBy(x => key)` | `Map<K, T>` | each element under its key |

The pairs `sort`/`sorted`, `sortBy`/`sortedBy` and `reverse`/`reversed` differ in one thing:
the first changes the list, the second returns a new list and leaves the original alone.

### Working with lists of players and objects

```tys
@playerOnly
command richest {
    let online = server.players
    let top = online.sortedByDescending(p => p.level).take(3)
    player.send("<gold>Top levels:")
    for p in top {
        player.send("<gray>- {p.name}: {p.level}")
    }
    let flying = online.count(p => p.flying)
    let average = online.map(p => p.level).average()
    let names = online.filter(p => p.hasPermission("server.vip")).map(p => p.name).join(", ")
    player.send("{flying} flying, average level {math.roundTo(average, 1)}, VIPs: {names}")
}
```

### Lists are shared, not copied

A list is an object: two variables can refer to the same list. Changing it through one
variable changes what the other sees. Use `copy()` when you need an independent list:

```tys-body
let original = [1, 2, 3]
let same = original
let separate = original.copy()
same.add(4)
player.send("{original} {separate}")        // [1, 2, 3, 4] [1, 2, 3]
```

### Loops over lists are safe

`for x in list` iterates over a **snapshot** taken when the loop starts, so the body may add
or remove elements without breaking the loop. Lists can also be shared by handlers running at
the same time on Folia: every operation is thread-safe.

## Maps

### Creating maps

```tys-body
let prices = {"diamond": 100, "gold": 20, "iron": 5}          // Map<string, int>
let names = {1: "one", 2: "two"}                                // Map<int, string>
let byMaterial = {Material.DIAMOND: 100, Material.EMERALD: 80}  // Map<Material, int>
let homes: Map<string, Location> = {}                           // empty: write the type
player.send("{prices} {names} {byMaterial} {homes.size}")
```

Keys can be text, numbers, `bool`, `UUID`, constants such as `Material`, and records. Maps
remember the order in which keys were added.

### Reading and changing

```tys-body
let prices = {"diamond": 100, "gold": 20}
prices["iron"] = 5                       // add or replace
prices["gold"] += 10                     // change a number in place
let emerald = prices["emerald"] ?? 0     // a missing key gives null
if "diamond" in prices {                 // 'in' looks at the keys
    player.send("Diamonds cost {prices["diamond"]}")
}
prices.remove("iron")
player.send("{prices.keys} {prices.values} {prices.size}")
```

`map[key]` has a nullable type (`int?` for a `Map<string, int>`) because the key may be missing;
use `??` for a default, or check it:

```tys-body
let homes: Map<string, Location> = {}
let home = homes["base"]
if home == null {
    player.send("<red>You have no home called base.")
    return
}
player.teleport(home)
```

`+=` and `-=` on a map of numbers treat a missing key as `0` and are **atomic**, so counting
with a map is safe even when many handlers do it at the same time:

```tys
var blocksBroken: Map<string, int> = {}

event block.break {
    blocksBroken[player.name] += 1
}

command stats {
    for name, count in blocksBroken {
        sender.send("{name}: {count} blocks")
    }
}
```

### Everything a map can do

| Member | Result | Meaning |
|--------|--------|---------|
| `size`, `isEmpty`, `isNotEmpty` | | how many entries |
| `keys`, `values` | list | the keys / values, in insertion order |
| `map[k]`, `get(k)` | value? | the value of a key, or `null` |
| `getOrDefault(k, fallback)` | value | the value, or `fallback` when the key is missing |
| `map[k] = v`, `put(k, v)`, `set(k, v)` | — | adds or replaces |
| `putIfAbsent(k, v)` | value? | adds only if the key is missing; returns the existing value |
| `remove(k)` | value? | removes a key; returns its value |
| `containsKey(k)`, `k in map` | `bool` | whether the key exists |
| `containsValue(v)` | `bool` | whether some key has this value |
| `clear()` | — | removes everything |
| `putAll(other)` | — | copies every entry of another map |
| `copy()` | map | an independent copy |
| `filter((k, v) => ...)` | map | the entries for which the condition is true |
| `forEach((k, v) => ...)` | — | runs the function for each entry |
| `keysSortedByValue()` | list | the keys, smallest value first |
| `keysSortedByValueDescending()` | list | the keys, largest value first (top lists) |

### Looping over maps

```tys-body
let kills = {"Steve": 12, "Alex": 30, "Notch": 7}
for name, count in kills {           // keys and values
    player.send("{name}: {count}")
}
for name in kills {                  // only the keys
    player.send("<gray>{name}")
}
let ranking = kills.keysSortedByValueDescending()
player.send("First place: {ranking[0]} with {kills[ranking[0]] ?? 0} kills")
```

Like lists, maps iterate over a snapshot and are safe to share between handlers.

## Records

A record is a type you declare yourself, with named fields. Use one when several values
belong together — a warp, a report, a reward — instead of keeping parallel lists or maps.

```tys
record Reward(item: Material, amount: int = 1, chance: double = 100.0)

let rewards = [
    Reward(Material.DIAMOND, 1, 5.0),
    Reward(Material.GOLD_INGOT, 4, 25.0),
    Reward(Material.BREAD, 8)
]

@playerOnly
command lootbox {
    for reward in rewards {
        if random.chance(reward.chance) {
            player.give(reward.item, reward.amount)
            player.send("<green>+{reward.amount} {reward.item.prettyName}")
        }
    }
}
```

* `record Name(field: Type, ...)` declares the type; fields can have default values.
* `Reward(Material.BREAD, 8)` creates one; trailing fields with defaults can be left out.
* `reward.item` reads a field.

### Records cannot change

The fields of a record are fixed when it is created. To "change" one, create a new record:

```text
ERROR scripts/points.tys:7:7 [TYS0220]

  7 |     p.x = 5
    |       ^

Cannot assign to field 'x': records cannot be changed.

Create a new record with the changed value instead.
```

This makes records safe to share and to use as map keys.

### Methods

A record can have functions of its own. Inside them, the fields are available by name:

```tys
record Warp(name: string, location: Location, cost: int = 0) {
    function describe(): string {
        return cost == 0 ? "{name} (free)" : "{name} ({cost} coins)"
    }

    function withCost(newCost: int): Warp {
        return Warp(name, location, newCost)
    }
}

persistent var warps: Map<string, Warp> = {}

@playerOnly
@permission("warps.admin")
command setwarp(name: string, cost: int = 0) {
    warps[name] = Warp(name, player.location, cost)
    player.send("<green>Saved {warps[name]?.describe()}")
}
```

### Equality and text

Two records are equal (`==`) when they are of the same type and all their fields are equal.
Inserted into text, a record shows its fields: `Point(x=4, y=2)`.

```tys
record Point(x: int, y: int)

event player.join {
    let a = Point(1, 2)
    let b = Point(1, 2)
    player.send("{a == b} {a}")          // true Point(x=1, y=2)
}
```

### Saving records

Records whose fields can be saved (numbers, text, locations, items, constants, lists and maps
of these, other records, ...) can be kept in `persistent var` and `playerdata var`. See
[Saving data](Saving-Data).

## Values of unknown type

Some values have the type `any?`: they may be anything, for example the result of
`json.parse`. Convert them with `as` / `as?` to use them:

```tys-body
let data = json.parse("\{\"scores\": [10, 20]}") as? Map<string, any?>
if data == null {
    return                                            // not a JSON object
}
let scores = data["scores"] as? List<any?> ?? []
player.send("{scores.size} scores")
```

`as? List<T>` and `as? Map<K, V>` check that the value is a list or a map, but not the type of
each element: use the `json.as...` functions to read elements safely (see
[Files, web and JSON](Files-Web-and-JSON)).

## Next

* [Functions and lambdas](Functions-and-Lambdas) — the functions you pass to `filter`, `map`,
  `sortBy`...
* [Saving data](Saving-Data) — keeping lists, maps and records across restarts
