# Menus

A menu is a chest inventory whose slots run script functions when they are clicked.
Players cannot take or put items in a menu unless it allows it.

The Java test platform also models chest menus and click callbacks, including deferred
close/open ordering, handler retention when `menu.inventory.set` changes an item, and
closing menus when their owning script is retired. It is a behavioural test double;
inventory packets, drag gestures and Bukkit event integration still need a Paper test.

```tys
playerdata var coins: int = 100

function openShop(player: Player) {
    let menu = Menu(3, "<dark_green>Shop")
    menu.fillBorder(ItemStack(Material.GRAY_STAINED_GLASS_PANE, 1, " "))
    menu.set(11, ItemStack(Material.BREAD, 16, "<yellow>Bread", ["<gray>10 coins"]), click => {
        buy(click.player, ItemStack(Material.BREAD, 16), 10)
    })
    menu.set(15, ItemStack(Material.DIAMOND, 1, "<aqua>Diamond", ["<gray>100 coins"]), click => {
        buy(click.player, ItemStack(Material.DIAMOND), 100)
    })
    menu.set(22, ItemStack(Material.BARRIER, 1, "<red>Close"), click => {
        click.close()
    })
    menu.open(player)
}

function buy(player: Player, item: ItemStack, price: int) {
    if player.coins < price {
        player.send("<red>You need {price} coins.")
        player.playSound(Sound.ENTITY_VILLAGER_NO)
        return
    }
    player.coins -= price
    player.give(item)
    player.send("<green>Bought {item}.")
}

@playerOnly
command shop {
    openShop(player)
}
```

## Building a menu

| Member | Meaning |
|--------|---------|
| `Menu(rows, title)` | A chest menu with 1 to 6 rows (slots `0` to `rows * 9 - 1`). |
| `Menu(InventoryType.HOPPER, title)` | A menu of another shape. |
| `Menu.slot(row, column)` | The slot of a row and a column, both counted from 0. |
| `menu.set(slot, item)` | Shows an item without a click handler. |
| `menu.set(slot, item, click => ...)` | Shows an item and runs the function when it is clicked. |
| `menu.onClick(slot, click => ...)` | Sets or replaces the handler of a slot. |
| `menu.fill(item)`, `menu.fillBorder(item)` | Fills the empty slots (or the empty border). |
| `menu.clear()` | Empties every slot and removes the handlers. |
| `menu.onOpen(p => ...)`, `menu.onClose(p => ...)` | Runs a function when a player opens or closes it. |
| `menu.open(player)`, `menu.close()` | Shows the menu to a player; closes it for everyone. |
| `menu.allowTaking = true` | Lets players take and put items (a storage menu). |
| `menu.viewers`, `menu.inventory`, `menu.size` | Who looks at it, its inventory, its size. |

## The click

`click.player`, `click.slot`, `click.click` (`ClickType.LEFT`, `ClickType.SHIFT_RIGHT`,
...), `click.left`, `click.right`, `click.shift`, `click.item`, `click.cursor`,
`click.hotbarButton`, `click.menu`; `click.close()` closes the menu for the player after
the click, and `click.cancelled = false` lets this one click move the item.

Clicks in the player's own inventory are allowed, except shift-clicks and
double-clicks that would move items into the menu.

The server does not allow changing a player's screen in the middle of a click, so
opening another menu, `click.close()`, `player.openInventory(...)` or
`player.closeInventory()` from a click function (or from `onOpen`/`onClose`, or a
`player.inventory*` event handler) takes effect right after the click; the rest of the
function runs first. `menu.set(slot, item)` also removes the slot's function; to change
only the item, write `menu.inventory.set(slot, item)`.

## Lifetime

A menu can be shown to several players and updated while they look at it
(`menu.set(...)` changes what they see). It belongs to the script that created it:
when that script is reloaded, every open copy is closed, so a player never keeps a
menu whose buttons do nothing. Menus nobody looks at are freed automatically.

## Testing basic items

The in-memory test platform models chest slots/clicks, item names/lore, hands and
both Player.give overloads. Given items are recorded by receiver, material, amount
and name, so a command can be checked after it changes saved data. It does not
simulate inventory stacking, full-inventory drops or world physics; test those
on a Paper copy with a client. This is separate from compiler-only examples.
