# Players

A `Player` is an online player. It has everything a [living entity](Entities) has (health,
potion effects, equipment, location...), everything a command sender has (`send`,
`hasPermission`...) and everything an offline player has (`uuid`, `firstPlayed`, bans...), plus
the members on this page. The complete list is in the [API reference](API-Reference).

## Finding players

| Expression | Result |
|------------|--------|
| `player` | in player events and commands: the player concerned |
| `server.players` | every online player (a snapshot list) |
| `server.player("Steve")` | the online player with this exact name, or `null` |
| `server.player(uuid)` | the online player with this UUID, or `null` |
| `world.players` | the players in a world |
| `location.nearbyPlayers(10)`, `player.nearbyPlayers(10)` | players within 10 blocks |
| `server.offlinePlayer("Steve")`, `server.offlinePlayer(uuid)` | an [offline player](#offline-players) |

```tys
@permission("server.staff")
command find(name: string) {
    let target = server.player(name)
    if target == null {
        sender.send("<red>{name} is not online.")
        return
    }
    let at = target.location
    sender.send("<gray>{target.name} is in {target.world.name} at {at.blockX}, {at.blockY}, {at.blockZ}.")
}
```

## Who the player is

| Member | Meaning |
|--------|---------|
| `name`, `uuid` | the name and the unique id (use the UUID to remember players; names can change) |
| `displayName` | the name shown in chat (changeable, MiniMessage) |
| `playerListName` | the name shown in the tab list (changeable) |
| `locale` | the language of the client, e.g. `en_us`, `vi_vn` |
| `clientBrand` | `vanilla`, `fabric`, ... when the client sent it |
| `address` | the IP address |
| `ping` | the latency in milliseconds |
| `op`, `setOp(bool)` | operator status |
| `firstPlayed`, `lastLogin`, `lastSeen`, `playedBefore` | when they first joined, last joined, were last online |

```tys
event player.join {
    if !player.playedBefore {
        broadcast("<light_purple>Welcome {player.name} to the server for the first time!")
    }
    if player.locale.startsWith("vi") {
        player.send("<green>Chào mừng bạn!")
    } else {
        player.send("<green>Welcome!")
    }
}
```

## Health, food and experience

| Member | Meaning |
|--------|---------|
| `health`, `maxHealth` | current and maximum health (20 = 10 hearts); both changeable |
| `heal(amount)`, `damage(amount)`, `kill()` | heal, hurt (armor applies), kill |
| `absorption` | extra golden hearts |
| `food` (0–20), `saturation`, `exhaustion` | hunger |
| `level`, `exp` (0–1 progress), `totalExperience` | experience |
| `giveExp(points)`, `giveLevels(levels)`, `expToLevel` | give experience; points needed for the next level |
| `fireTicks`, `freezeTicks`, `remainingAir`, `maximumAir` | burning, freezing, air under water |
| `invulnerable`, `noDamageTicks` | no damage at all / short invulnerability after a hit |

```tys
@permission("server.heal")
command heal(target: Player? = null) {
    let who = target ?? player
    if who == null {
        sender.send("<red>Name a player.")
        return
    }
    who.health = who.maxHealth
    who.food = 20
    who.saturation = 20.0
    who.fireTicks = 0
    who.clearPotionEffects()
    who.send("<green>You have been healed.")
}
```

## Game mode, flight and movement

| Member | Meaning |
|--------|---------|
| `gameMode` | `GameMode.SURVIVAL`, `CREATIVE`, `ADVENTURE`, `SPECTATOR` (changeable) |
| `allowFlight`, `flying` | whether the player may fly / is flying |
| `walkSpeed` (0.2 normal), `flySpeed` (0.1 normal) | from -1 to 1 |
| `sneaking`, `sprinting`, `gliding`, `swimming`, `climbing`, `sleeping`, `onGround` | what the player is doing |
| `location`, `eyeLocation`, `direction`, `velocity` | where they are and move |
| `teleport(location)`, `teleport(entity)` | teleports |
| `compassTarget`, `respawnLocation` | where the compass points; where they respawn (`null` for world spawn) |

```tys
@playerOnly
@permission("server.fly")
command fly(speed: double = 0.1) {
    player.allowFlight = !player.allowFlight
    player.flySpeed = math.clamp(speed, 0.0, 1.0)
    player.send(player.allowFlight ? "<green>You can fly." : "<red>You can no longer fly.")
}

@permission("server.gamemode")
command gm(mode: GameMode, target: Player? = null) {
    let who = target ?? player
    if who == null {
        sender.send("<red>Name a player.")
        return
    }
    who.gameMode = mode
    sender.send("<green>{who.name} is now in {mode} mode.")
}
```

## Inventory and items

| Member | Meaning |
|--------|---------|
| `inventory` | the player's inventory (`PlayerInventory`, see [Items and inventories](Items-and-Inventories)) |
| `give(item)`, `give(material, amount)` | gives items; what does not fit drops at the player's feet |
| `mainHand`, `offHand` | the items in the hands (changeable; `null` for empty) |
| `helmet`, `chestplate`, `leggings`, `boots` | armor (changeable) |
| `enderChest`, `openEnderChest()` | the ender chest |
| `openInventory(inventory)`, `openWorkbench()`, `closeInventory()` | open and close screens |
| `openedInventory`, `cursor` | what the player looks at; the item on the cursor |
| `setCooldown(material, duration)`, `cooldown(material)`, `hasCooldown(material)` | item cooldowns (like ender pearls) |
| `updateInventory()` | re-sends the inventory to the client |

```tys
@playerOnly
command hat {
    let hand = player.mainHand
    if hand == null {
        player.send("<red>Hold the block you want to wear.")
        return
    }
    let old = player.helmet
    player.helmet = hand
    player.mainHand = old
    player.send("<green>Nice hat!")
}
```

## Potion effects and attributes

```tys-body
player.addPotionEffect(PotionEffectType.NIGHT_VISION, 10 minutes, 1)
player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 30 seconds, 2, true, false))  // ambient, no particles
if player.hasPotionEffect(PotionEffectType.POISON) {
    player.removePotionEffect(PotionEffectType.POISON)
}
player.setAttributeBase(Attribute.SCALE, 1.5)            // 50% bigger
player.setAttributeBase(Attribute.JUMP_STRENGTH, 0.6)
player.send("Attack damage: {player.attribute(Attribute.ATTACK_DAMAGE)}")
```

Level `1` is the normal strength of an effect (Speed I); a negative duration lasts forever.
`potionEffects` lists the active effects and `potionEffect(type)` returns one (or `null`).
Attributes include `MAX_HEALTH`, `MOVEMENT_SPEED`, `ATTACK_DAMAGE`, `ATTACK_SPEED`, `ARMOR`,
`SCALE`, `JUMP_STRENGTH`, `GRAVITY`, `STEP_HEIGHT`, `BLOCK_INTERACTION_RANGE`,
`ENTITY_INTERACTION_RANGE`, `SAFE_FALL_DISTANCE` and more — every attribute of the server.

## What only this player sees

| Member | Meaning |
|--------|---------|
| `playSound(sound)`, `playSound(sound, volume, pitch)`, `playSound(sound, location, volume, pitch)` | a sound only this player hears |
| `stopSound(sound)`, `stopAllSounds()` | stop sounds |
| `spawnParticle(particle, location, count)` | particles only this player sees |
| `sendBlockChange(location, material)` | a fake block (until the block updates) |
| `setPlayerTime(time, relative)`, `resetPlayerTime()` | a personal time of day |
| `setPlayerWeather(raining)`, `resetPlayerWeather()` | personal weather |
| `hidePlayer(other)`, `showPlayer(other)`, `canSee(other)` | hide other players from this one |
| `title(...)`, `actionBar(...)`, `tabHeader`, `tabFooter` | see [Titles, boss bars and sidebars](Titles-Boss-Bars-and-Sidebars) |

A simple vanish:

```tys
var vanished: List<UUID> = []

@playerOnly
@permission("server.vanish")
command vanish {
    if player.uuid in vanished {
        vanished.remove(player.uuid)
        for other in server.players {
            other.showPlayer(player)
        }
        player.send("<gray>You are visible again.")
    } else {
        vanished.add(player.uuid)
        for other in server.players {
            if !other.hasPermission("server.vanish.see") {
                other.hidePlayer(player)
            }
        }
        player.send("<gray>You are now invisible to players.")
    }
}

event player.join {
    for id in vanished {
        let hidden = server.player(id)
        if hidden != null && !player.hasPermission("server.vanish.see") {
            player.hidePlayer(hidden)
        }
    }
}
```

## Permissions

`player.hasPermission("node")` checks a permission (from your permission plugin, or operator
status). `player.addPermission("node")` gives a permission **temporarily**: until the player
leaves or the script reloads. For permanent permissions use your permission plugin, for example
with `server.dispatch("lp user {player.name} permission set node")`.

```tys
@playerOnly
command trial {
    player.addPermission("server.fly")
    player.send("<green>You can use /fly for 10 minutes.")
    after 10 minutes for player {
        player.removePermission("server.fly")
        player.allowFlight = false
        player.send("<gray>Your trial has ended.")
    }
}
```

## Making the player act

| Member | Meaning |
|--------|---------|
| `performCommand("spawn")` | runs a command as the player (without `/`) |
| `chat("hello")` | sends a chat message (or a command if it starts with `/`) as the player |
| `swingMainHand()` | plays the arm swing animation |
| `kick(reason)` | disconnects the player with a message |

## Statistics

`Statistic` identifies vanilla counters. Match the overload to `statistic.parameterType`:
`UNTYPED` takes no extra key, `ITEM`/`BLOCK` takes a `Material`, and `ENTITY` takes an
`EntityType`. A mismatched key is a runtime error.

```tys
@playerOnly
command mystats {
    let jumps = player.statistic(Statistic.JUMP)
    let stone = player.statistic(Statistic.MINE_BLOCK, Material.STONE)
    let zombies = player.statistic(Statistic.KILL_ENTITY, EntityType.ZOMBIE)
    player.send("<gold>Jumps: {jumps}; stone mined: {stone}; zombies killed: {zombies}.")
}
```

For each overload there are `setStatistic(statistic, [key,] value)`,
`incrementStatistic(statistic, [key,] amount)` and `decrementStatistic(...)` variants.
Set values must be nonnegative; increment/decrement amounts must be positive, and counters
cannot be reduced below zero. Check the exact signatures in the [API reference](API-Reference).
These APIs currently belong to online `Player`; offline statistic access is not implemented.

## Offline players

An `OfflinePlayer` is any player the server knows, online or not:
`server.offlinePlayer("Notch")`, `server.offlinePlayer(uuid)`, a command parameter of type
`OfflinePlayer`, `server.bannedPlayers`, `server.operators`, `server.whitelistedPlayers`.

| Member | Meaning |
|--------|---------|
| `name` | the last known name (`string?`: `null` if the server never saw them) |
| `uuid` | the unique id |
| `online`, `player` | whether they are online; the online `Player` or `null` |
| `playedBefore`, `firstPlayed`, `lastLogin`, `lastSeen` | history (`Instant`s; 0 if never) |
| `lastLocation` | where they were when they left |
| `banned`, `ban(reason)`, `ban(reason, duration)`, `pardon()` | bans (and kicks when online) |
| `whitelisted` | on the whitelist (changeable) |
| `isOp()`, `setOp(bool)` | operator status |

[`playerdata var`](Saving-Data) values work on offline players too: `target.coins += 10`.

```tys
@permission("server.tempban")
command tempban(target: OfflinePlayer, duration: Duration, reason: string...) {
    target.ban(reason, duration)
    broadcast("<red>{target.name ?? "?"} was banned for {format.duration(duration)}: {reason}")
}

@permission("server.seen")
command seen(target: OfflinePlayer) {
    if target.online {
        sender.send("<green>{target.name ?? "?"} is online now.")
    } else if !target.playedBefore {
        sender.send("<red>That player has never joined.")
    } else {
        sender.send("<gray>{target.name ?? "?"} was last seen {format.duration(time.since(target.lastSeen))} ago.")
    }
}
```

## Next

* [Entities](Entities) — mobs, items on the ground, projectiles, armor stands
* [Items and inventories](Items-and-Inventories)
* [Saving data](Saving-Data) — values per player
