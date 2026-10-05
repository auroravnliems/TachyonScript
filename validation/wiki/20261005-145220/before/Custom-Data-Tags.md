# Custom data tags

Tags are small pieces of hidden data that you attach to an **item**, an **entity**, a
**block**, a **chunk** or a **world**. Minecraft saves them together with the thing they belong
to: a tagged sword keeps its tag when it is dropped, put in a chest, traded or the server
restarts; a tagged zombie keeps it until it dies.

Use tags to recognise your own things — a custom weapon, a crate key, a boss, a placed block —
reliably. Never recognise items by their name: players can rename items in an anvil.

```tys
@playerOnly
@permission("crates.admin")
command cratekey(amount: int = 1) {
    let key = ItemStack(Material.TRIPWIRE_HOOK, amount, "<gold>Crate Key", ["<gray>Right-click the crate to open it"])
    key.glowing = true
    key.setTag("crate_key", "legendary")
    player.give(key)
}

event player.interact {
    if !event.rightClick || !event.mainHand || block?.type != Material.CHEST {
        return
    }
    let held = item
    if held == null || held.tag("crate_key") != "legendary" {
        return
    }
    event.cancel()
    held.amount -= 1
    player.mainHand = held.amount > 0 ? held : null
    player.give(Material.DIAMOND, random.int(1, 5))
    player.playSound(Sound.BLOCK_CHEST_OPEN)
    player.send("<gold>You opened a legendary crate!")
}
```

## Reading and writing tags

The same members exist on `ItemStack`, `Entity` (and so `Player`), `Block`, `Chunk` and
`World`:

| Member | Meaning |
|--------|---------|
| `setTag(key, "text")` | stores text |
| `setTag(key, 42)` | stores a whole number (items, entities, blocks) |
| `setTag(key, 2.5)` | stores a decimal number (items, entities, blocks) |
| `tag(key)` | the value as text (`string?`, `null` if there is none) |
| `intTag(key)` | the value as `int?` |
| `doubleTag(key)` | the value as `double?` |
| `hasTag(key)` | whether the tag exists |
| `removeTag(key)` | removes it |
| `tags` | the keys of all tags (items and entities) |
| `clearTags()` | removes every tag of a block |

`tag(key)` also returns numbers as text, and `doubleTag` also reads whole numbers, so reading
never fails because of the kind of value.

```tys
event entity.death {
    if killer == null {
        return
    }
    let weapon = killer.mainHand
    if weapon == null || weapon.tag("weapon") == null {
        return
    }
    let kills = (weapon.intTag("kills") ?? 0) + 1
    weapon.setTag("kills", kills)
    weapon.lore = ["<gray>Kills: <red>{kills}"]
    killer.mainHand = weapon
}
```

## Keys

A key is lower-case letters, digits and `. _ - /`. Keys are stored under the `tachyonscript`
namespace, so they never collide with other plugins. Upper-case letters are turned into
lower-case (`"placedBy"` is `placedby`).

To read (or write) the data of another plugin, give its full key with the namespace:

```tys-body
let hand = player.mainHand
let otherPluginId = hand?.tag("mmoitems:mmoitems_item_id")
if otherPluginId != null {
    player.send("This is the MMOItems item {otherPluginId}")
}
```

Text, whole numbers and decimal numbers can be read from other plugins' data; other kinds of
values read as `null`.

## Blocks

Minecraft has no storage for ordinary blocks, so TachyonScript keeps block tags in the block's
**chunk**, under the block's position. Any block can therefore have tags — stone, dirt, a door.
Because the tag belongs to the position, not to the block, remove it when the block goes away:

```tys
event block.place {
    if block.type == Material.CHEST {
        block.setTag("owner", player.uuid.toString())
    }
}

event block.break {
    let owner = block.tag("owner")
    if owner != null && owner != player.uuid.toString() && !player.hasPermission("chests.bypass") {
        event.cancel()
        player.send("<red>This chest belongs to someone else.")
        return
    }
    block.clearTags()
}

event player.interact {
    let clicked = block
    if clicked == null || clicked.type != Material.CHEST || !event.rightClick {
        return
    }
    let owner = clicked.tag("owner")
    if owner != null && owner != player.uuid.toString() && !player.hasPermission("chests.bypass") {
        event.cancel()
        player.send("<red>This chest is locked.")
    }
}
```

Blocks can also move without being broken (pistons, explosions, endermen); handle those events
too when it matters (`block.pistonExtend`, `entity.explode`, `entity.changeBlock`).

## Entities

```tys
event entity.spawn {
    if reason == SpawnReason.SPAWNER {
        entity.setTag("from_spawner", 1)
    }
}

event entity.death {
    if entity.hasTag("from_spawner") {
        event.droppedExp = 0                 // no experience farms
    }
}
```

A player's tags are saved with the player. For player data that scripts use a lot, a
[`playerdata var`](Saving-Data) is usually more convenient: it is typed, can hold lists, maps,
locations and items, and works for offline players.

### Scoreboard tags

Entities also have the tags of the vanilla `/tag` command, which command blocks and data packs
can see: `scoreboardTags`, `addScoreboardTag(tag)`, `hasScoreboardTag(tag)`,
`removeScoreboardTag(tag)`.

## Chunks and worlds

Chunks and worlds store text tags — a claim owner of a chunk, a setting of a world:

```tys
@playerOnly
command claim {
    let chunk = player.location.chunk
    let owner = chunk.tag("claim")
    if owner != null {
        player.send("<red>This chunk is already claimed.")
        return
    }
    chunk.setTag("claim", player.uuid.toString())
    player.send("<green>You claimed chunk {chunk.x}, {chunk.z}.")
}
```

## Tags, saved variables or a database?

| Data belongs to... | Use |
|--------------------|-----|
| one item, entity, block or chunk, and should move or disappear with it | a tag |
| a player or the server | [`playerdata var` / `persistent var`](Saving-Data) |
| many players, searchable | a [database](Databases) |

## Next

* [Items and inventories](Items-and-Inventories)
* [Entities](Entities)
* [Saving data](Saving-Data)
