# The world

This page is a tour of the standard library for players, entities, items, inventories,
blocks and worlds. Every member is listed with its type in the
[reference](reference.md#types).

## Players

```tys-body
player.health = player.maxHealth
player.food = 20
player.saturation = 5.0
player.level += 1
player.giveExp(50)
player.gameMode = GameMode.CREATIVE
player.allowFlight = true
player.walkSpeed = 0.3
player.addPotionEffect(PotionEffectType.NIGHT_VISION, 5 minutes, 1)
player.playSound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP)
player.setCooldown(Material.ENDER_PEARL, 5 seconds)
player.send("Ping: {player.ping} ms, language: {player.locale}")
if player.hasPermission("server.vip") && !player.flying {
    player.teleport(player.world.spawnLocation)
}
```

Offline players (`server.offlinePlayer(name)`, `server.offlinePlayer(uuid)`) have
`firstPlayed`, `lastSeen`, `banned`, `whitelisted`, `ban(reason, duration)` and
`pardon()`, and their [saved data](storage.md).

## Entities

```tys-body
let zombie = player.location.spawn(EntityType.ZOMBIE) as Mob
zombie.customName = "<red>Guard"
zombie.customNameVisible = true
zombie.helmet = ItemStack(Material.GOLDEN_HELMET)
zombie.target = player
zombie.setAttributeBase(Attribute.MOVEMENT_SPEED, 0.35)

for nearby in player.nearbyEntities(10) {
    if nearby is Monster {
        nearby.glowing = true
    }
}
let looking = player.targetEntity(20)
if looking != null {
    player.send("You look at a {looking.type}")
}
```

Types follow Minecraft: `Entity` > `LivingEntity` > `Mob` > `Monster`, `Animals`,
`Tameable`; `Player`, `Item` (an item on the ground), `Projectile`, `ArmorStand`,
`TextDisplay` (floating text), `ExperienceOrb`, `FallingBlock`, `TNTPrimed`. Use `is`
and `as?` to go from a general type to a specific one.

## Items

```tys-body
let sword = ItemStack(Material.DIAMOND_SWORD, 1, "<gold>Excalibur", ["<gray>A legendary blade"])
sword.enchant(Enchantment.SHARPNESS, 7)
sword.unbreakable = true
sword.addFlags(ItemFlag.HIDE_ENCHANTS)
sword.setTag("weapon", "excalibur")          // hidden data, kept when the item moves
player.give(sword)

let hand = player.mainHand
if hand != null && hand.tag("weapon") == "excalibur" {
    player.send("You hold {hand}")               // "1x Excalibur"
}
```

`ItemStack` has `type`, `amount`, `name`, `lore`, `enchantments`, `damage`,
`glowing`, `customModelData`, `itemModel`, `color` (leather armor, potions),
`skullOwner` and more. Items given to a player that do not fit drop at their feet.

## Inventories

```tys-body
let inventory = player.inventory
if inventory.count(Material.DIAMOND) >= 5 {
    inventory.remove(Material.DIAMOND, 5)
    player.give(Material.EMERALD, 1)
}
inventory.heldSlot = 0
player.enderChest.add(ItemStack(Material.BREAD, 16))
let chest = Inventory(3, "<dark_gray>Storage")
chest.set(13, ItemStack(Material.APPLE))
player.openInventory(chest)
```

For inventories with click handlers, see [Menus](gui.md).

## Blocks, locations and worlds

```tys-body
let here = player.location
let below = here.block.relative(BlockFace.DOWN)
if below.type == Material.GRASS_BLOCK {
    below.type = Material.DIRT
}
let chest = here.add(0, 1, 0).block
chest.type = Material.CHEST
chest.inventory?.add(ItemStack(Material.DIAMOND))
chest.setTag("owner", player.name)           // any block can have tags

let world = player.world
world.time = 6000
world.storm = false
world.setGameRule("keepInventory", "true")
world.strikeLightningEffect(here)
here.spawnParticle(Particle.HEART, 10, 0.5, 0.5, 0.5, 0.0)
here.playSound(Sound.BLOCK_NOTE_BLOCK_PLING)
let hologram = here.add(0, 2, 0).spawnText("<gold>Welcome!")
```

`Location` values are copies: `add`, `subtract`, `withY`, `center`, `forward` and
friends return new locations. `Vector(x, y, z)` does the same for directions and
velocities (`player.velocity = player.direction.multiply(2)`). `world.fill(from, to,
material)` changes up to 32768 blocks at once, and `location.blocksInRadius(r)` lists
the blocks of a sphere.

## Threads (Folia)

Reading is always allowed. Changing an entity or a block from a handler of another
region (or from an asynchronous event) is forwarded to the thread that owns it and
applied on its next tick. Operations that return a new entity (such as `spawn`) wait
for the owner when they are called off the server threads, and report a clear error
when called from another region's thread on Folia; run them in `after 1 tick for
<entity>` blocks there.
