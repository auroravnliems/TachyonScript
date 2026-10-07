# Coming from Skript

TachyonScript covers common server scripting tasks — events, commands, variables, GUIs,
timers and databases — with its own compiler and runtime and syntax closer to Java,
Kotlin or JavaScript. This guide maps concepts, not every Skript feature. The big differences:

* **Scripts are checked when they load.** Typos, wrong types, missing arguments and unchecked
  `null` values are errors with the exact line and a suggestion — before anything runs. A script
  with errors never replaces one that works.
* **Values have types.** A variable holds an `int`, a `Player`, a `List<ItemStack>`... The
  compiler knows what each value can do, and suggests members.
* **Work is resolved at load time.** Scripts compile to register code and messages are
  pre-parsed; see [Performance and Folia](Performance-and-Folia) for measurement limits.
* **Everything is built in.** GUIs, databases, boss bars, sidebars, holograms, web requests,
  JSON, files, custom item data, Vault and PlaceholderAPI need no addon.

## Migration scope

The 0.5.1 source audit records decisions for 29 of 941 Skript classes, with 912 still
unreviewed. Of those reviewed, 12 are implemented, 6 adapted, 6 already covered, 1 partial
and 4 missing. This is not a claim of full language or API parity.

This update adds statistics, transient metadata, typed block states, sign sides, display
transforms and bounding boxes. Loot/AnvilView APIs and offline statistics remain gaps.
Consult the [API reference](API-Reference) for exact signatures; TachyonScript does not
execute `.sk` files or replace its engine with Skript's.

Metadata is shared temporary TachyonScript data, cleaned up when the latest writer stops;
it is not Bukkit/Skript metadata or saved data. Menu clicks use `MenuClick`; generic
inventory click/drag events do not run anywhere inside a Menu view. See
[Custom data tags](Custom-Data-Tags#temporary-metadata) and [Menus](Menus).

## Side by side

### Events

| Skript | TachyonScript |
|--------|---------------|
| `on join:` | `event player.join { ... }` |
| `on first join:` | `event player.join { if !player.playedBefore { ... } }` |
| `on quit:` | `event player.quit { ... }` |
| `on chat:` | `event player.chat { ... }` (`message` is the text) |
| `on death of player:` (`victim`, `attacker`) | `event player.death { ... }` (`victim`, `killer`) |
| `on damage:` | `event entity.damage { ... }` or `event entity.damageByEntity { ... }` (`attacker`) |
| `on break of diamond ore:` | `event block.break { if block.type == Material.DIAMOND_ORE { ... } }` |
| `on place:` | `event block.place { ... }` |
| `on right click with a blaze rod:` | `event player.interact { if event.rightClick && item?.type == Material.BLAZE_ROD { ... } }` |
| `on inventory click:` | `event player.inventoryClick { ... }` — or a [Menu](Menus) with click functions |
| `on respawn:` | `event player.respawn { ... }` |
| `on load:` / `on unload:` | `on load { ... }` / `on unload { ... }` |
| `cancel event` | `event.cancel()` |
| `set join message to "..."` | `event.joinMessage = "..."` |
| `set death message to "..."` | `event.deathMessage = "..."` |
| `set chat format to ...` | `event.cancel()` then `broadcast("...")`, see [Messages and text](Messages-and-Text#formatting-the-chat) |

All 111 events are listed on the [Events](Events#all-events) page.

### Text

| Skript | TachyonScript |
|--------|---------------|
| `send "Hi %player%" to player` | `player.send("Hi {player.name}")` |
| `message "Hi"` (in a command) | `sender.send("Hi")` |
| `broadcast "..."` | `broadcast("...")` |
| `&a`, `&l`, `<##ff8800>` colors | MiniMessage: `<green>`, `<bold>`, `<#ff8800>`; old codes with `text.legacy("&aHi")` |
| `%player's balance%` | `{economy.balance(player)}` |
| `send title "A" with subtitle "B" to player` | `player.title("A", "B")` |
| `send action bar "..." to player` | `player.actionBar("...")` |
| `set tab header to "..." and footer to "..." for player` | `player.tabHeader = "..."`, `player.tabFooter = "..."` |
| `options:` `prefix: &6[Server]` ... `{@prefix}` | `const PREFIX = "<gold>[Server]"` ... `{PREFIX}` |

Values inserted with `{...}` are always plain text: a player cannot inject colors or click
actions through their name or a chat message.

### Variables

| Skript | TachyonScript |
|--------|---------------|
| `set {_x} to 5` (local) | `let x = 5` (fixed) or `var x = 5` (changeable) |
| `set {x} to 5` (global, saved) | `persistent var x: int = 5` |
| `set {coins::%uuid of player%} to 100` | `playerdata var coins: int = 100` then `player.coins = 100` |
| `add 1 to {x}` / `remove 1 from {x}` | `x += 1` / `x -= 1` (or `x++`) |
| `delete {x}` | `x = null` (for a `T?` variable) |
| `if {_x} is set:` | `if x != null { ... }` |
| `{homes::%uuid of player%::%arg%}` | `playerdata var homes: Map<string, Location> = {}` then `player.homes[name]` |
| `add player to {_list::*}` | `list.add(player)` |
| `size of {_list::*}` | `list.size` |
| `loop {_list::*}:` / `loop-value` | `for value in list { ... }` |
| `loop {_map::*}:` / `loop-index` | `for key, value in map { ... }` |
| `random element of {_list::*}` | `list.random()` |
| `sorted {_list::*}` | `list.sorted()` / `list.sortedBy(x => ...)` |

Saved variables are loaded and saved automatically (SQLite by default, MySQL for networks) and
a player's data is loaded before they join. See [Saving data](Saving-Data).

### Conditions and loops

| Skript | TachyonScript |
|--------|---------------|
| `if {_x} is greater than 5:` | `if x > 5 { ... }` |
| `if player has permission "a.b":` | `if player.hasPermission("a.b") { ... }` |
| `else if ...:` / `else:` | `} else if ... {` / `} else {` |
| `chance of 20%:` | `if random.chance(20) { ... }` |
| `loop all players:` / `loop-player` | `for p in server.players { ... }` |
| `loop 10 times:` / `loop-number` | `for i in 1..10 { ... }` |
| `loop blocks in radius 5 around player:` | `for b in player.location.blocksInRadius(5) { ... }` |
| `loop all entities in radius 10 around player:` | `for e in player.nearbyEntities(10) { ... }` |
| `while ...:` | `while ... { ... }` |
| `stop` / `exit trigger` | `return` |
| `exit loop` | `break` |
| `continue` | `continue` |

### Timing

| Skript | TachyonScript |
|--------|---------------|
| `wait 5 seconds` then more code | `after 5 seconds { more code }` |
| `wait 1 tick` | `after 1 tick { ... }` |
| `every 5 minutes:` | `every 5 minutes { ... }` (top level of the file) |
| `every 5 minutes in world "world":` | `every 5 minutes { ... }` with a check of the world |
| `at 12:00 in world "world":` (Minecraft time) | `every` with `world.time`, or `at "12:00" { }` for **real** time |
| `now`, `difference between {x} and now` | `time.now`, `time.since(x)` |

`wait` in Skript pauses the code; `after` in TachyonScript schedules a block and continues at
once. Put everything that should happen later inside the block. See
[Timers and tasks](Timers-and-Tasks).

### Commands

```text
command /heal [<player>]:
    permission: server.heal
    cooldown: 30 seconds
    trigger:
        if arg-1 is set:
            heal arg-1
        else:
            heal player
```

```tys
@permission("server.heal")
@cooldown(30 seconds)
command heal(target: Player? = null) {
    let who = target ?? player
    if who == null {
        sender.send("<red>Name a player.")
        return
    }
    who.health = who.maxHealth
}
```

| Skript | TachyonScript |
|--------|---------------|
| `command /x <text>:` | `command x(value: string) { ... }` — `string...` for the rest of the line |
| `[<player>]` (optional) | `target: Player? = null` or `amount: int = 1` |
| `arg-1`, `arg-2` | the parameter names |
| `permission:` / `permission message:` | `@permission("...")` / `@permissionMessage("...")` |
| `cooldown:` / `cooldown message:` | `@cooldown(...)` / `@cooldownMessage("...")` |
| `executable by: players` | `@playerOnly` |
| `aliases:` | `@aliases("a", "b")` |
| `usage:` / `description:` | `@usage("...")` / `@description("...")` |
| `command /warp set <text>` | `command warp.set(name: string)` (a sub-command) |

Arguments are converted and checked before your code runs: `/pay Steve lots` answers
`'lots' is not a number.` by itself. See [Commands](Commands).

### Players, items and the world

| Skript | TachyonScript |
|--------|---------------|
| `give player 5 diamonds` | `player.give(Material.DIAMOND, 5)` |
| `remove 5 diamonds from player's inventory` | `player.inventory.remove(Material.DIAMOND, 5)` |
| `player has 5 diamonds` | `player.inventory.contains(Material.DIAMOND, 5)` |
| `set player's health to 10` (**hearts**) | `player.health = 20.0` (**health points**, like Minecraft: 20 = 10 hearts) |
| `heal player` / `feed player` | `player.health = player.maxHealth` / `player.food = 20` |
| `set player's gamemode to creative` | `player.gameMode = GameMode.CREATIVE` |
| `teleport player to spawn of world "world"` | `player.teleport(server.world("world")?.spawnLocation ?? player.location)` |
| `play sound "entity.player.levelup" to player` | `player.playSound(Sound.ENTITY_PLAYER_LEVELUP)` |
| `apply speed 2 to player for 30 seconds` | `player.addPotionEffect(PotionEffectType.SPEED, 30 seconds, 2)` |
| `set block at player to stone` | `player.location.block.type = Material.STONE` |
| `spawn a zombie at player` | `player.location.spawn(EntityType.ZOMBIE)` |
| `name of player's tool` | `player.mainHand?.name` |
| `set name of player's tool to "..."` | `let tool = player.mainHand` ... `tool.name = "..."` ... `player.mainHand = tool` |
| `execute console command "..."` | `server.dispatch("...")` |
| `make player execute command "/spawn"` | `player.performCommand("spawn")` |
| `kick player due to "..."` | `player.kick("...")` |
| `ban player due to "..."` | `player.ban("...")` |
| `random integer between 1 and 10` | `random.int(1, 10)` |
| `player's balance` / `add 10 to player's balance` | `economy.balance(player)` / `economy.deposit(player, 10)` |

Constants such as `Material.DIAMOND` are checked when the script loads: `Material.DIAMOND_SWROD`
is an error with the suggestion `DIAMOND_SWORD`.

### Functions

```text
function reward(p: player, amount: number = 10):
    add {_amount} to {coins::%uuid of {_p}%}
    send "+%{_amount}% coins" to {_p}
```

```tys
playerdata var coins: int = 0

function reward(p: Player, amount: int = 10) {
    p.coins += amount
    p.send("+{amount} coins")
}
```

Functions can return values (`function price(item: Material): int { return ... }`), call
themselves, and be passed around as values. See [Functions and lambdas](Functions-and-Lambdas).

## Addons you no longer need

| Skript addon | Built into TachyonScript |
|--------------|--------------------------|
| skript-gui, TuSKe (GUIs) | [Menus](Menus) |
| skript-db (SQL) | [Databases](Databases) — with protection against SQL injection |
| SkBee (NBT, boss bars, scoreboards, text displays, ...) | [Custom data tags](Custom-Data-Tags), [boss bars, sidebars, holograms](Titles-Boss-Bars-and-Sidebars) |
| skript-yaml, SkQuery files | [files and JSON](Files-Web-and-JSON), [saved variables](Saving-Data) |
| Reqn and web addons | [web requests](Files-Web-and-JSON#web-requests) |
| skript-placeholders | [`placeholder` declarations](Vault-and-PlaceholderAPI) and `papi.parse` |
| skript-reflect | [addons in Java](Writing-Addons) with checked declarations |

## A complete conversion

A small Skript:

```text
options:
    prefix: &6[Kills]&r

on death of player:
    attacker is a player
    add 1 to {kills::%uuid of attacker%}
    send "{@prefix} &aYou now have %{kills::%uuid of attacker%}% kills." to attacker
    if {kills::%uuid of attacker%} is 10:
        broadcast "{@prefix} &e%attacker% reached 10 kills!"
        give attacker 1 diamond

command /kills [<offlineplayer>]:
    trigger:
        set {_p} to arg-1 ? player
        send "{@prefix} &7%{_p}% has %{kills::%uuid of {_p}%} ? 0% kills."
```

The same in TachyonScript:

```tys
const PREFIX = "<gold>[Kills]</gold> "

playerdata var kills: int = 0

event player.death {
    if killer == null {
        return
    }
    killer.kills += 1
    killer.send("{PREFIX}<green>You now have {killer.kills} kills.")
    if killer.kills == 10 {
        broadcast("{PREFIX}<yellow>{killer.name} reached 10 kills!")
        killer.give(Material.DIAMOND, 1)
    }
}

command kills(target: OfflinePlayer? = null) {
    let who: OfflinePlayer? = target ?? player
    if who == null {
        sender.send("{PREFIX}<red>Name a player.")
        return
    }
    sender.send("{PREFIX}<gray>{who.name ?? "?"} has {who.kills} kills.")
}
```

What changed:

* `{kills::%uuid of attacker%}` became `playerdata var kills` — typed, saved automatically, and
  read as `killer.kills`.
* `attacker is a player` became `if killer == null { return }`: `killer` is a `Player?`, so the
  compiler insists on the check.
* The option became a constant; colors became MiniMessage.
* The command's optional argument is a typed parameter; offline players work, and Tab completes
  their names.

## Common surprises

* **`after` does not pause.** Code after an `after` block runs immediately. Put what should
  happen later inside the block.
* **Health is in health points.** `player.health = 20.0` is full health for a normal player (10
  hearts). `player.maxHealth` tells the maximum.
* **Text from players is never formatted.** `player.send(message)` with a variable is refused;
  write `player.send("{message}")`. See [Messages and text](Messages-and-Text).
* **`null` must be handled.** `server.player("Steve")` returns `Player?`. Check it
  (`if target != null`), give a default (`?? ...`) or use `?.`.
* **Numbers have types.** `7 / 2` is `3` (whole numbers); write `7 / 2.0` for `3.5`.
* **Script variables reset on reload; saved ones do not.** A plain top-level `var` starts again
  when its script reloads; use `persistent var` / `playerdata var` for values that must survive.

## Next

* [Your first script](Your-First-Script)
* [Language basics](Language-Basics)
* [Recipes](Recipes) — complete scripts to copy
