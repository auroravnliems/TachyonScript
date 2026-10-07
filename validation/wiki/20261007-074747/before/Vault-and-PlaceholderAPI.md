# Vault and PlaceholderAPI

TachyonScript works with the two plugins most servers already have. Neither is required:
without them, the functions on this page return neutral values and nothing breaks.

## Vault: money

[Vault](https://www.spigotmc.org/resources/vault.34315/) connects plugins to your economy plugin
(EssentialsX, CMI, ...). With both installed, scripts read and change balances:

```tys
@playerOnly
command sellhand {
    let hand = player.mainHand
    let prices = {Material.DIAMOND: 50.0, Material.EMERALD: 30.0, Material.GOLD_INGOT: 10.0}
    let price = hand == null ? null : prices[hand.type]
    if hand == null || price == null {
        player.send("<red>You cannot sell that.")
        return
    }
    let earned = hand.amount * price
    if !economy.deposit(player, earned) {
        player.send("<red>The payment failed; nothing was sold.")
        return
    }
    player.mainHand = null
    player.send("<green>Sold for {economy.format(earned)}. Balance: {economy.format(economy.balance(player))}")
}

@playerOnly
@cooldown(3 seconds)
command buyxp(levels: int = 1) {
    let cost = 100.0 * levels
    if !economy.withdraw(player, cost) {
        player.send("<red>You need {economy.format(cost)}.")
        return
    }
    player.giveLevels(levels)
    player.send("<green>Bought {levels} levels for {economy.format(cost)}.")
}
```

| Function | Result |
|----------|--------|
| `economy.available` | whether an economy plugin is connected through Vault |
| `economy.balance(player)` | the balance (`0` without an economy) |
| `economy.has(player, amount)` | whether the player has at least the amount |
| `economy.deposit(player, amount)` | gives money; `false` if it failed |
| `economy.withdraw(player, amount)` | takes money; `false` if the player does not have enough or it failed |
| `economy.format(amount)` | the amount as the economy plugin writes it, e.g. `$1,234.50` |
| `economy.currencyName` | the plural name of the currency |

The functions take an `OfflinePlayer`, so they also work for players who are offline
(`economy.deposit(server.offlinePlayer("Notch"), 10)`). Always check the result of `withdraw`
before giving what was paid for — it is the only way to know the money was really taken.

## Vault: prefixes and groups

With a permission plugin that supports Vault (LuckPerms, ...):

| Function | Result |
|----------|--------|
| `chat.prefix(player)`, `chat.suffix(player)` | the player's prefix / suffix, or `""` |
| `permissions.group(player)` | the primary group, or `null` |
| `permissions.groups(player)` | every group of the player |

Prefixes are usually written with `&` color codes; `text.legacy(...)` turns them into formatted
text:

```tys
event player.chat {
    event.cancel()
    let prefix = text.legacy(chat.prefix(player))
    let suffix = text.legacy(chat.suffix(player))
    broadcast("{prefix}<white>{player.name}</white>{suffix}<gray>: {message}")
}

event player.join {
    let groups = permissions.groups(player)
    if "vip" in groups || "mvp" in groups {
        broadcast("<gold>★ {permissions.group(player) ?? "VIP"} {player.name} joined!")
    }
}
```

## PlaceholderAPI: your own placeholders

[PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) lets plugins show
values of other plugins — in scoreboards, tab lists, holograms, chat formats. A script declares
placeholders with `placeholder`; every plugin that supports PlaceholderAPI can then show them as
`%tys_<name>%`:

```tys
playerdata var kills: int = 0
playerdata var deaths: int = 0

event player.death {
    victim.deaths += 1
    if killer != null {
        killer.kills += 1
    }
}

placeholder kills {
    return "{player?.kills ?? 0}"
}

placeholder kdr {
    let p = player
    if p == null {
        return "0"
    }
    let ratio = p.deaths == 0 ? p.kills * 1.0 : p.kills / (p.deaths * 1.0)
    return format.decimal(ratio, 2)
}

placeholder online_staff {
    return "{server.players.count(p => p.hasPermission("server.staff"))}"
}
```

`%tys_kills%`, `%tys_kdr%` and `%tys_online_staff%` now work in any plugin.

Inside a placeholder:

| Variable | Type | Meaning |
|----------|------|---------|
| `player` | `OfflinePlayer?` | the player the text is shown for, or `null` (for example on a global hologram) |
| `argument` | `string` | the rest of the name, see below |

A placeholder must `return` the text to show on every path (the compiler checks this).
Placeholder names are lower-case letters, digits and `_`.

### Placeholders with an argument

`%tys_<name>_<anything>%` calls the placeholder `<name>` with `argument` set to the part after
the name — one placeholder can serve many values:

```tys
persistent var bestTimes: Map<string, long> = {}

placeholder besttime {
    // %tys_besttime_parkour1%, %tys_besttime_parkour2%, ...
    let millis = bestTimes[argument]
    if millis == null {
        return "-"
    }
    return format.timer(1 millisecond * millis)
}

placeholder top {
    // %tys_top_1%, %tys_top_2%, ... : the players with the most kills among those online
    let place = argument.toInt() ?? 1
    let ranked = server.players.sortedByDescending(p => p.kills)
    if place < 1 || place > ranked.size {
        return "-"
    }
    return "{ranked[place - 1].name}"
}

playerdata var kills: int = 0
```

When the full name matches a placeholder exactly it is used as is; otherwise the longest
placeholder name followed by `_` wins.

### Things to know

* Placeholders are asked for often (a scoreboard plugin may ask every second for every player),
  sometimes from background threads. Keep them fast: read variables, do not query databases or
  change the world in them.
* The placeholders of a script are removed when it is unloaded and updated when it is reloaded;
  PlaceholderAPI does not need to be reloaded.
* TachyonScript registers its expansion (`tys`) when it starts, if PlaceholderAPI is installed
  then. `/tys status` shows the declared placeholders and whether PlaceholderAPI is connected.

## PlaceholderAPI: other plugins' placeholders

`papi.parse(player, text)` replaces PlaceholderAPI placeholders in a text — the values of any
plugin:

```tys
@playerOnly
command mystats {
    let rank = papi.parse(player, "%luckperms_primary_group_name%")
    let balance = papi.parse(player, "%vault_eco_balance_formatted%")
    player.send("<gray>Rank: <white>{rank}</white>, balance: <white>{balance}")
}
```

* Without PlaceholderAPI the text comes back unchanged; `papi.available` tells whether it is
  installed.
* The result is plain text inserted into your message. Some placeholders return `&` color codes;
  show them with `text.legacy(papi.parse(...))`.
* `player` may be `null` for placeholders that do not depend on a player.

## Next

* [Messages and text](Messages-and-Text)
* [Saving data](Saving-Data) — where values shown by placeholders usually come from
* [Recipes](Recipes)
