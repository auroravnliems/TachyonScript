# Coming from Skript

TachyonScript does what Skript does, with a syntax closer to Java, Kotlin or
JavaScript. Scripts are compiled and type-checked when they load, so typos and wrong
types are reported before anything runs, and they execute many times faster.

## Side by side

| Skript | TachyonScript |
|--------|---------------|
| `on join:` | `event player.join { ... }` |
| `send "Hi %player%" to player` | `player.send("Hi {player.name}")` |
| `broadcast "..."` | `broadcast("...")` |
| `set {_x} to 5` | `let x = 5` / `var x = 5` |
| `set {coins::%uuid of player%} to 100` | `playerdata var coins: int = 100` then `player.coins = 100` |
| `set {total} to 0` (saved global) | `persistent var total: int = 0` |
| `add 1 to {total}` | `total += 1` |
| `if {_x} is greater than 5:` | `if x > 5 { ... }` |
| `loop all players:` / `loop-player` | `for p in server.players { ... }` |
| `loop 10 times:` | `for i in 1..10 { ... }` |
| `wait 5 seconds` | `after 5 seconds { ... }` |
| `every 5 minutes:` | `every 5 minutes { ... }` |
| `at 12:00:` | `at "12:00" { ... }` |
| `command /heal [<player>]:` | `command heal(target: Player? = null) { ... }` |
| `permission: server.heal` | `@permission("server.heal")` |
| `cooldown: 30 seconds` | `@cooldown(30 seconds)` |
| `executable by: players` | `@playerOnly` |
| `function heal(p: player):` | `function heal(p: Player) { ... }` |
| `cancel event` | `event.cancel()` |
| `give player 5 diamonds` | `player.give(Material.DIAMOND, 5)` |
| `remove 5 diamonds from player` | `player.inventory.remove(Material.DIAMOND, 5)` |
| `player has 5 diamonds` | `player.inventory.contains(Material.DIAMOND, 5)` |
| `set player's health to 20` | `player.health = 20.0` |
| `teleport player to spawn of world "world"` | `player.teleport(server.world("world")?.spawnLocation ?? player.location)` |
| `play sound "entity.player.levelup" to player` | `player.playSound(Sound.ENTITY_PLAYER_LEVELUP)` |
| `apply speed 2 to player for 30 seconds` | `player.addPotionEffect(PotionEffectType.SPEED, 30 seconds, 2)` |
| `send title "A" with subtitle "B" to player` | `player.title("A", "B")` |
| `send action bar "..." to player` | `player.actionBar("...")` |
| `set block at player to stone` | `player.location.block.type = Material.STONE` |
| `spawn a zombie at player` | `player.location.spawn(EntityType.ZOMBIE)` |
| `name of player's tool` | `player.mainHand?.name` |
| `open chest with 3 rows named "Shop" to player` + inventory click events | `Menu(3, "Shop")` with click functions ([Menus](gui.md)) |
| `%player's balance%` (Vault addon) | `economy.balance(player)` |
| `stop` / `exit` | `return` |

## What is different

* **Types.** Every value has a type (`int`, `string`, `Player`, `List<ItemStack>`...). The
  compiler knows what each value can do, suggests members, and rejects mistakes before
  the script runs.
* **Null safety.** Values that may be missing have a `?` type and must be checked
  (`if target != null`, `target?.name`, `?? default`). There are no "`<none>`" surprises.
* **Variables.** Local variables are declared with `let`/`var`; saved ones are
  `persistent var` (server) and `playerdata var` (per player) with a type — no string
  keys to build, and loading is automatic and asynchronous.
* **Text.** MiniMessage everywhere, and values inserted into messages are always plain
  text, so players cannot inject formatting.
* **No addons needed** for GUIs, databases, boss bars, sidebars, holograms (text
  displays), web requests, JSON, files, persistent data tags, Vault and PlaceholderAPI.
* **Reloads are safe.** A script with errors never replaces a working one, and
  everything a script started (timers, menus, boss bars) stops with it.
* **Folia.** Scripts run on Folia without changes.
