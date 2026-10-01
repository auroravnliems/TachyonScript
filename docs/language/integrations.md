# Integrations

In `TestPlatform`, text files live only in memory. HTTP requests are recorded and receive
the configured status/body on the next simulated tick; no network connection is opened.
Use `platform.interactions().http(status, body)` and `.requests()` in Java tests. A real
connection failure is represented by status `-1`; the test can supply that response too.
This tests script decisions, not HTTP transport or Paper filesystem confinement.

## Vault: economy, chat and groups

With [Vault](https://www.spigotmc.org/resources/vault.34315/) and an economy plugin
installed, scripts can read and change balances:

```tys
@playerOnly
command sell {
    let hand = player.mainHand
    if hand == null || hand.type != Material.DIAMOND {
        player.send("<red>Hold diamonds to sell them.")
        return
    }
    let earned = hand.amount * 50.0
    if economy.deposit(player, earned) {
        player.mainHand = null
        player.send("<green>Sold for {economy.format(earned)}. Balance: {economy.format(economy.balance(player))}")
    }
}
```

`economy.available`, `balance(player)`, `has(player, amount)`, `deposit(player, amount)`,
`withdraw(player, amount)` (false if the player cannot pay), `format(amount)` and
`currencyName`. With a chat and permission plugin behind Vault: `chat.prefix(player)`,
`chat.suffix(player)`, `permissions.group(player)` and `permissions.groups(player)`.
Without Vault, balances are 0 and payments fail; nothing else breaks.

## PlaceholderAPI

Scripts declare placeholders that every plugin using
[PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) can show, as
`%tys_<name>%`:

```tys
playerdata var kills: int = 0

event entity.death {
    if killer != null {
        killer.kills += 1
    }
}

placeholder kills {
    return "{player?.kills ?? 0}"
}

placeholder rank {
    // %tys_rank_<anything>%: 'argument' is the text after 'rank_'
    return argument == "short" ? "VIP" : "Very Important Player"
}
```

Inside a placeholder, `player` is the `OfflinePlayer?` it is asked for and `argument`
the rest of the name. Scripts can also use other plugins' placeholders:
`papi.parse(player, "%vault_eco_balance%")` (the text comes back unchanged without
PlaceholderAPI).

## Files

`files.read(path)`, `files.lines(path)`, `files.write(path, text)`,
`files.append(path, line)`, `files.exists(path)`, `files.delete(path)` and
`files.list(folder)` work on text files in `plugins/TachyonScript/files/`; paths cannot
leave that folder.

```tys-body
let date = time.format(time.now, "yyyy-MM-dd HH:mm")
files.append("logs/joins.log", "{date} {player.name} joined")
```

## The web and JSON

`web.get(url, (status, body) => ...)` and `web.post(url, body, contentType, (status,
body) => ...)` send requests in the background and call the function on the server
thread with the status code (`-1` when the request failed) and the response.

```tys-body
web.get("https://api.mojang.com/users/profiles/minecraft/{player.name}", (status, body) => {
    if status == 200 {
        let profile = json.parseMap(body)
        let id = json.asString(profile["id"]) ?? "?"
        player.send("Your Mojang id is {id}")
    }
})
```

`json.parse(text)` reads JSON into maps, lists, text, numbers and `bool`;
`json.parseMap`, `json.parseList` and `json.asMap`/`asList`/`asString`/`asNumber`/`asBool`
read it with types, and `value as Map<string, any?>` also works. `json.stringify(value)`
and `json.pretty(value)` write JSON.

## Server

`server.dispatch("say hi")` runs a console command; `server.tps`, `server.mspt`,
`server.uptime`, `server.version`, `server.plugins`, `server.isPluginEnabled(name)`,
`server.whitelist`, `server.motd`, `server.setMaxPlayers(n)`, `server.createWorld(name)`
and more are in the [reference](reference.md#server).
