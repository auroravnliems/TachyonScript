# Menus

A **menu** is a chest screen (a GUI) whose slots run your functions when they are clicked:
shops, selectors, confirmations, settings, teleport menus. Players cannot take items out of a
menu or put items into it unless you allow it.

The Java test platform can exercise chest menus and click callbacks, deferred close/open
ordering, `menu.inventory.set` retaining callbacks, and menus closing with their retired
script. It does not model inventory packets or drag gestures; test Bukkit integration on Paper.

```tys
playerdata var coins: int = 100

function openShop(player: Player) {
    let menu = Menu(3, "<dark_green><bold>Shop")
    menu.fillBorder(ItemStack(Material.GRAY_STAINED_GLASS_PANE, 1, " "))
    menu.set(11, ItemStack(Material.BREAD, 16, "<yellow>Bread", ["<gray>16 bread", "<gold>Price: 10 coins"]), click => {
        buy(click.player, ItemStack(Material.BREAD, 16), 10)
    })
    menu.set(13, ItemStack(Material.IRON_INGOT, 8, "<white>Iron", ["<gray>8 iron ingots", "<gold>Price: 40 coins"]), click => {
        buy(click.player, ItemStack(Material.IRON_INGOT, 8), 40)
    })
    menu.set(15, ItemStack(Material.DIAMOND, 1, "<aqua>Diamond", ["<gray>1 diamond", "<gold>Price: 100 coins"]), click => {
        buy(click.player, ItemStack(Material.DIAMOND), 100)
    })
    menu.set(22, ItemStack(Material.BARRIER, 1, "<red>Close"), click => {
        click.close()
    })
    menu.open(player)
}

function buy(player: Player, item: ItemStack, price: int) {
    if player.coins < price {
        player.send("<red>You need {price} coins; you have {player.coins}.")
        player.playSound(Sound.ENTITY_VILLAGER_NO)
        return
    }
    player.coins -= price
    player.give(item)
    player.playSound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP)
    player.send("<green>Bought {item}. <gray>{player.coins} coins left.")
}

@playerOnly
command shop {
    openShop(player)
}
```

## Slots

A chest menu has 1 to 6 rows of 9 slots. Slots are numbered from `0` (top left) to
`rows * 9 - 1` (bottom right):

```text
 0  1  2  3  4  5  6  7  8
 9 10 11 12 13 14 15 16 17
18 19 20 21 22 23 24 25 26
```

`Menu.slot(row, column)` computes the number, both counted from 0: `Menu.slot(1, 4)` is `13`,
the center of a 3-row menu. Using a slot the menu does not have is a runtime error
(`Slot 40 does not exist (the menu has slots 0 to 26).`).

## Building a menu

| Member | Meaning |
|--------|---------|
| `Menu(rows, title)` | a chest menu with 1 to 6 rows |
| `Menu(InventoryType.HOPPER, title)` | a menu of another shape: `HOPPER` (5 slots), `DISPENSER`, `DROPPER` (9), ... |
| `menu.set(slot, item)` | shows an item without a click function |
| `menu.set(slot, item, click => ...)` | shows an item and runs the function when it is clicked |
| `menu.onClick(slot, click => ...)` | sets or replaces the function of a slot |
| `menu.get(slot)` | the item in a slot |
| `menu.fill(item)` | puts an item in every empty slot |
| `menu.fillBorder(item)` | puts an item in every empty slot of the outer rows and columns |
| `menu.clear()` | empties every slot and removes the functions |
| `menu.onOpen(p => ...)`, `menu.onClose(p => ...)` | runs a function when a player opens / closes it |
| `menu.open(player)` | shows the menu to a player |
| `menu.close()` | closes it for everyone looking at it |
| `menu.allowTaking` | `true`: players may take and put items (a storage menu) |
| `menu.rows`, `menu.size`, `menu.title`, `menu.inventory`, `menu.viewers` | information |

## The click

The function of a slot receives a `MenuClick`:

| Member | Meaning |
|--------|---------|
| `click.player` | who clicked |
| `click.slot` | the clicked slot |
| `click.left`, `click.right`, `click.shift` | the kind of click |
| `click.click` | the exact kind: `ClickType.LEFT`, `SHIFT_RIGHT`, `MIDDLE`, `NUMBER_KEY`, `DROP`, `DOUBLE_CLICK`, ... |
| `click.hotbarButton` | the number key pressed (0–8), or -1 |
| `click.item`, `click.cursor` | the item in the slot; the item on the cursor |
| `click.menu` | the menu |
| `click.close()` | closes the menu for this player right after the click |
| `click.cancelled` | `true` by default; set it to `false` to let this click move the item |

```tys
function amountItem(amount: int): ItemStack {
    return ItemStack(Material.GOLD_NUGGET, amount, "<yellow>Amount: {amount}", ["<gray>Left: +1, right: -1, shift: x10"])
}

function openAmountPicker(player: Player) {
    let menu = Menu(1, "<gold>Pick an amount")
    menu.set(4, amountItem(1), click => {
        let current = click.item?.amount ?? 1
        let step = click.shift ? 10 : 1
        let next = math.clamp(click.left ? current + step : current - step, 1, 64)
        click.menu.inventory.set(4, amountItem(next))     // changes the item, keeps this function
    })
    menu.set(8, ItemStack(Material.LIME_DYE, 1, "<green>Confirm"), click => {
        let amount = click.menu.get(4)?.amount ?? 1
        click.player.give(Material.GOLD_NUGGET, amount)
        click.close()
    })
    menu.open(player)
}
```

`menu.set(slot, item)` without a function also removes the slot's function. To change only
the item of a slot — for example from its own click function — write to the menu's inventory:
`menu.inventory.set(slot, item)`.

## Clicks outside the menu

While a menu is open, the player can still use their own inventory at the bottom of the
screen — except the clicks that would move items **into** the menu (shift-clicks and
double-clicks), which are cancelled. Dragging items over the menu is cancelled too. With
`menu.allowTaking = true` all of this is allowed.

## One menu for everyone, or one per player

A menu can be shown to several players at once; changes (`menu.set(...)`) are visible to every
viewer immediately. Create one menu per player (in a function, as `openShop` above) when its
content depends on the player; create one shared menu when it shows the same thing to everyone:

```tys
let warps = {"Spawn": Location(server.defaultWorld, 0.5, 80, 0.5), "Arena": Location(server.defaultWorld, 200.5, 70, -50.5)}

let warpMenu = buildWarpMenu()

function buildWarpMenu(): Menu {
    let menu = Menu(1, "<dark_aqua>Warps")
    var slot = 0
    for name, location in warps {
        menu.set(slot, ItemStack(Material.ENDER_PEARL, 1, "<aqua>{name}"), click => {
            click.player.teleport(location)
            click.close()
        })
        slot++
    }
    return menu
}

@playerOnly
command warps {
    warpMenu.open(player)
}
```

## Pages

A menu for a long list shows one page and rebuilds itself for the next one:

```tys
function openPlayerList(viewer: Player, page: int) {
    let everyone = server.players.sortedBy(p => p.name)
    let perPage = 45
    let pages = math.max(1, (everyone.size + perPage - 1) / perPage)
    let menu = Menu(6, "<dark_gray>Players ({page + 1}/{pages})")
    let shown = everyone.drop(page * perPage).take(perPage)
    for i in 0..<shown.size {
        let target = shown[i]
        let head = ItemStack(Material.PLAYER_HEAD, 1, "<yellow>{target.name}", ["<gray>Click to teleport"])
        head.skullOwner = target
        menu.set(i, head, click => {
            click.player.teleport(target)
            click.close()
        })
    }
    if page > 0 {
        menu.set(45, ItemStack(Material.ARROW, 1, "<gray>Previous page"), click => {
            openPlayerList(click.player, page - 1)
        })
    }
    if page < pages - 1 {
        menu.set(53, ItemStack(Material.ARROW, 1, "<gray>Next page"), click => {
            openPlayerList(click.player, page + 1)
        })
    }
    menu.open(viewer)
}

@playerOnly
@permission("server.tpmenu")
command tpmenu {
    openPlayerList(player, 0)
}
```

A menu opened from a click function replaces the current one. The server does not allow
changing a player's screen in the middle of a click, so `menu.open(...)`, `click.close()`,
`player.openInventory(...)` and `player.closeInventory()` called from a click (or from an
`onOpen`/`onClose` function, or an inventory event handler) take effect right after it, on the
player's next tick; the rest of the function runs first.

## Confirmations

```tys
function confirm(player: Player, question: string, onYes: function(Player): void) {
    let menu = Menu(3, "<dark_red>{question}")
    menu.set(11, ItemStack(Material.LIME_WOOL, 1, "<green><bold>Yes"), click => {
        click.close()
        onYes(click.player)
    })
    menu.set(15, ItemStack(Material.RED_WOOL, 1, "<red><bold>No"), click => {
        click.close()
    })
    menu.open(player)
}

@playerOnly
command resetme {
    confirm(player, "Reset your homes?", p => {
        p.send("<green>Your homes were reset.")
    })
}
```

## Storage menus

With `allowTaking = true` a menu is a normal storage screen, useful for backpacks and virtual
chests. Read what the player left in it when they close it:

```tys
playerdata var vault: List<ItemStack?> = []

@playerOnly
command vault {
    let menu = Menu(6, "<gold>{player.name}'s vault")
    menu.allowTaking = true
    for i in 0..<player.vault.size {
        menu.set(i, player.vault[i])
    }
    menu.onClose(p => {
        p.vault = menu.inventory.contents
        p.send("<gray>Vault saved.")
    })
    menu.open(player)
}
```

## Updating a menu while it is open

```tys
@playerOnly
command clock {
    let menu = Menu(1, "<gold>Server clock")
    menu.open(player)
    every 1 second {
        if menu.viewers.isEmpty {
            task.cancel()
            return
        }
        menu.set(4, ItemStack(Material.CLOCK, 1, "<yellow>{time.format(time.now, "HH:mm:ss")}",
            ["<gray>Online: {server.onlineCount}", "<gray>TPS: {math.roundTo(server.tps, 1)}"]))
    }
}
```

## Lifetime

A menu belongs to the script that created it. When the script is reloaded, every open copy is
closed, so a player never keeps a menu whose buttons belong to an old version. Menus nobody
looks at any more are freed automatically. Click functions run on the thread of the player who
clicked (the player's region on Folia).

## Menus or inventory events?

| Need | Use |
|------|-----|
| buttons that do something | a `Menu` |
| a normal chest-like storage | `Inventory(rows, title)` or a `Menu` with `allowTaking` |
| reacting to clicks in *other* inventories (chests, the player's inventory) | the `player.inventoryClick` [event](Events) |

## Next

* [Items and inventories](Items-and-Inventories) — the items shown in menus
* [Saving data](Saving-Data) — remembering what players chose
* [Recipes](Recipes) — complete shops and selectors
