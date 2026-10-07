# Items and inventories

## Creating items

```tys-body
let bread = ItemStack(Material.BREAD, 16)
let sword = ItemStack(Material.DIAMOND_SWORD, 1, "<gold><bold>Excalibur")
let key = ItemStack(Material.TRIPWIRE_HOOK, 1, "<yellow>Crate key", ["<gray>Right-click a crate", "<gray>to open it"])
player.give(bread)
player.give(sword)
player.give(key)
player.give(Material.COOKED_BEEF, 8)
```

| Constructor | Creates |
|-------------|---------|
| `ItemStack(material)` | one item |
| `ItemStack(material, amount)` | several (1 to 99) |
| `ItemStack(material, amount, name)` | with a custom name (MiniMessage) |
| `ItemStack(material, amount, name, lore)` | with a name and lore lines |

Names and lore are [messages](Messages-and-Text): MiniMessage with `{values}`. Unlike vanilla,
custom names are not italic unless you ask for `<italic>`.

A material that is not an item (`Material.WATER`, `Material.AIR`) or an amount outside 1–99 is
a runtime error. `player.give` splits large amounts into full stacks; what does not fit into
the inventory drops at the player's feet.

## Everything an item has

| Member | Meaning |
|--------|---------|
| `type` | the material (changeable) |
| `amount`, `maxStackSize` | how many; the most a stack of it can hold |
| `name` | the custom name, or `null` (changeable; `null` removes it) |
| `displayName` | what the player sees: the custom name or the translated material name |
| `lore`, `addLore(line)` | the lines below the name |
| `enchant(enchantment, level)`, `removeEnchant(e)`, `enchantLevel(e)`, `hasEnchant(e)`, `enchantments` | enchantments (any level up to 255; books store them) |
| `glowing` | the enchantment shine without enchantments |
| `unbreakable`, `damage`, `maxDamage` | durability |
| `addFlags(flag)`, `removeFlags(flag)`, `hasFlag(flag)`, `hideTooltip` | what the tooltip hides |
| `customModelData`, `itemModel` | resource pack models (`itemModel = "myserver:ruby"`) |
| `color` | the color of leather armor, potions, ... (`null` if it cannot be dyed) |
| `skullOwner` | whose head a player head shows |
| `tag(key)`, `setTag(key, value)`, `hasTag(key)`, `removeTag(key)`, `tags` | hidden data, see [Custom data tags](Custom-Data-Tags) |
| `isEmpty`, `isSimilar(other)` | air or zero items; same item ignoring the amount |
| `clone()`, `withAmount(n)` | independent copies |

```tys
@playerOnly
@permission("server.items")
command legendary {
    let sword = ItemStack(Material.NETHERITE_SWORD, 1, "<gradient:#ff6a00:#ee0979><bold>Dragon Slayer")
    sword.lore = ["<gray>Forged in dragon fire.", "", "<gold>Legendary"]
    sword.enchant(Enchantment.SHARPNESS, 10)
    sword.enchant(Enchantment.FIRE_ASPECT, 3)
    sword.unbreakable = true
    sword.addFlags(ItemFlag.HIDE_ENCHANTS)
    sword.addFlags(ItemFlag.HIDE_UNBREAKABLE)
    sword.setTag("weapon", "dragon_slayer")
    player.give(sword)
}

@playerOnly
command skull(owner: OfflinePlayer) {
    let head = ItemStack(Material.PLAYER_HEAD)
    head.skullOwner = owner
    head.name = "<yellow>{owner.name ?? "?"}'s head"
    player.give(head)
}
```

Item flags: `HIDE_ENCHANTS`, `HIDE_ATTRIBUTES`, `HIDE_UNBREAKABLE`, `HIDE_DESTROYS`,
`HIDE_PLACED_ON`, `HIDE_ADDITIONAL_TOOLTIP`, `HIDE_DYE`, `HIDE_ARMOR_TRIM`,
`HIDE_STORED_ENCHANTS`.

## Recognising items

Compare materials with `==`; recognise *your* items by a [tag](Custom-Data-Tags), never by their
name (players can rename items in an anvil):

```tys
event player.interact {
    if !event.rightClick || !event.mainHand {
        return
    }
    let held = item
    if held == null || held.tag("weapon") != "dragon_slayer" {
        return
    }
    player.world.strikeLightningEffect(player.targetBlock(30)?.location ?? player.location)
    player.setCooldown(held.type, 3 seconds)
}
```

`item1.isSimilar(item2)` compares two stacks completely (material, name, lore, enchantments,
tags...) except the amount.

## Items in hands and slots

`player.mainHand`, `player.offHand`, the armor slots and `inventory.get(slot)` return the item
**in that slot**, or `null` when the slot is empty:

```tys
@playerOnly
command repair {
    let hand = player.mainHand
    if hand == null || hand.maxDamage == 0 {
        player.send("<red>Hold something that can be repaired.")
        return
    }
    hand.damage = 0
    player.mainHand = hand
    player.send("<green>Repaired!")
}
```

To be sure a change reaches the player, **assign the item back** (`player.mainHand = hand`) as
above. Use `clone()` when you want a separate copy that you can change without touching the slot.
Assigning `null` empties a slot.

## Inventories

A player's inventory, a chest, an ender chest, a menu — all are an `Inventory`:

| Member | Meaning |
|--------|---------|
| `size`, `type` | number of slots; `InventoryType.CHEST`, `HOPPER`, `PLAYER`, ... |
| `get(slot)`, `set(slot, item)` | read / write a slot (`null` = empty) |
| `add(item)` | adds where it fits; returns how many did **not** fit |
| `hasSpace(item)` | whether all of it would fit |
| `contains(material)`, `contains(material, amount)`, `containsAtLeast(item, amount)` | checks |
| `count(material)` | how many items of a material |
| `remove(material, amount)` | removes up to an amount; returns how many were removed |
| `removeItem(item)` | removes stacks similar to the item, up to its amount |
| `clear()`, `clear(slot)` | empties everything / one slot |
| `contents` | every slot as a list (`null` for empty slots), changeable |
| `firstEmpty`, `isEmpty` | the first free slot (`-1` if full); whether all slots are empty |
| `viewers` | the players looking at it |
| `location` | where its block or entity is |

A player's inventory (`player.inventory`) also has `heldSlot` (0–8), the selected hotbar slot.
Its slots are numbered like Minecraft's: 0–8 hotbar, 9–35 the main inventory, 36–39 armor,
40 the off hand.

### Buying and selling

```tys
@playerOnly
command sell(material: Material, amount: int = 1) {
    let prices = {Material.WHEAT: 2, Material.CARROT: 3, Material.DIAMOND: 100}
    let price = prices[material]
    if price == null {
        player.send("<red>{material.prettyName} cannot be sold.")
        return
    }
    if player.inventory.count(material) < amount {
        player.send("<red>You do not have {amount} {material.prettyName}.")
        return
    }
    player.inventory.remove(material, amount)
    player.giveExp(price * amount)
    player.send("<green>Sold {amount} {material.prettyName} for {price * amount} experience.")
}
```

### Chests and other blocks

`block.inventory` is the inventory of a container block — chest, barrel, furnace, hopper,
shulker box, ... — or `null` for other blocks:

```tys
@playerOnly
command fillchest {
    let target = player.targetBlock(5)
    let chest = target?.inventory
    if chest == null {
        player.send("<red>Look at a container.")
        return
    }
    chest.add(ItemStack(Material.BREAD, 32))
    chest.add(ItemStack(Material.TORCH, 64))
    player.send("<green>Filled the {target?.type?.prettyName ?? "container"}.")
}
```

### Inventories of your own

`Inventory(rows, title)` creates a chest-like inventory that belongs to no block — a backpack, a
trash can, a "view inventory" screen — and `player.openInventory(...)` shows it:

```tys
@playerOnly
command trash {
    player.openInventory(Inventory(4, "<red>Trash — close to delete"))
}

@playerOnly
@permission("server.invsee")
command invsee(target: Player) {
    player.openInventory(target.inventory)
}

@playerOnly
command enderchest {
    player.openEnderChest()
}

@playerOnly
command craft {
    player.openWorkbench()
}
```

For inventories with buttons — shops, menus, selectors — use a [Menu](Menus) instead: it cancels
clicks and runs a function per slot.

### Inventory events

| Event | When |
|-------|------|
| `player.inventoryClick` | a player clicks a slot; not delivered anywhere in a Menu view |
| `player.inventoryDrag` | a player drags items over slots; not delivered anywhere in a Menu view |
| `player.inventoryOpen`, `player.inventoryClose` | a player opens / closes an inventory |
| `player.craft`, `player.enchant` | crafting and enchanting results |
| `furnace.smelt`, `furnace.burn`, `inventory.moveItem` | furnaces and hoppers |
| `player.drop`, `entity.pickup` | dropping and picking up items |

For `Menu(...)`, this exclusion includes the bottom inventory and outside clicks,
at every priority and with either `allowTaking` value. Use [MenuClick](Menus#the-click)
for menu buttons. Ordinary inventories and external GUIs retain `cancel()`, `uncancel()`
and writable `cancelled`; a generic handler can deliberately undo another plugin's cancel.

```tys
event player.inventoryClick {
    if item?.tag("soulbound") != null && topInventory.type != InventoryType.PLAYER
        && topInventory.type != InventoryType.CRAFTING {
        event.cancel()
        player.send("<red>Soulbound items cannot be stored.")
    }
}
```

## Saving items

An `ItemStack` can be kept in a `persistent var` or `playerdata var` — with its name, lore,
enchantments, tags and every other component:

```tys
playerdata var backpack: List<ItemStack?> = []

@playerOnly
command backpack {
    let menu = Menu(3, "<dark_aqua>{player.name}'s backpack")
    menu.allowTaking = true
    for i in 0..<player.backpack.size {
        menu.set(i, player.backpack[i])
    }
    menu.onClose(p => {
        p.backpack = menu.inventory.contents
    })
    menu.open(player)
}
```

## Next

The in-memory test platform records both Player.give overloads per receiver,
including material, amount and name. Basic item/menu scenarios can run, but
inventory stacking, full-inventory drops and world physics require a Paper copy
and a client. A successful compiler check alone does not verify those behaviours.

* [Menus](Menus) — inventories with buttons
* [Custom data tags](Custom-Data-Tags) — recognising your own items
* [Players](Players) — `give`, hands and armor
