# Recipes

Complete scripts for common server features. Each one is a single file: copy it into
`plugins/TachyonScript/scripts/`, adjust the texts and numbers, and run `/tys reload`. They are
compiled by the project's tests, so they work as written.

* [Welcome messages](#welcome-messages)
* [Spawn with a teleport delay](#spawn-with-a-teleport-delay)
* [Homes](#homes)
* [Warps with a menu](#warps-with-a-menu)
* [Coins: balance, pay and top](#coins-balance-pay-and-top)
* [Daily reward that survives restarts](#daily-reward-that-survives-restarts)
* [Chat: mute, word filter and caps](#chat-mute-word-filter-and-caps)
* [Teleport requests](#teleport-requests)
* [Shop menu with Vault](#shop-menu-with-vault)
* [A custom item](#a-custom-item)
* [Crates with weighted rewards](#crates-with-weighted-rewards)
* [PvP statistics and kill streaks](#pvp-statistics-and-kill-streaks)
* [AFK detection](#afk-detection)
* [Playtime and rewards](#playtime-and-rewards)
* [Player reports with a database](#player-reports-with-a-database)
* [Block log](#block-log)
* [Maintenance mode](#maintenance-mode)

## Welcome messages

Custom join and quit messages, a starter kit and a counter of unique players.

```tys
const PREFIX = "<dark_gray>[<gold>Server</gold>]</dark_gray> "

persistent var uniquePlayers: int = 0

event player.join {
    if !player.playedBefore {
        uniquePlayers++
        event.joinMessage = "{PREFIX}<light_purple>Welcome <white>{player.name}</white>, our player #{uniquePlayers}!"
        player.give(ItemStack(Material.STONE_SWORD))
        player.give(ItemStack(Material.BREAD, 16))
        player.give(ItemStack(Material.TORCH, 16))
    } else {
        event.joinMessage = "<gray>[<green>+</green>] {player.name}"
    }
    after 1 second for player {
        player.title("<gold>Welcome", "<gray>{server.onlineCount} players online")
        player.playSound(Sound.ENTITY_PLAYER_LEVELUP, 0.6, 1.2)
    }
}

event player.quit {
    event.quitMessage = "<gray>[<red>-</red>] {player.name}"
}
```

## Spawn with a teleport delay

`/setspawn` saves the spawn; `/spawn` teleports after 3 seconds unless the player moves.
Players with `spawn.instant` teleport at once.

```tys
persistent var spawnPoint: Location? = null
var waiting: Map<UUID, Location> = {}

@playerOnly
@permission("spawn.set")
command setspawn {
    spawnPoint = player.location
    player.world.spawnLocation = player.location
    player.send("<green>Spawn set.")
}

@playerOnly
@cooldown(10 seconds)
command spawn {
    let target = spawnPoint ?? server.defaultWorld.spawnLocation
    if player.hasPermission("spawn.instant") {
        player.teleport(target)
        return
    }
    waiting[player.uuid] = player.location
    player.send("<yellow>Teleporting in 3 seconds. Do not move!")
    after 3 seconds for player {
        if waiting.remove(player.uuid) == null {
            return                                // cancelled by moving
        }
        player.teleport(target)
        player.playSound(Sound.ENTITY_ENDERMAN_TELEPORT)
        player.send("<green>Welcome to spawn.")
    }
}

event player.move {
    if !event.changedBlock || !(player.uuid in waiting) {
        return
    }
    waiting.remove(player.uuid)
    player.send("<red>Teleport cancelled: you moved.")
}

event player.quit {
    waiting.remove(player.uuid)
}
```

## Homes

Named homes per player, a limit by permission, and a clickable list.

```tys
playerdata var homes: Map<string, Location> = {}

function homeLimit(p: Player): int {
    if p.hasPermission("homes.unlimited") {
        return 100
    }
    if p.hasPermission("homes.vip") {
        return 5
    }
    return 2
}

@playerOnly
command sethome(name: string = "home") {
    let key = name.lower()
    if !key.matches("[a-z0-9_]\{1,16\}") {     // \{ \} are literal braces
        player.send("<red>Home names use letters, digits and _ (at most 16).")
        return
    }
    let limit = homeLimit(player)
    if !(key in player.homes) && player.homes.size >= limit {
        player.send("<red>You can have {limit} homes. Delete one with /delhome <name>.")
        return
    }
    player.homes[key] = player.location
    player.send("<green>Home <white>{key}</white> set.")
}

@playerOnly
command home(name: string = "home") {
    let target = player.homes[name.lower()]
    if target == null {
        player.send("<red>You have no home called {name}. See /homes.")
        return
    }
    player.teleport(target)
    player.send("<green>Welcome home.")
}

@playerOnly
command delhome(name: string) {
    if player.homes.remove(name.lower()) == null {
        player.send("<red>You have no home called {name}.")
        return
    }
    player.send("<green>Home {name} deleted.")
}

@playerOnly
command homes {
    if player.homes.isEmpty {
        player.send("<gray>You have no homes yet. Set one with /sethome <name>.")
        return
    }
    let links = text.join(player.homes.keys.map(k => text.command("<aqua>{k}", "/home {k}")), "<gray>, ")
    player.send("<gold>Your homes ({player.homes.size}/{homeLimit(player)}): {links}")
}
```

## Warps with a menu

Admins save warps with an icon; everyone opens `/warps` and clicks to travel.

```tys
record Warp(name: string, location: Location, icon: Material)

persistent var warps: Map<string, Warp> = {}

@playerOnly
@permission("warps.admin")
command warp.set(name: string, icon: Material = Material.ENDER_PEARL) {
    warps[name.lower()] = Warp(name, player.location, icon)
    player.send("<green>Warp {name} saved.")
}

@permission("warps.admin")
command warp.delete(name: string) {
    if warps.remove(name.lower()) == null {
        sender.send("<red>No warp called {name}.")
        return
    }
    sender.send("<green>Warp {name} deleted.")
}

@playerOnly
command warp.go(name: string) {
    let warp = warps[name.lower()]
    if warp == null {
        player.send("<red>No warp called {name}.")
        return
    }
    player.teleport(warp.location)
}

@playerOnly
command warps {
    let list = warps.values.sortedBy(w => w.name)
    let rows = math.clamp((list.size + 8) / 9, 1, 6)
    let menu = Menu(rows, "<dark_aqua>Warps")
    for i in 0..<math.min(list.size, rows * 9) {
        let warp = list[i]
        let at = warp.location
        let item = ItemStack(warp.icon, 1, "<aqua>{warp.name}", ["<gray>{at.blockX}, {at.blockY}, {at.blockZ}", "<yellow>Click to travel"])
        menu.set(i, item, click => {
            click.close()
            click.player.teleport(warp.location)
            click.player.playSound(Sound.ENTITY_ENDERMAN_TELEPORT)
        })
    }
    menu.open(player)
}
```

## Coins: balance, pay and top

A simple currency of its own, with a PlaceholderAPI placeholder. (To use your Vault economy
instead, see [Vault and PlaceholderAPI](Vault-and-PlaceholderAPI).)

```tys
const PREFIX = "<gold>[Coins]</gold> "
const START = 100

playerdata var coins: long = START

command balance(target: OfflinePlayer? = null) {
    let who: OfflinePlayer? = target ?? player
    if who == null {
        sender.send("{PREFIX}<red>Name a player.")
        return
    }
    sender.send("{PREFIX}<gray>{who.name ?? "?"} has <yellow>{format.number(who.coins)}</yellow> coins.")
}

@playerOnly
command pay(target: OfflinePlayer, amount: long) {
    if amount <= 0 {
        player.send("{PREFIX}<red>The amount must be positive.")
        return
    }
    if target.uuid == player.uuid {
        player.send("{PREFIX}<red>You cannot pay yourself.")
        return
    }
    if player.coins < amount {
        player.send("{PREFIX}<red>You only have {format.number(player.coins)} coins.")
        return
    }
    player.coins -= amount
    target.coins += amount
    player.send("{PREFIX}<green>Sent {format.number(amount)} coins to {target.name ?? "?"}.")
    target.player?.send("{PREFIX}<green>{player.name} sent you {format.number(amount)} coins.")
}

@permission("coins.admin")
command eco.give(target: OfflinePlayer, amount: long) {
    target.coins += amount
    sender.send("{PREFIX}<green>{target.name ?? "?"} now has {format.number(target.coins)} coins.")
}

@permission("coins.admin")
command eco.set(target: OfflinePlayer, amount: long) {
    target.coins = amount
    sender.send("{PREFIX}<green>{target.name ?? "?"} now has {format.number(amount)} coins.")
}

command baltop {
    let top = server.players.sortedByDescending(p => p.coins).take(10)
    sender.send("{PREFIX}<gold>Richest players online:")
    for i in 0..<top.size {
        sender.send("<yellow>{i + 1}.</yellow> {top[i].name} <gray>- {format.number(top[i].coins)}")
    }
}

placeholder coins {
    return format.number(player?.coins ?? 0)
}
```

`baltop` ranks the players who are online. For a ranking of everyone who ever played, keep
balances in a [database](Databases) instead.

## Daily reward that survives restarts

A reward every 20 hours, with a streak that grows when players come back within two days.

```tys
playerdata var lastDaily: Instant? = null
playerdata var dailyStreak: int = 0

@playerOnly
command daily {
    let last = player.lastDaily
    if last != null && time.since(last) < 20 hours {
        player.send("<red>Your next reward is ready in {format.duration(20 hours - time.since(last))}.")
        return
    }
    if last != null && time.since(last) < 48 hours {
        player.dailyStreak += 1
    } else {
        player.dailyStreak = 1
    }
    player.lastDaily = time.now
    let streak = player.dailyStreak
    let diamonds = math.min(streak, 7)
    player.give(Material.DIAMOND, diamonds)
    player.giveExp(50 * streak)
    player.send("<gold>Daily reward: {diamonds} diamonds and {50 * streak} experience. <gray>Streak: {streak} {format.plural(streak, "day", "days")}.")
    player.playSound(Sound.ENTITY_PLAYER_LEVELUP)
}

event player.join {
    let last = player.lastDaily
    if last == null || time.since(last) >= 20 hours {
        after 5 seconds for player {
            player.send(text.command("<yellow>Your daily reward is ready! <u>Click here</u> or type /daily.", "/daily"))
        }
    }
}
```

## Chat: mute, word filter and caps

```tys
persistent var mutedUntil: Map<UUID, Instant> = {}
let blockedWords = ["badword", "anotherbadword"]

@permission("chat.mute")
command mute(target: OfflinePlayer, duration: Duration, reason: string...) {
    mutedUntil[target.uuid] = time.now + duration
    sender.send("<green>{target.name ?? "?"} is muted for {format.duration(duration)}.")
    target.player?.send("<red>You were muted for {format.duration(duration)}: {reason}")
}

@permission("chat.mute")
command unmute(target: OfflinePlayer) {
    mutedUntil.remove(target.uuid)
    sender.send("<green>{target.name ?? "?"} can talk again.")
}

@priority(LOWEST)
event player.chat {
    let until = mutedUntil[player.uuid]
    if until != null {
        if time.now < until {
            event.cancel()
            player.send("<red>You are muted for {format.duration(time.until(until))}.")
            return
        }
        mutedUntil.remove(player.uuid)
    }
    let lower = message.lower()
    if blockedWords.any(word => word in lower) {
        event.cancel()
        player.send("<red>Please keep the chat friendly.")
        return
    }
    let letters = message.chars().count(c => c.lower() != c.upper())
    let capitals = message.chars().count(c => c != c.lower())
    if letters >= 8 && capitals * 100 / letters > 70 {
        event.message = "{lower}"
    }
}
```

Durations are typed like `30m`, `2h`, `1d` in the command: `/mute Steve 1h spam`.

## Teleport requests

`/tpa <player>` sends a request with clickable buttons; it expires after 60 seconds.

```tys
var requests: Map<UUID, UUID> = {}          // target -> requester
var sentAt: Map<UUID, Instant> = {}

@playerOnly
@cooldown(5 seconds)
command tpa(target: Player) {
    if target == player {
        player.send("<red>You cannot teleport to yourself.")
        return
    }
    requests[target.uuid] = player.uuid
    sentAt[target.uuid] = time.now
    let accept = text.command("<green><bold>[Accept]</bold>", "/tpaccept")
    let deny = text.command("<red><bold>[Deny]</bold>", "/tpdeny")
    target.send("<gold>{player.name}</gold> wants to teleport to you. {accept} {deny}")
    player.send("<gray>Request sent to {target.name}. It expires in 60 seconds.")
}

@playerOnly
command tpaccept {
    let from = requests.remove(player.uuid)
    let sent = sentAt.remove(player.uuid)
    if from == null || sent == null || time.since(sent) > 60 seconds {
        player.send("<red>You have no pending request.")
        return
    }
    let requester = server.player(from)
    if requester == null {
        player.send("<red>That player has left.")
        return
    }
    requester.teleport(player)
    requester.send("<green>Teleported to {player.name}.")
    player.send("<green>Request accepted.")
}

@playerOnly
command tpdeny {
    let from = requests.remove(player.uuid)
    sentAt.remove(player.uuid)
    if from == null {
        player.send("<red>You have no pending request.")
        return
    }
    server.player(from)?.send("<red>{player.name} denied your request.")
    player.send("<gray>Request denied.")
}
```

## Shop menu with Vault

Buys items with the server's economy (Vault and an economy plugin). Left click buys one stack,
shift-click four.

```tys
record Offer(item: Material, amount: int, price: double)

let offers = [
    Offer(Material.BREAD, 16, 20.0),
    Offer(Material.COOKED_BEEF, 16, 40.0),
    Offer(Material.OAK_LOG, 32, 50.0),
    Offer(Material.IRON_INGOT, 8, 80.0),
    Offer(Material.DIAMOND, 1, 250.0),
    Offer(Material.EXPERIENCE_BOTTLE, 8, 120.0)
]

@playerOnly
command shop {
    if !economy.available {
        player.send("<red>The shop is closed: no economy plugin is installed.")
        return
    }
    let menu = Menu(3, "<dark_green><bold>Shop")
    menu.fillBorder(ItemStack(Material.GREEN_STAINED_GLASS_PANE, 1, " "))
    for i in 0..<offers.size {
        let offer = offers[i]
        let icon = ItemStack(offer.item, offer.amount, "<white>{offer.amount}x {offer.item.prettyName}",
            ["<gray>Price: <gold>{economy.format(offer.price)}", "", "<yellow>Click: buy 1", "<yellow>Shift-click: buy 4"])
        menu.set(10 + i, icon, click => {
            let times = click.shift ? 4 : 1
            let cost = offer.price * times
            if !economy.withdraw(click.player, cost) {
                click.player.send("<red>You need {economy.format(cost)}.")
                click.player.playSound(Sound.ENTITY_VILLAGER_NO)
                return
            }
            for n in 1..times {
                click.player.give(offer.item, offer.amount)
            }
            click.player.send("<green>Bought {offer.amount * times} {offer.item.prettyName} for {economy.format(cost)}.")
            click.player.playSound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP)
        })
    }
    menu.set(22, ItemStack(Material.BARRIER, 1, "<red>Close"), click => {
        click.close()
    })
    menu.open(player)
}
```

## A custom item

A hammer that strikes lightning where the player looks, with a cooldown. It is recognised by a
[tag](Custom-Data-Tags), so renaming it in an anvil changes nothing.

```tys
function thorsHammer(): ItemStack {
    let hammer = ItemStack(Material.MACE, 1, "<gradient:#a1c4fd:#c2e9fb><bold>Thor's Hammer")
    hammer.lore = ["<gray>Right-click to call the storm.", "", "<dark_gray>Cooldown: 5 seconds"]
    hammer.unbreakable = true
    hammer.glowing = true
    hammer.setTag("item", "thors_hammer")
    return hammer
}

@permission("items.hammer")
command hammer(target: Player? = null) {
    let who = target ?? player
    if who == null {
        sender.send("<red>Name a player.")
        return
    }
    who.give(thorsHammer())
    sender.send("<green>Gave Thor's Hammer to {who.name}.")
}

event player.interact {
    if !event.rightClick || !event.mainHand || item?.tag("item") != "thors_hammer" {
        return
    }
    if player.hasCooldown(Material.MACE) {
        return
    }
    let target = player.targetBlock(40)
    if target == null {
        player.send("<gray>Nothing to strike.")
        return
    }
    target.world.strikeLightning(target.location)
    player.setCooldown(Material.MACE, 5 seconds)
}
```

## Crates with weighted rewards

Keys are tagged items; rewards have weights (a weight of 50 is ten times more likely than 5).

```tys
record Reward(item: Material, amount: int, weight: double, rare: bool = false)

let rewards = [
    Reward(Material.IRON_INGOT, 8, 50.0),
    Reward(Material.GOLD_INGOT, 6, 30.0),
    Reward(Material.EMERALD, 4, 12.0),
    Reward(Material.DIAMOND, 3, 6.0),
    Reward(Material.NETHERITE_INGOT, 1, 2.0, true)
]

function crateKey(amount: int): ItemStack {
    let key = ItemStack(Material.TRIPWIRE_HOOK, amount, "<gold>Crate Key", ["<gray>Right-click a crate (an ender chest)"])
    key.glowing = true
    key.setTag("crate_key", "basic")
    return key
}

@permission("crates.admin")
command cratekey(target: Player, amount: int = 1) {
    target.give(crateKey(amount))
    sender.send("<green>Gave {amount} crate keys to {target.name}.")
}

event player.interact {
    if !event.rightClick || !event.mainHand || block?.type != Material.ENDER_CHEST {
        return
    }
    let key = item
    if key == null || key.tag("crate_key") != "basic" {
        return
    }
    event.cancel()
    key.amount -= 1
    player.mainHand = key.amount > 0 ? key : null
    let reward = rewards[random.weighted(rewards.map(r => r.weight))]
    player.give(reward.item, reward.amount)
    player.playSound(Sound.BLOCK_CHEST_OPEN)
    if reward.rare {
        broadcast("<light_purple><bold>{player.name} won a rare {reward.item.prettyName}!")
    } else {
        player.send("<green>You won {reward.amount} {reward.item.prettyName}.")
    }
}
```

## PvP statistics and kill streaks

```tys
playerdata var kills: int = 0
playerdata var deaths: int = 0
var streaks: Map<UUID, int> = {}

event player.death {
    victim.deaths += 1
    let lost = streaks.remove(victim.uuid) ?? 0
    if lost >= 5 {
        broadcast("<gray>{victim.name}'s streak of {lost} kills has ended.")
    }
    if killer == null {
        return
    }
    killer.kills += 1
    streaks[killer.uuid] += 1
    let streak = streaks[killer.uuid] ?? 0
    if streak == 5 || streak == 10 || streak % 25 == 0 {
        broadcast("<red><bold>{killer.name} is on a {streak} kill streak!")
        killer.addPotionEffect(PotionEffectType.REGENERATION, 10 seconds, 2)
    }
}

event player.quit {
    streaks.remove(player.uuid)
}

command stats(target: OfflinePlayer? = null) {
    let who: OfflinePlayer? = target ?? player
    if who == null {
        sender.send("<red>Name a player.")
        return
    }
    let ratio = who.deaths == 0 ? who.kills * 1.0 : who.kills / (who.deaths * 1.0)
    sender.send("<gold>{who.name ?? "?"}: <white>{who.kills}</white> kills, <white>{who.deaths}</white> deaths, K/D <white>{format.decimal(ratio, 2)}")
}

placeholder kills {
    return "{player?.kills ?? 0}"
}

placeholder streak {
    let p = player
    return p == null ? "0" : "{streaks[p.uuid] ?? 0}"
}
```

## AFK detection

Players who do not move or chat for 5 minutes are marked AFK; after 30 minutes they are kicked
(unless they have `afk.bypass`).

```tys
var lastActive: Map<UUID, Instant> = {}
var afk: List<UUID> = []

function active(p: Player) {
    lastActive[p.uuid] = time.now
    if p.uuid in afk {
        afk.remove(p.uuid)
        broadcast("<gray>{p.name} is no longer AFK.")
    }
}

event player.join {
    lastActive[player.uuid] = time.now
}

event player.quit {
    lastActive.remove(player.uuid)
    afk.remove(player.uuid)
}

event player.move {
    if event.changedBlock {
        active(player)
    }
}

event player.chat {
    active(player)
}

every 10 seconds {
    for p in server.players {
        let idle = time.since(lastActive[p.uuid] ?? time.now)
        if idle > 5 minutes && !(p.uuid in afk) {
            afk.add(p.uuid)
            broadcast("<gray>{p.name} is now AFK.")
        }
        if idle > 30 minutes && !p.hasPermission("afk.bypass") {
            p.kick("<red>You were AFK for too long.")
        }
    }
}

placeholder afk {
    let p = player
    return p != null && p.uuid in afk ? "AFK" : ""
}
```

## Playtime and rewards

Counts minutes played, rewards every hour, and offers `/playtime` and `%tys_playtime%`.

```tys
playerdata var minutesPlayed: long = 0

every 1 minute {
    for p in server.players {
        p.minutesPlayed += 1
        if p.minutesPlayed % 60 == 0 {
            p.give(Material.DIAMOND, 1)
            p.send("<gold>You played another hour: here is a diamond!")
        }
    }
}

command playtime(target: OfflinePlayer? = null) {
    let who: OfflinePlayer? = target ?? player
    if who == null {
        sender.send("<red>Name a player.")
        return
    }
    sender.send("<gray>{who.name ?? "?"} has played for <white>{format.duration(1 minute * who.minutesPlayed)}</white>.")
}

placeholder playtime {
    let p = player
    if p == null {
        return "0m"
    }
    return "{1 minute * p.minutesPlayed}"
}
```

## Player reports with a database

`/report <player> <reason>` stores a report with the position; staff list, teleport to and
close reports.

```tys
let db = Database.sqlite("reports.db")

on load {
    db.execute("CREATE TABLE IF NOT EXISTS reports (id INTEGER PRIMARY KEY AUTOINCREMENT, reporter TEXT, target TEXT, reason TEXT, world TEXT, x REAL, y REAL, z REAL, time INTEGER, open INTEGER DEFAULT 1)")
}

@playerOnly
@cooldown(1 minute)
command report(target: Player, reason: string...) {
    let at = target.location
    db.update("INSERT INTO reports (reporter, target, reason, world, x, y, z, time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        [player.name, target.name, reason, target.world.name, at.x, at.y, at.z, time.now], count => {
        player.send("<green>Thank you, the staff has been notified.")
        broadcast("<red>[Report]</red> <gray>{player.name} reported {target.name}: {reason}", "reports.see")
    })
}

@permission("reports.see")
command reports.list {
    db.query("SELECT id, reporter, target, reason, time FROM reports WHERE open = 1 ORDER BY id DESC LIMIT 10", rows => {
        if rows.isEmpty {
            sender.send("<green>No open reports.")
            return
        }
        for row in rows {
            let id = row.long("id") ?? 0
            let when = row.instant("time")
            let date = when == null ? "?" : time.format(when, "dd/MM HH:mm")
            let go = text.command("<aqua>[tp]", "/reports tp {id}")
            let close = text.command("<green>[close]", "/reports close {id}")
            sender.send("<yellow>#{id}</yellow> <white>{row.string("target") ?? "?"}</white> <gray>by {row.string("reporter") ?? "?"} at {date}: {row.string("reason") ?? ""}</gray> {go} {close}")
        }
    })
}

@playerOnly
@permission("reports.see")
command reports.tp(id: long) {
    db.queryFirst("SELECT world, x, y, z FROM reports WHERE id = ?", [id], row => {
        let world = server.world(row?.string("world") ?? "")
        if row == null || world == null {
            player.send("<red>Report #{id} not found, or its world is not loaded.")
            return
        }
        player.teleport(Location(world, row.double("x") ?? 0.0, row.double("y") ?? 64.0, row.double("z") ?? 0.0))
    })
}

@permission("reports.see")
command reports.close(id: long) {
    db.update("UPDATE reports SET open = 0 WHERE id = ?", [id], count => {
        sender.send(count == 0 ? "<red>No report #{id}." : "<green>Report #{id} closed.")
    })
}
```

## Block log

Records who placed and broke blocks, and shows the history of a block with `/inspect`.

```tys
let db = Database.sqlite("blocklog.db")

on load {
    db.execute("CREATE TABLE IF NOT EXISTS changes (world TEXT, x INTEGER, y INTEGER, z INTEGER, player TEXT, action TEXT, material TEXT, time INTEGER)")
    db.execute("CREATE INDEX IF NOT EXISTS changes_position ON changes (world, x, y, z)")
}

function record(block: Block, who: Player, action: string) {
    db.execute("INSERT INTO changes VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        [block.world.name, block.x, block.y, block.z, who.name, action, block.type.key, time.now])
}

@ignoreCancelled
@priority(MONITOR)
event block.break {
    record(block, player, "broke")
}

@ignoreCancelled
@priority(MONITOR)
event block.place {
    record(block, player, "placed")
}

@playerOnly
@permission("blocklog.inspect")
command inspect {
    let target = player.targetBlock(6)
    if target == null {
        player.send("<red>Look at a block.")
        return
    }
    db.query("SELECT player, action, material, time FROM changes WHERE world = ? AND x = ? AND y = ? AND z = ? ORDER BY time DESC LIMIT 8",
        [target.world.name, target.x, target.y, target.z], rows => {
        if rows.isEmpty {
            player.send("<gray>No changes recorded at {target.x}, {target.y}, {target.z}.")
            return
        }
        player.send("<gold>History of {target.x}, {target.y}, {target.z}:")
        for row in rows {
            let when = row.instant("time")
            let ago = when == null ? "?" : format.duration(time.since(when))
            player.send("<gray>{ago} ago: <white>{row.string("player") ?? "?"}</white> {row.string("action") ?? "?"} {row.string("material") ?? "?"}")
        }
    })
}
```

## Maintenance mode

`/maintenance on` keeps everyone out except the players on its list, and changes the server list
entry.

```tys
persistent var maintenance: bool = false
persistent var allowed: List<UUID> = []

@permission("server.maintenance")
command maintenance(enabled: bool) {
    maintenance = enabled
    if enabled {
        for p in server.players {
            if !(p.uuid in allowed) && !p.hasPermission("server.maintenance.bypass") {
                p.kick("<red>The server is now under maintenance.<newline><gray>Please come back later.")
            }
        }
    }
    sender.send("<yellow>Maintenance is {enabled ? "on" : "off"}.")
}

@permission("server.maintenance")
command maintenance.allow(target: OfflinePlayer) {
    if !(target.uuid in allowed) {
        allowed.add(target.uuid)
    }
    sender.send("<green>{target.name ?? "?"} may join during maintenance.")
}

event player.preLogin {
    if maintenance && !(uuid in allowed) {
        event.disallow("<red>The server is under maintenance.<newline><gray>Please come back later.")
    }
}

event server.ping {
    if maintenance {
        event.motd = "<red><bold>Maintenance</bold></red><newline><gray>We will be back soon!"
        event.version = "Maintenance"
    }
}
```

## Next

* [Events](Events), [Commands](Commands) and the [API reference](API-Reference) for everything
  these recipes use
* [Saving data](Saving-Data) and [Databases](Databases)
