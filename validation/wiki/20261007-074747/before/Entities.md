# Entities

Everything that moves in a world — players, mobs, animals, dropped items, arrows, armor
stands, holograms — is an **entity**. The standard library follows Minecraft's own family
tree:

```text
Entity                      everything: location, velocity, name, tags, passengers, ...
├── LivingEntity            health, potion effects, equipment, AI, attributes
│   ├── Player              see the Players page
│   ├── ArmorStand
│   └── Mob                 target, pathfinding
│       ├── Monster         zombies, skeletons, creepers, ...
│       └── Ageable         babies
│           └── Animals     breeding
│               └── Tameable  wolves, cats, horses, ...
├── Item                    an item lying on the ground
├── Projectile              arrows, snowballs, tridents, fireballs, ...
├── ExperienceOrb
├── FallingBlock
├── TNTPrimed
└── TextDisplay             floating text (holograms)
```

A value of a more specific type has all the members of the types above it: a `Monster` is a
`Mob`, a `LivingEntity` and an `Entity`.

## Is it a zombie? `is`, `as?` and `type`

Event variables are usually typed generally (`entity: Entity` in `entity.damage`). Use `is` to
test the kind and `as?` to get the specific type:

```tys
event entity.damageByEntity {
    if entity is Player && attacker is Player {
        event.cancel()                                  // no PvP
        return
    }
    let mob = entity as? Mob
    if mob != null && attacker is Player {
        mob.target = attacker as? LivingEntity          // the mob fights back
    }
}

event entity.spawn {
    if entity.type == EntityType.PHANTOM {
        event.cancel()                                  // no phantoms
    }
}
```

* `x is T` — whether `x` is a `T`. After `if x is T { ... }`, `x` is a `T` inside the block.
* `x as? T` — `x` as a `T`, or `null` if it is not one.
* `x as T` — `x` as a `T`; a runtime error if it is not one. Use it only when you are sure,
  for example right after spawning.
* `entity.type` — the exact kind: `EntityType.ZOMBIE`, `EntityType.ARMOR_STAND`, ... (all 150+
  kinds of the server).

## Spawning

```tys
@playerOnly
@permission("server.boss")
command boss {
    let zombie = player.location.spawn(EntityType.ZOMBIE) as Mob
    zombie.customName = "<dark_red><bold>Zombie King"
    zombie.customNameVisible = true
    zombie.maxHealth = 100.0
    zombie.health = 100.0
    zombie.helmet = ItemStack(Material.GOLDEN_HELMET)
    zombie.mainHand = ItemStack(Material.NETHERITE_SWORD)
    zombie.setAttributeBase(Attribute.MOVEMENT_SPEED, 0.3)
    zombie.setAttributeBase(Attribute.ATTACK_DAMAGE, 8.0)
    zombie.setAttributeBase(Attribute.SCALE, 1.5)
    zombie.removeWhenFarAway = false
    zombie.setTag("boss", "zombie_king")
    zombie.target = player
    broadcast("<dark_red>The Zombie King has risen!")
}

event entity.death {
    if entity.tag("boss") == "zombie_king" {
        event.clearDrops()
        event.addDrop(ItemStack(Material.NETHER_STAR))
        event.droppedExp = 500
        broadcast("<gold>{killer?.name ?? "Someone"} defeated the Zombie King!")
    }
}
```

| Call | Result |
|------|--------|
| `location.spawn(EntityType.X)` | a new entity at the location |
| `world.spawn(EntityType.X, location)` | the same |
| `location.dropItem(item)`, `world.dropItem(location, item)` | an `Item` on the ground |
| `world.dropItemNaturally(location, item)` | the same with a small random offset |
| `entity.launchProjectile(EntityType.ARROW)` | a projectile shot where a living entity looks |
| `location.spawnText(text)` | a hologram (see [Titles, boss bars and sidebars](Titles-Boss-Bars-and-Sidebars#holograms)) |

The spawned entity is returned as an `Entity`; `as Mob`, `as ArmorStand`... gives the specific
type.

## Every entity

| Member | Meaning |
|--------|---------|
| `type`, `uuid`, `name` | kind, unique id (saved with the entity), name |
| `location`, `world`, `chunk` | where it is (`location` is a copy) |
| `teleport(location)`, `teleport(entity)` | moves it |
| `velocity`, `direction`, `facing` | movement (blocks per tick), where it looks |
| `onGround`, `inWater`, `inLava`, `fallDistance` | its surroundings |
| `customName`, `customNameVisible` | the name above it |
| `glowing`, `silent`, `gravity`, `invulnerable` | behavior flags (all changeable) |
| `fireTicks`, `freezeTicks`, `frozen` | burning and freezing |
| `persistent` | saved with its chunk (false: removed when the chunk unloads) |
| `passengers`, `addPassenger(e)`, `removePassenger(e)`, `eject()`, `vehicle`, `leaveVehicle()` | riding |
| `nearbyEntities(radius)`, `nearbyPlayers(radius)` | what is around it |
| `distance(entity)`, `distance(location)` | distances |
| `setRotation(yaw, pitch)` | turns it |
| `valid`, `dead`, `ticksLived` | whether it still exists, is dead, how old it is |
| `remove()` | removes it from the world (players must be kicked instead) |
| `scoreboardTags`, `addScoreboardTag(t)`, `hasScoreboardTag(t)`, `removeScoreboardTag(t)` | the tags of the `/tag` command |
| `tag(key)`, `setTag(key, value)`, ... | hidden data saved with the entity (see [Custom data tags](Custom-Data-Tags)) |
| `width`, `height` | its size |

## Living entities

| Member | Meaning |
|--------|---------|
| `health`, `maxHealth`, `absorption` | health |
| `heal(amount)`, `damage(amount)`, `damage(amount, source)`, `kill()` | change health |
| `lastDamage`, `killer`, `noDamageTicks` | combat information |
| `addPotionEffect(...)`, `removePotionEffect(type)`, `hasPotionEffect(type)`, `potionEffect(type)`, `potionEffects`, `clearPotionEffects()` | potion effects |
| `attribute(a)`, `attributeBase(a)`, `setAttributeBase(a, value)` | attributes (`Attribute.MAX_HEALTH`, `MOVEMENT_SPEED`, `SCALE`, ...) |
| `helmet`, `chestplate`, `leggings`, `boots`, `mainHand`, `offHand` | equipment (changeable) |
| `equipment(slot)`, `setEquipment(slot, item)` | equipment by `EquipmentSlot` |
| `ai` | whether it moves and acts on its own |
| `collidable`, `canPickupItems`, `removeWhenFarAway`, `invisible` | behavior flags |
| `eyeLocation`, `targetBlock(maxDistance)`, `targetEntity(maxDistance)`, `hasLineOfSight(e)` | what it looks at |
| `launchProjectile(type)` | shoots |
| `gliding`, `swimming`, `climbing`, `sleeping` | what it is doing |
| `remainingAir`, `maximumAir`, `arrowsInBody` | details |
| `swingMainHand()` | arm swing animation |

## Mobs

| Member | Meaning |
|--------|---------|
| `target` | the entity it attacks or follows (`null` for none) |
| `moveTo(location)`, `moveTo(location, speed)` | walks there; `false` if there is no path |
| `stopMoving()` | stops walking |
| `lookAt(location)` | turns its head |
| `aware` | `false`: it does nothing on its own but can still be pushed |

Babies, animals and pets:

| Type | Members |
|------|---------|
| `Ageable` | `baby`, `age` (negative for babies) |
| `Animals` | `loveMode`, `setLoveModeTicks(ticks)` |
| `Tameable` | `tamed`, `owner` (`OfflinePlayer?`), `setOwner(player)` |

```tys
@playerOnly
command pet {
    let wolf = player.location.spawn(EntityType.WOLF) as Tameable
    wolf.setOwner(player)
    wolf.customName = "<aqua>{player.name}'s wolf"
    wolf.baby = true
    player.send("<green>Meet your new pet!")
}
```

## Other kinds

| Type | Members |
|------|---------|
| `Item` (on the ground) | `itemStack`, `pickupDelay`, `unlimitedLifetime`, `thrower` |
| `Projectile` | `shooter` |
| `ArmorStand` | `arms`, `basePlate`, `marker`, `small` |
| `ExperienceOrb` | `experience` |
| `FallingBlock` | `material`, `dropItem` |
| `TNTPrimed` | `fuseTicks`, `yield` |
| `TextDisplay` | `text`, `scale`, `billboard`, `background`, `seeThrough`, `shadowed`, `lineWidth` |

```tys
event player.interact {
    if event.rightClick && event.mainHand && item?.type == Material.BLAZE_ROD {
        let fireball = player.launchProjectile(EntityType.SMALL_FIREBALL)
        fireball.velocity = player.direction.multiply(2)
        player.setCooldown(Material.BLAZE_ROD, 1 second)
    }
}

@permission("server.clearlag")
command clearitems {
    var removed = 0
    for world in server.worlds {
        for entity in world.entities {
            if entity is Item {
                entity.remove()
                removed++
            }
        }
    }
    broadcast("<gray>Removed {removed} items from the ground.")
}
```

## Finding entities

| Expression | Entities |
|------------|----------|
| `world.entities`, `world.livingEntities` | all loaded entities of a world |
| `chunk.entities` | the entities of a chunk |
| `location.nearbyEntities(radius)`, `entity.nearbyEntities(radius)` | within a distance |
| `player.targetEntity(20)` | what the player looks at, up to 20 blocks |
| `server.player(...)` | players, see [Players](Players) |

```tys
@playerOnly
command glow(radius: double = 16) {
    var count = 0
    for nearby in player.nearbyEntities(radius) {
        if nearby is Monster {
            nearby.glowing = true
            count++
        }
    }
    player.send("<yellow>{count} monsters are now glowing.")
    after 10 seconds for player {
        for nearby in player.nearbyEntities(radius) {
            nearby.glowing = false
        }
    }
}
```

## Remembering entities

Entity values are only valid while the entity exists. To find an entity again later (after it
unloaded, or after a restart), remember its `uuid` — or mark it with a [tag](Custom-Data-Tags)
and look for the tag. `entity.valid` tells whether an entity value still refers to an entity in
the world. `entity.id` is a number that is only unique until the server restarts.

## Bounding boxes

`entity.boundingBox` and `block.boundingBox` return copies in world coordinates.
`BoundingBox(x1, y1, z1, x2, y2, z2)` normalizes opposite corners. Its `min`, `max` and
`center` are new vectors; `width`, `height`, `depth` and `volume` describe its size.

```tys-body
let box = BoundingBox(0.0, 0.0, 0.0, 2.0, 2.0, 2.0)
let larger = box.expand(1.0)
let shifted = box.shift(Vector(4.0, 0.0, 0.0))
log("Inside: {box.contains(Vector(1.0, 1.0, 1.0))}; larger volume: {larger.volume}")
log("Overlap: {box.overlaps(shifted)}; enclosing volume: {box.union(shifted).volume}")
```

`expand`, `shift`, `union` and `copy` return new boxes, leaving the original unchanged.
Point containment includes minimum faces and excludes maximum faces. `overlaps` requires
positive extent; touching faces alone are not overlap. Coordinates do not identify a world:
check that locations belong to the same world before using their boxes together.

## Threads (Folia)

On Folia each region of the world has its own thread. Reading any entity is always allowed.
Changes to an entity owned by another region are handed to that region and applied on its next
tick. Operations that create entities (`spawn`, `dropItem`, `launchProjectile`) must run on the
thread that owns the location: from another region's thread they report an error — run them in
`after 1 tick for <entity>` or from an event of that region. On Paper none of this matters.
See [Performance and Folia](Performance-and-Folia).

## Next

* [Players](Players)
* [Custom data tags](Custom-Data-Tags) — remember things about an entity
* [Events](Events) — `entity.*` events
