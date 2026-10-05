# Events

Everything that happens on the server — a player joins, a block breaks, a creeper explodes,
someone clicks an inventory — is an **event**. A script reacts to an event with an event
handler:

```tys
event player.join {
    player.send("<green>Welcome, {player.name}!")
}
```

`event` is followed by the event's name and a block. The block runs every time the event
happens. A script can have any number of handlers, also several for the same event, and
several scripts can handle the same event.

## Event variables

Each event gives its handler a few **variables** with the things involved. In `player.join`
it is `player`; in `block.break` it is `player` and `block`; in `entity.damageByEntity` it is
`entity`, `damager`, `attacker` and `cause`. They are listed for every event in the
[catalogue below](#all-events).

```tys
event block.break {
    if block.type == Material.DIAMOND_ORE {
        broadcast("<aqua>{player.name} found diamonds!")
    }
}

event entity.damageByEntity {
    if entity is Player && attacker is Player {
        attacker.send("<red>PvP is disabled here.")
        event.cancel()
    }
}
```

Variables whose type ends in `?` may be `null` — for example `killer` in `player.death` is
`Player?` because a player can die without being killed by another player. The compiler makes
you check them before use:

```tys
event player.death {
    if killer != null {
        killer.send("<gold>You killed {victim.name}!")
        killer.giveExp(10)
    }
}
```

## The event object

`event` is the event itself. It has members to read and change what will happen:

```tys
event player.join {
    event.joinMessage = "<gray>[<green>+</green>] {player.name}"
}

event player.quit {
    event.quitMessage = null                          // no quit message at all
}

event player.death {
    event.keepInventory = victim.hasPermission("server.keepinventory")
    event.deathMessage = "<red>{victim.name} <gray>fell."
}

event entity.damage {
    if cause == "fall" && entity is Player {
        event.damage = event.damage / 2               // half fall damage for players
    }
}

event entity.death {
    if entity.type == EntityType.ZOMBIE {
        event.clearDrops()
        event.addDrop(ItemStack(Material.EMERALD))
        event.droppedExp = 20
    }
}

event player.respawn {
    event.respawnLocation = server.defaultWorld.spawnLocation
}
```

Some members that are often useful:

| Event | Members of `event` |
|-------|-------------------|
| `player.join`, `player.quit` | `joinMessage`, `quitMessage` (set to `null` to hide) |
| `player.chat` | `message` (what everyone sees) |
| `player.death` | `deathMessage`, `keepInventory`, `keepLevel`, `newLevel`, `drops`, `clearDrops()`, `addDrop(item)`, `droppedExp` |
| `entity.death` | `drops`, `clearDrops()`, `addDrop(item)`, `droppedExp` |
| `entity.damage`, `entity.damageByEntity` | `damage` (changeable), `finalDamage`, `damageCause` |
| `player.move` | `from`, `to` (changeable), `changedBlock` |
| `player.teleport` | `destination` (changeable) |
| `player.respawn` | `respawnLocation`, `isBedSpawn`, `isAnchorSpawn` |
| `player.interact` | `rightClick`, `leftClick`, `mainHand` |
| `player.command` | `message` (the whole command line, changeable) |
| `player.preLogin` | `allow()`, `disallow(reason)`, `allowed` |
| `block.break` | `dropItems`, `exp` |
| `entity.explode`, `block.explode` | `clearBlocks()`, `yield` |
| `server.ping` | `motd`, `maxPlayers`, `onlinePlayers`, `hidePlayers`, `version` |

Every event object and its members are in the [API reference](API-Reference).

## Cancelling

Events marked "yes" in the *Cancel* column of the [catalogue](#all-events) can be stopped: the
block is not broken, the message is not sent, the damage is not dealt.

```tys
event block.place {
    if block.type == Material.TNT && !player.hasPermission("server.tnt") {
        event.cancel()
        player.send("<red>TNT is not allowed.")
    }
}
```

* `event.cancel()` cancels, `event.uncancel()` un-cancels, and `event.cancelled` tells whether
  the event is cancelled (it can also be assigned).
* Cancelling an event that cannot be cancelled is a compile error
  (`Event 'player.join' cannot be cancelled.`).

## Order of handlers

Handlers run by **priority**, from `LOWEST` to `MONITOR`, like the listeners of Java plugins.
Handlers with the same priority run in the order of their files' names and then in the order
they are written.

```tys
@priority(LOWEST)
event player.chat {
    if message.containsIgnoreCase("badword") {
        event.cancel()
        player.send("<red>Watch your language.")
    }
}

@ignoreCancelled
event player.chat {
    log.info("[chat] {player.name}: {message}")
}

@priority(MONITOR)
event player.chat {
    if event.cancelled {
        log.info("Blocked a message from {player.name}")
    }
}
```

| Annotation | Meaning |
|------------|---------|
| `@priority(LOWEST)` ... `@priority(MONITOR)` | when the handler runs: `LOWEST`, `LOW`, `NORMAL` (the default), `HIGH`, `HIGHEST`, `MONITOR` |
| `@ignoreCancelled` | skip the handler when the event is already cancelled (by a script or another plugin) |

Use `LOWEST` to decide early (other plugins then see the cancelled event), `HIGHEST` to have
the last word, and `MONITOR` only to observe the final result — a `MONITOR` handler should not
change the event. `@ignoreCancelled` on an event that cannot be cancelled is a compile error.

## Errors in handlers

If a handler fails with a runtime error (for example it reads past the end of a list), the
error is logged with the script and line, and the other handlers of the event still run. The
same error at the same place is logged once and then counted, so a failing `player.move`
handler cannot flood the console; `/tys errors` shows the counts. See
[Error handling](Error-Handling).

## Using the event later

An `after` block runs when the event is already over. Cancelling or changing the event there
has no effect, so the compiler warns about it:

```text
WARNING scripts/combat.tys:3:5 [TYS0309]

  3 |     after 1 second {
    |     ^^^^^^^^^^^^^^^^

The event is over when this block runs: cancelling it or changing it has no effect.

Read what you need from the event before the block, e.g. let damage = event.damage
```

Event variables such as `player` or `block` can be used in `after` blocks: they are copied when
the block is created. The player may have left by then — write
`after 5 seconds for player { ... }` so the block is skipped when the player is gone (see
[Timers and tasks](Timers-and-Tasks)).

## Threads

Handlers run on the thread that fired the event:

* on Paper, most events fire on the main server thread;
* `player.chat`, `player.preLogin` and `server.ping` fire on background threads, on Paper and
  on Folia;
* on Folia, events of a player or a block fire on the thread of the region that owns them,
  so handlers for different areas of the world run at the same time.

Scripts do not need to care: when a handler changes something another thread owns (the health
of a player in another region, a block in another chunk), TachyonScript hands the change to the
owner, which applies it on its next tick. Script variables, lists and maps are safe to share
between handlers. See [Performance and Folia](Performance-and-Folia).

## Performance

The server only listens to an event while at least one loaded script handles it: an unused
event costs nothing. A small handler costs about 44 nanoseconds to run.

Some events fire very often — `player.move` many times per second for every player,
`block.redstone`, `block.flow`, `entity.target`, `inventory.moveItem` (hoppers). Keep their
handlers short and leave early when there is nothing to do:

```tys
event player.move {
    if !event.changedBlock {
        return                        // only turned the head or moved inside the block
    }
    if to.block.relative(BlockFace.DOWN).type == Material.GOLD_BLOCK {
        player.addPotionEffect(PotionEffectType.SPEED, 3 seconds, 2)
    }
}
```

## All events

The 111 events of the standard library, with the variables their handlers get. The members of
each event object are in the [API reference](API-Reference). Addons can add more events.

### Players

| Event | Variables | Cancel | What happened |
|-------|-----------|:------:|---------------|
| `player.join` | `player: Player` |  | A player joined the server. |
| `player.quit` | `player: Player` |  | A player left the server. |
| `player.death` | `victim: Player`, `killer: Player?` | yes | A player died (cancelling it revives the player). |
| `player.chat` | `player: Player`, `message: string` | yes | A player sent a chat message. |
| `player.move` | `player: Player`, `from: Location`, `to: Location` | yes | A player moved or turned. |
| `player.respawn` | `player: Player` |  | A player respawns after dying. |
| `player.teleport` | `player: Player`, `from: Location`, `to: Location`, `cause: TeleportCause` | yes | A player teleports (command, ender pearl, portal, plugin, ...). |
| `player.command` | `player: Player`, `command: string`, `label: string` | yes | A player runs a command (before the command itself runs). |
| `player.interact` | `player: Player`, `action: Action`, `block: Block?`, `face: BlockFace`, `item: ItemStack?`, `hand: EquipmentSlot?` | yes | A player clicks: at the air, at a block, or steps on a pressure plate. |
| `player.interactEntity` | `player: Player`, `entity: Entity`, `hand: EquipmentSlot` | yes | A player right-clicks an entity. |
| `player.drop` | `player: Player`, `item: Item`, `stack: ItemStack` | yes | A player drops an item. |
| `player.consume` | `player: Player`, `item: ItemStack` | yes | A player eats food or drinks a potion or milk. |
| `player.itemHeld` | `player: Player`, `previousSlot: int`, `newSlot: int` | yes | A player selects another hotbar slot. |
| `player.swapHands` | `player: Player`, `mainHand: ItemStack?`, `offHand: ItemStack?` | yes | A player swaps the items of their hands (F key). |
| `player.toggleSneak` | `player: Player`, `sneaking: bool` | yes | A player starts or stops sneaking. |
| `player.toggleSprint` | `player: Player`, `sprinting: bool` | yes | A player starts or stops sprinting. |
| `player.toggleFlight` | `player: Player`, `flying: bool` | yes | A player starts or stops flying. |
| `player.gameModeChange` | `player: Player`, `gameMode: GameMode` | yes | A player's game mode changes. |
| `player.levelChange` | `player: Player`, `oldLevel: int`, `newLevel: int` |  | A player's experience level changes. |
| `player.expChange` | `player: Player` |  | A player gains experience points. |
| `player.worldChange` | `player: Player`, `from: World` |  | A player went to another world. |
| `player.bedEnter` | `player: Player`, `bed: Block` | yes | A player tries to sleep in a bed. |
| `player.bedLeave` | `player: Player`, `bed: Block` | yes | A player gets out of bed. |
| `player.fish` | `player: Player`, `caught: Entity?`, `state: string` | yes | Something happens while fishing (casting, a bite, catching, ...). |
| `player.bucketFill` | `player: Player`, `block: Block`, `bucket: Material` | yes | A player fills a bucket. |
| `player.bucketEmpty` | `player: Player`, `block: Block`, `bucket: Material` | yes | A player empties a bucket. |
| `player.advancement` | `player: Player`, `advancement: string` |  | A player completes an advancement. |
| `player.portal` | `player: Player`, `from: Location`, `cause: TeleportCause` | yes | A player enters a portal. |
| `player.kick` | `player: Player`, `reason: Component` | yes | A player is kicked. |
| `player.preLogin` | `name: string`, `uuid: UUID`, `address: string` |  | A player is connecting (before they are in the world; runs off the server thread). |
| `player.itemDamage` | `player: Player`, `item: ItemStack` | yes | A player's tool or armor loses durability. |
| `player.itemBreak` | `player: Player`, `item: ItemStack` |  | A player's tool or armor breaks. |
| `player.craft` | `player: Player`, `item: ItemStack`, `inventory: Inventory` | yes | A player takes the result of a crafting recipe. |
| `player.enchant` | `player: Player`, `item: ItemStack`, `block: Block` | yes | A player enchants an item at an enchanting table. |
| `player.jump` | `player: Player`, `from: Location`, `to: Location` | yes | A player jumps. |
| `player.armorChange` | `player: Player`, `slot: EquipmentSlot`, `oldItem: ItemStack`, `newItem: ItemStack` |  | A player's armor changes. |
| `player.launchProjectile` | `player: Player`, `projectile: Projectile`, `item: ItemStack` | yes | A player throws or shoots something with an item (snowball, ender pearl, trident, ...). |
| `player.signChange` | `player: Player`, `block: Block`, `lines: List<Component>` | yes | A player writes on a sign. |
| `player.inventoryClick` | `player: Player`, `slot: int`, `rawSlot: int`, `click: ClickType`, `item: ItemStack?`, `cursor: ItemStack?`, `inventory: Inventory?`, `topInventory: Inventory` | yes | A player clicks a slot of an open inventory (menus made with Menu handle their own clicks). |
| `player.inventoryDrag` | `player: Player`, `slots: List<int>`, `inventory: Inventory` | yes | A player drags items over several slots. |
| `player.inventoryOpen` | `player: Player`, `inventory: Inventory` | yes | A player opens an inventory. |
| `player.inventoryClose` | `player: Player`, `inventory: Inventory` |  | A player closes an inventory. |
| `player.shear` | `player: Player`, `entity: Entity` | yes | A player shears an entity (sheep, mooshroom, ...). |
| `player.harvest` | `player: Player`, `block: Block` | yes | A player harvests a block without breaking it (berries, ...). |
| `player.resourcePack` | `player: Player`, `status: string` |  | A player answers the resource pack prompt or finishes loading it. |
| `player.exhaustion` | `player: Player` | yes | A player gets hungrier (from moving, fighting, ...). |
| `player.foodChange` | `player: Player`, `item: ItemStack?` | yes | A player's food level changes. |

### Blocks

| Event | Variables | Cancel | What happened |
|-------|-----------|:------:|---------------|
| `block.break` | `player: Player`, `block: Block` | yes | A player is breaking a block. |
| `block.place` | `player: Player`, `block: Block`, `against: Block`, `item: ItemStack` | yes | A player places a block. |
| `block.burn` | `block: Block`, `source: Block?` | yes | Fire destroys a block. |
| `block.ignite` | `block: Block`, `player: Player?`, `cause: string` | yes | A block catches fire. |
| `block.grow` | `block: Block`, `newType: Material` | yes | A crop or plant grows. |
| `block.spread` | `block: Block`, `source: Block` | yes | A block spreads (fire, grass, mushrooms, ...). |
| `block.fade` | `block: Block`, `newType: Material` | yes | A block fades or melts (ice, snow, coral, fire). |
| `block.form` | `block: Block`, `newType: Material` | yes | A block forms (snow, ice, obsidian, concrete). |
| `block.explode` | `block: Block`, `blocks: List<Block>` | yes | A block explodes (bed in the nether, respawn anchor, ...). |
| `block.redstone` | `block: Block`, `oldCurrent: int` |  | A block's redstone current changes. |
| `block.flow` | `block: Block`, `to: Block` | yes | Water or lava flows (or a dragon egg teleports). |
| `block.leavesDecay` | `block: Block` | yes | Leaves decay. |
| `block.damage` | `player: Player`, `block: Block`, `item: ItemStack` | yes | A player starts breaking a block. |
| `block.dispense` | `block: Block` | yes | A dispenser or dropper shoots an item. |
| `block.pistonExtend` | `block: Block`, `direction: BlockFace`, `blocks: List<Block>` | yes | A piston pushes. |
| `block.pistonRetract` | `block: Block`, `direction: BlockFace`, `blocks: List<Block>` | yes | A piston pulls back. |
| `block.fertilize` | `block: Block`, `player: Player?` | yes | Bone meal is used on a block. |
| `furnace.smelt` | `block: Block`, `source: ItemStack` | yes | A furnace finishes smelting an item. |
| `furnace.burn` | `block: Block`, `fuel: ItemStack` | yes | A furnace burns a fuel item. |
| `inventory.moveItem` | `source: Inventory`, `destination: Inventory` | yes | A hopper or dropper moves an item between inventories. |

### Entities

| Event | Variables | Cancel | What happened |
|-------|-----------|:------:|---------------|
| `entity.damage` | `entity: Entity`, `cause: string` | yes | An entity is about to take damage. Change event.damage to modify it. |
| `entity.death` | `entity: LivingEntity`, `killer: Player?` | yes | A living entity (mob or player) dies. |
| `entity.damageByEntity` | `entity: Entity`, `damager: Entity`, `attacker: Entity?`, `cause: DamageCause` | yes | An entity is hurt by another entity (a hit, an arrow, an explosion, ...). |
| `entity.spawn` | `entity: LivingEntity`, `location: Location`, `reason: SpawnReason` | yes | A creature spawns. |
| `entity.target` | `entity: Entity`, `reason: string` | yes | A mob chooses a target (or loses it). |
| `entity.tame` | `entity: LivingEntity`, `owner: Player?` | yes | A player tames an animal. |
| `entity.breed` | `entity: LivingEntity`, `mother: LivingEntity`, `father: LivingEntity`, `breeder: Player?` | yes | Two animals breed. |
| `entity.regainHealth` | `entity: Entity`, `reason: string` | yes | An entity heals. |
| `entity.shootBow` | `entity: LivingEntity`, `bow: ItemStack?`, `projectile: Entity`, `force: double` | yes | An entity shoots a bow or crossbow. |
| `entity.combust` | `entity: Entity` | yes | An entity catches fire. |
| `entity.changeBlock` | `entity: Entity`, `block: Block`, `to: Material` | yes | An entity changes a block (endermen, falling sand, sheep eating grass, ...). |
| `entity.teleport` | `entity: Entity`, `from: Location` | yes | A non-player entity teleports (enderman, chorus fruit, ...). |
| `entity.toggleGlide` | `entity: Entity`, `gliding: bool` | yes | An entity starts or stops gliding with an elytra. |
| `entity.resurrect` | `entity: LivingEntity`, `hand: EquipmentSlot?` | yes | A totem of undying saves an entity (cancelled when it has none). |
| `entity.potionEffect` | `entity: Entity`, `newEffect: PotionEffect?`, `oldEffect: PotionEffect?`, `cause: string`, `action: string` | yes | An entity's potion effect is added, changed or removed. |
| `entity.mount` | `entity: Entity`, `mount: Entity` | yes | An entity gets on another entity. |
| `entity.dismount` | `entity: Entity`, `dismounted: Entity` | yes | An entity gets off another entity. |
| `entity.interact` | `entity: Entity`, `block: Block` | yes | A non-player entity interacts with a block (trampling farmland, pressing a plate, ...). |
| `entity.transform` | `entity: Entity`, `transformed: Entity`, `reason: string` | yes | An entity turns into another (zombie villager cured, pig struck by lightning, ...). |
| `entity.explode` | `entity: Entity`, `location: Location`, `blocks: List<Block>` | yes | An entity explodes (creeper, TNT, fireball, ...). |
| `entity.pickup` | `entity: LivingEntity`, `player: Player?`, `item: Item`, `stack: ItemStack` | yes | A player or mob picks up an item. |
| `explosion.prime` | `entity: Entity` | yes | An entity is about to explode. |
| `projectile.launch` | `projectile: Projectile`, `shooter: Entity?` | yes | A projectile is launched. |
| `projectile.hit` | `projectile: Projectile`, `hitEntity: Entity?`, `hitBlock: Block?`, `shooter: Entity?` | yes | A projectile hits an entity or a block. |

### Items on the ground

| Event | Variables | Cancel | What happened |
|-------|-----------|:------:|---------------|
| `item.spawn` | `item: Item`, `location: Location` | yes | An item appears in the world (dropped, broken block, ...). |
| `item.despawn` | `item: Item`, `location: Location` | yes | An item despawns after 5 minutes. |
| `item.merge` | `item: Item`, `target: Item` | yes | Two item stacks on the ground merge. |

### Paintings and item frames

| Event | Variables | Cancel | What happened |
|-------|-----------|:------:|---------------|
| `hanging.break` | `entity: Entity`, `cause: string` | yes | A painting or item frame breaks. |
| `hanging.place` | `entity: Entity`, `player: Player?`, `block: Block` | yes | A player places a painting or item frame. |

### Vehicles

| Event | Variables | Cancel | What happened |
|-------|-----------|:------:|---------------|
| `vehicle.enter` | `vehicle: Entity`, `entity: Entity` | yes | An entity gets into a vehicle (boat, minecart). |
| `vehicle.exit` | `vehicle: Entity`, `entity: LivingEntity` | yes | An entity gets out of a vehicle. |
| `vehicle.destroy` | `vehicle: Entity`, `attacker: Entity?` | yes | A vehicle is destroyed. |

### Worlds and chunks

| Event | Variables | Cancel | What happened |
|-------|-----------|:------:|---------------|
| `world.weatherChange` | `world: World`, `raining: bool` | yes | Rain starts or stops. |
| `world.thunderChange` | `world: World`, `thundering: bool` | yes | A thunderstorm starts or stops. |
| `world.lightning` | `world: World`, `location: Location`, `cause: string` | yes | Lightning strikes. |
| `world.load` | `world: World` |  | A world is loaded. |
| `world.unload` | `world: World` | yes | A world is about to unload. |
| `world.timeSkip` | `world: World`, `reason: string` | yes | The time jumps (sleeping, /time). |
| `world.structureGrow` | `location: Location`, `player: Player?`, `bonemeal: bool` | yes | A tree or big mushroom grows. |
| `world.portalCreate` | `world: World`, `entity: Entity?`, `reason: string` | yes | A portal is created. |
| `chunk.load` | `chunk: Chunk`, `world: World`, `newChunk: bool` |  | A chunk is loaded. |
| `chunk.unload` | `chunk: Chunk`, `world: World` |  | A chunk is about to unload. |

### Server

| Event | Variables | Cancel | What happened |
|-------|-----------|:------:|---------------|
| `server.ping` | `address: string` | yes | Someone's server list asks for the server's status (runs off the server thread). |
| `server.command` | `sender: CommandSender` | yes | The console (or a command block) runs a command. |

## Next

* [Commands](Commands) — the other way players interact with scripts
* [Timers and tasks](Timers-and-Tasks) — doing something later or regularly
* [API reference](API-Reference) — every event object and its members
