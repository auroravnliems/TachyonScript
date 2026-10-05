# Events

A script reacts to things that happen on the server with event handlers:

```tys
event player.join {
    player.send("<green>Welcome {player.name}!")
}

event block.break {
    if !player.hasPermission("build.bypass") {
        event.cancel()
        player.send("<red>You cannot build here.")
    }
}
```

Inside a handler:

* the event's **variables** (`player`, `block`, `killer`, ...) are available by
  name. They are listed per event in the [reference](reference.md#events);
* `event` is the event object, with members such as `event.cancel()`,
  `event.cancelled`, `event.joinMessage`, `event.damage` or `event.respawnLocation`.

A script can have several handlers for the same event, and several scripts can
handle the same event. If one handler fails with a runtime error, the error is logged
(with the script line) and the other handlers still run.

## Order and cancelled events

Handlers run by priority, from `LOWEST` to `MONITOR`, like Bukkit listeners; within a
priority, in order of file name and declaration. `@ignoreCancelled` skips the handler
when an earlier handler (or another plugin) has cancelled the event:

```tys
@priority(LOWEST)
event player.chat {
    if message.containsIgnoreCase("badword") {
        event.cancel()
    }
}

@ignoreCancelled
event player.chat {
    log.info("{player.name}: {message}")
}

@priority(MONITOR)
event player.chat {
    // MONITOR handlers see the final result and should not change it.
    if event.cancelled {
        log.info("Blocked a message from {player.name}")
    }
}
```

Priorities: `LOWEST`, `LOW`, `NORMAL` (the default), `HIGH`, `HIGHEST`, `MONITOR`.

`event.cancel()`, `event.uncancel()` and the writable `event.cancelled` property
change the underlying Bukkit event. Assigning `false` can therefore undo another
plugin's cancellation. Only do that when your script deliberately owns that decision;
for an external GUI it can allow its icons to move as real items.

Menus created with `Menu(...)` handle clicks and drags through their own listener.
While such a menu is open, `player.inventoryClick` and `player.inventoryDrag` do not
run for any part of its view. This does not depend on priority, `@ignoreCancelled`
or `allowTaking`. Ordinary inventories and other plugins' inventories keep the normal
event and cancellation API. See [menus](gui.md).

## Available events

More than a hundred events are available; the [reference](reference.md#events) lists
each one with its variables. The most used:

| Area      | Events |
|-----------|--------|
| Players   | `player.join`, `player.quit`, `player.chat`, `player.command`, `player.move`, `player.teleport`, `player.death`, `player.respawn`, `player.interact`, `player.interactEntity`, `player.drop`, `entity.pickup`, `player.consume`, `player.itemHeld`, `player.swapHands`, `player.toggleSneak`, `player.toggleSprint`, `player.toggleFlight`, `player.jump`, `player.gameModeChange`, `player.levelChange`, `player.expChange`, `player.worldChange`, `player.bedEnter`, `player.fish`, `player.advancement`, `player.kick`, `player.preLogin`, `player.foodChange`, `player.armorChange`, `player.launchProjectile` |
| Inventories | `player.inventoryClick`, `player.inventoryDrag`, `player.inventoryOpen`, `player.inventoryClose`, `player.craft`, `player.enchant`, `furnace.smelt`, `furnace.burn`, `inventory.moveItem` |
| Blocks    | `block.break`, `block.place`, `block.burn`, `block.ignite`, `block.grow`, `block.spread`, `block.fade`, `block.form`, `block.explode`, `block.redstone`, `block.flow`, `block.leavesDecay`, `block.damage`, `block.dispense`, `block.pistonExtend`, `block.pistonRetract`, `block.fertilize`, `player.signChange`, `player.bucketFill`, `player.bucketEmpty` |
| Entities  | `entity.damage`, `entity.damageByEntity`, `entity.death`, `entity.spawn`, `entity.target`, `entity.tame`, `entity.breed`, `entity.explode`, `entity.regainHealth`, `entity.shootBow`, `entity.combust`, `entity.changeBlock`, `entity.teleport`, `entity.mount`, `entity.dismount`, `entity.transform`, `entity.potionEffect`, `projectile.launch`, `projectile.hit`, `item.spawn`, `item.despawn`, `vehicle.enter`, `vehicle.exit` |
| World     | `world.weatherChange`, `world.thunderChange`, `world.lightning`, `world.timeSkip`, `world.load`, `world.unload`, `chunk.load`, `chunk.unload`, `world.structureGrow`, `world.portalCreate` |
| Server    | `server.ping` (the server list), `server.command` (console commands) |

Addons can add more events.

## Threads

Handlers run on the thread that fired the event. On Folia that is the region thread
that owns the player or block, so handlers for different regions run in parallel;
`player.chat`, `player.preLogin` and `server.ping` run off the main thread on both
Paper and Folia. Scripts do not need to care: when a handler changes something that
another thread owns (for example the health of a player in a different region),
TachyonScript hands the change to the owner, and it is applied on that owner's next
tick.

## Costs

A server only listens to an event while at least one script handles it, so an
unused `player.move` handler costs nothing. Handlers for frequent events
(`player.move` fires many times per second per player) should stay short:
`event.changedBlock` tells whether the player actually moved to another block.
