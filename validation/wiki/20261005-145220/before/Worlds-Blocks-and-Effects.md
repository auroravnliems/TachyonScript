# Worlds, blocks and effects

## Locations

A `Location` is a position in a world: `x`, `y`, `z`, plus a rotation (`yaw`, `pitch`).

```tys-body
let here = player.location                          // a copy of the player's location
let spawn = Location(server.defaultWorld, 0.5, 64, 0.5)
let above = here.add(0, 2, 0)                       // 2 blocks higher
let ahead = here.forward(5)                         // 5 blocks in the direction the player looks
player.send("You are {here.distance(spawn)} blocks from spawn, at {here.blockX}, {here.blockY}, {here.blockZ}.")
player.teleport(ahead.withY(ahead.highestBlock.y + 1.0))
```

Locations are **values**: `add`, `subtract`, `forward`, `withX`/`withY`/`withZ`,
`withYaw`/`withPitch`, `withWorld`, `center`, `blockLocation` and `lookAt` return a new location
and leave the original alone.

| Member | Meaning |
|--------|---------|
| `x`, `y`, `z`, `yaw`, `pitch`, `world` | coordinates, rotation and world (`World?`: `null` if the world is not loaded) |
| `blockX`, `blockY`, `blockZ` | whole block coordinates |
| `block`, `chunk`, `biome`, `lightLevel` | what is at the location |
| `add(x, y, z)`, `add(vector)`, `subtract(...)` | moved copies |
| `forward(distance)`, `direction` | along / the direction of the rotation |
| `center`, `blockLocation` | the center / corner of the block |
| `lookAt(target)` | a copy turned to face another location |
| `distance(other)`, `distanceSquared(other)` | distances (same world) |
| `highestBlock` | the highest non-air block of the column |
| `isSafe` | two free blocks and solid ground: a player can stand here |
| `isChunkLoaded` | whether its chunk is loaded |
| `nearbyEntities(r)`, `nearbyPlayers(r)` | what is around |
| `blocksInRadius(r)` | the blocks of a sphere (radius up to 16) |
| `toVector()` | the coordinates as a `Vector` |

`location(world, x, y, z)` is another way to write `Location(world, x, y, z)`.

## Blocks

```tys
event block.break {
    if block.type == Material.DIAMOND_ORE || block.type == Material.DEEPSLATE_DIAMOND_ORE {
        broadcast("<aqua>{player.name} found diamonds at {block.y}!")
    }
}

@playerOnly
command pillar(height: int = 5) {
    let base = player.location.block
    for i in 1..math.clamp(height, 1, 50) {
        base.relative(0, i - 1, 0).type = Material.QUARTZ_PILLAR
    }
    player.teleport(base.relative(0, height, 0).center)
}
```

| Member | Meaning |
|--------|---------|
| `type` | the material (changeable: `block.type = Material.STONE`) |
| `blockData` | the full state, e.g. `minecraft:oak_stairs[facing=north,half=bottom]` (changeable) |
| `x`, `y`, `z`, `location`, `center`, `world`, `chunk` | where it is |
| `relative(BlockFace.UP)`, `relative(dx, dy, dz)` | neighbours |
| `isEmpty`, `isSolid`, `isLiquid`, `isPassable` | what kind of block |
| `isPowered`, `redstonePower` | redstone |
| `lightLevel`, `skyLight`, `biome` | environment (`biome` is changeable) |
| `hardness`, `drops`, `dropsWith(tool)` | breaking |
| `breakNaturally()`, `breakNaturally(tool)` | breaks it and drops its items |
| `inventory` | the inventory of a container (chest, barrel, furnace, ...), or `null` |
| `signLine(i)`, `setSignLine(i, text)` | the front lines 0–3 of a sign |
| `tag(key)`, `setTag(key, value)`, ... | hidden data for any block (see [Custom data tags](Custom-Data-Tags)) |

Block faces: `BlockFace.NORTH`, `EAST`, `SOUTH`, `WEST`, `UP`, `DOWN` (and diagonal ones);
`face.opposite` is the other side.

### Block states

`blockData` reads and writes everything about a block, in the same format as the `/setblock`
command:

```tys-body
let block = player.location.block
block.blockData = "minecraft:oak_stairs[facing=east,half=top]"
player.send("That block is {block.blockData}")
```

An invalid state is a runtime error (`Invalid block data '...'.`).

### Signs

```tys
@playerOnly
command signinfo {
    let target = player.targetBlock(5)
    let line = target?.signLine(0)
    if target == null || line == null {
        player.send("<red>Look at a sign.")
        return
    }
    target.setSignLine(3, "<gray>Checked by {player.name}")
}
```

### Many blocks at once

```tys
@playerOnly
@permission("server.build")
command platform(size: int = 5) {
    let center = player.location.subtract(0, 1, 0)
    let r = math.clamp(size, 1, 30)
    let changed = player.world.fill(center.add(-r, 0, -r), center.add(r, 0, r), Material.GLASS)
    player.send("<green>Placed {changed} blocks.")
}

@playerOnly
@permission("server.build")
command melt(radius: int = 5) {
    var count = 0
    for block in player.location.blocksInRadius(radius) {
        if block.type == Material.SNOW || block.type == Material.ICE {
            block.type = Material.AIR
            count++
        }
    }
    player.send("<green>Melted {count} blocks.")
}
```

`world.fill(from, to, material)` changes at most 32,768 blocks per call; `blocksInRadius` takes
a radius up to 16. For larger areas, split the work over several ticks with `every` so the
server keeps running smoothly.

## Worlds

```tys
@permission("server.world")
command day {
    for world in server.worlds {
        world.time = 1000
        world.storm = false
        world.thundering = false
    }
    broadcast("<yellow>Good morning!")
}
```

| Member | Meaning |
|--------|---------|
| `name`, `environment`, `seed` | `Environment.NORMAL`, `NETHER`, `THE_END` |
| `time` (0–24000), `fullTime`, `isDay`, `isNight` | time of day (changeable) |
| `storm`, `thundering`, `weatherDuration`, `setClearWeather(duration)` | weather |
| `difficulty`, `pvp` | changeable |
| `spawnLocation` | the world spawn (changeable) |
| `gameRule(name)`, `setGameRule(name, value)` | game rules as text: `setGameRule("keepInventory", "true")` |
| `borderCenter`, `borderSize` | the world border |
| `players`, `entities`, `livingEntities` | who is in it |
| `blockAt(x, y, z)`, `highestBlockAt(x, z)`, `chunkAt(x, z)`, `isChunkLoaded(x, z)` | lookups |
| `minHeight`, `maxHeight`, `seaLevel` | heights |
| `spawn(type, location)`, `dropItem(location, item)`, `dropItemNaturally(location, item)` | create entities |
| `createExplosion(location, power)`, `createExplosion(location, power, fire, breakBlocks)` | explosions |
| `strikeLightning(location)`, `strikeLightningEffect(location)` | lightning (with / without damage) |
| `fill(from, to, material)` | fill a box |
| `save()` | save to disk |
| `tag(key)`, `setTag(key, text)`, `removeTag(key)` | text data stored in the world |

`server.world("name")` finds a loaded world (or `null`), `server.worlds` lists them,
`server.defaultWorld` is the main world. `server.createWorld("arena")` loads a world, creating
it if needed (`server.createWorld("arena_nether", Environment.NETHER)` for another kind), and
`server.unloadWorld(world, true)` unloads it. Folia cannot create or unload worlds while it runs.

## Chunks

A `Chunk` is a 16×16 column of blocks: `x`, `z` (chunk coordinates), `world`, `isLoaded`,
`entities`, `forceLoaded` (keep it loaded without players) and text `tag`s.

```tys
@playerOnly
@permission("server.chunks")
command keeploaded {
    let chunk = player.location.chunk
    chunk.forceLoaded = !chunk.forceLoaded
    player.send("<gray>Chunk {chunk} stays loaded: {chunk.forceLoaded}")
}
```

## Vectors

A `Vector` is a direction or a speed: `Vector(x, y, z)`, `entity.velocity`, `location.direction`.
Operations return new vectors: `add`, `subtract`, `multiply(factor)`, `normalize()`,
`rotateAroundX/Y/Z(radians)`, `cross`, `dot`, `angle`, `length`, `distance`, `toLocation(world)`.

```tys
event player.toggleSneak {
    if sneaking && player.hasPermission("server.leap") && player.onGround {
        player.velocity = player.direction.multiply(1.5).add(Vector(0, 0.6, 0))
        player.playSound(Sound.ENTITY_BREEZE_JUMP)
    }
}
```

## Particles

```tys-body
let at = player.location.add(0, 1, 0)
at.spawnParticle(Particle.HEART, 5)                                    // at the location
at.spawnParticle(Particle.FLAME, 40, 0.5, 0.5, 0.5, 0.02)              // spread out, with speed
at.spawnDust(Color.fromHex("#ff8800") ?? Color.ORANGE, 1.5, 20)        // colored dust
player.spawnParticle(Particle.HAPPY_VILLAGER, at, 10)                  // only this player sees it
```

`spawnParticle(particle, count, offsetX, offsetY, offsetZ, speed)` spreads the particles in a
box of the given size. Counts go up to 10,000. Particles that need extra data (a color, a block
or an item) cannot be shown with `spawnParticle`; use `spawnDust` for colored dust. Every
particle of the server is available: `Particle.FLAME`, `HEART`, `END_ROD`, `TOTEM_OF_UNDYING`,
`CLOUD`, `SOUL_FIRE_FLAME`, `ENCHANT`, `PORTAL`, `EXPLOSION`, `NOTE`, ...

A circle of particles around a player:

```tys
@playerOnly
command halo {
    every 2 ticks for player {
        if task.runs > 100 {
            task.cancel()
            return
        }
        let angle = task.runs * 0.3
        let spot = player.location.add(math.cos(angle) * 0.8, 2.2, math.sin(angle) * 0.8)
        spot.spawnParticle(Particle.END_ROD, 1, 0, 0, 0, 0)
    }
}
```

## Sounds

| Call | Who hears it |
|------|--------------|
| `location.playSound(sound)`, `location.playSound(sound, volume, pitch)` | everyone nearby |
| `player.playSound(sound)`, `player.playSound(sound, volume, pitch)` | only this player |
| `player.playSound(sound, location, volume, pitch)` | only this player, from a location |
| `player.stopSound(sound)`, `player.stopAllSounds()` | stop sounds |

Pitch goes from 0.5 (low) to 2 (high). Every sound of the server is a constant:
`Sound.ENTITY_PLAYER_LEVELUP`, `Sound.BLOCK_NOTE_BLOCK_PLING`, `Sound.UI_TOAST_CHALLENGE_COMPLETE`,
... (more than 1,800; the compiler suggests the right name when you mistype one).

## Explosions and lightning

```tys
event projectile.hit {
    if projectile.type == EntityType.SNOWBALL && projectile.tag("bomb") == "yes" {
        projectile.location.createExplosion(2.0)
    }
}

@playerOnly
@permission("server.bombs")
command bomb {
    let ball = player.launchProjectile(EntityType.SNOWBALL)
    ball.setTag("bomb", "yes")
}

@permission("server.smite")
command smite(target: Player) {
    target.world.strikeLightning(target.location)
}
```

`location.createExplosion(power)` explodes like TNT (TNT has power 4);
`world.createExplosion(location, power, fire, breakBlocks)` chooses whether it sets fires and
breaks blocks.

## Threads (Folia)

On Folia, blocks and entities belong to the region that owns their chunk. Reading is always
allowed; changes from another region's thread are handed to the owner and applied on its next
tick. `world.fill` and other operations that change many blocks run in the region of their first
corner — keep them inside one area. On Paper nothing of this matters.

## Next

* [Entities](Entities)
* [Custom data tags](Custom-Data-Tags) — data stored in blocks, chunks and worlds
* [Events](Events) — `block.*`, `world.*` and `chunk.*` events
