# Messages and text

Everything players read — chat messages, titles, item names, boss bars, sidebars, the server
list — is written in [MiniMessage](https://docs.advntr.dev/minimessage/format.html), the
modern text format of Paper:

```tys
event player.join {
    player.send("<green>Welcome, <bold>{player.name}</bold>!")
    player.send("<gradient:#ff8800:#ffdd00>Have a golden day</gradient>")
    player.send("<gray>Visit our <aqua><u><click:open_url:'https://example.com'>website</click></u></aqua>.")
}
```

## MiniMessage in one table

| Tag | Effect |
|-----|--------|
| `<red>`, `<gold>`, `<aqua>`, `<#ff8800>` | color (named or hex) |
| `<bold>` `<b>`, `<italic>` `<i>`, `<underlined>` `<u>`, `<strikethrough>` `<st>`, `<obfuscated>` `<obf>` | decorations |
| `</red>`, `</bold>`, `<reset>` | end a tag / end everything |
| `<gradient:#ff0000:#0000ff>...</gradient>` | a color gradient |
| `<rainbow>...</rainbow>` | rainbow colors |
| `<hover:show_text:'<gray>Hello'>...</hover>` | text shown when hovering |
| `<click:run_command:/spawn>`, `<click:suggest_command:/msg >`, `<click:open_url:'https://...'>`, `<click:copy_to_clipboard:'text'>` | click actions |
| `<newline>` | a line break |
| `<key:key.jump>` | the player's key binding |
| `<lang:block.minecraft.diamond_block>` | a text translated to the player's language |

The [MiniMessage documentation](https://docs.advntr.dev/minimessage/format.html) lists all
tags; [its web viewer](https://webui.advntr.dev/) previews them.

## Values in messages

`{...}` inserts a value into text:

```tys-body
player.send("<gray>You are level <yellow>{player.level}</yellow> with {player.food} food.")
player.send("<gray>Position: {player.location.blockX}, {player.location.blockY}, {player.location.blockZ}")
player.send("<gray>Two plus two is {2 + 2}; your name backwards is {player.name.reversed()}.")
```

How values look in text:

| Value | Text |
|-------|------|
| `42`, `3000000000L` | `42`, `3000000000` |
| `2.0`, `2.5` | `2`, `2.5` (whole doubles have no `.0`) |
| `true` | `true` |
| `null` | `null` — give a default: `{target?.name ?? "nobody"}` |
| `5 minutes + 3 seconds` | `5m 3s` |
| `time.now` | `2026-09-30 14:05:09` (server time zone) |
| a player, an entity, a world | its name |
| an item | `3x diamond`, `1x Excalibur` |
| a location | `world 10.5, 64, -3` |
| a list, a map | `[1, 2, 3]`, `{gold: 20, iron: 5}` — each element as it shows on its own: `[1s, 1m 30s]`, `[Steve, Alex]` |
| a record | `Warp(name=spawn, cost=0)` |
| a constant such as `Material.OAK_LOG` | `oak_log` |

For nicer numbers use `format`: `format.number(1234567)` → `1,234,567`,
`format.money(12.5)` → `12.50`, `format.compact(1500000)` → `1.5M`, `format.percent(0.256)` →
`25.6%`, `format.ordinal(3)` → `3rd`, `format.roman(14)` → `XIV`,
`format.duration(90 seconds)` → `1 minute, 30 seconds`, `format.timer(90 seconds)` → `01:30`,
`format.decimal(2.5, 2)` → `2.50`, `format.bytes(1536)` → `1.5 KB`,
`format.plural(n, "apple", "apples")` → `1 apple` / `3 apples`.

## Values are always plain text

A value inserted with `{...}` is **never** interpreted as MiniMessage. A player who names
themselves `<red>` — or writes `<click:run_command:/op me>` in chat — shows up literally and
cannot inject colors or click actions into your messages:

```tys
event player.chat {
    // The player's message is shown exactly as typed, tags included.
    broadcast("<gray>[Chat] <white>{player.name}</white>: {message}")
    event.cancel()
}
```

This is also why text that is only known while the script runs cannot be sent as a message
directly. A variable, a chat message, a string built with `+` from values, a string returned by
a function — any of these could contain tags from a player, so the compiler refuses to guess:

```text
ERROR scripts/chat.tys:2:15 [TYS0234]

  2 |     broadcast(message)
    |               ^^^^^^^

This text is only known while the script runs, so it is not formatted as MiniMessage automatically: it could contain tags from a player.

Expected:
    Component

Received:
    string

To show it as plain text, use a template: "{message}" (formatting around it is allowed, e.g. "<gray>{message}").

If the text is trusted MiniMessage, format it explicitly: text.mini(message)
```

The two ways out:

| You want | Write |
|----------|-------|
| show the text as it is | `player.send("{text}")` or with formatting around it: `player.send("<gray>{text}")` |
| format trusted MiniMessage (your own config, a constant, a `text` you built) | `player.send(text.mini(value))` |

Text written directly in the script — string literals, constants, literals and templates
joined with `+`, and choices between such texts — is yours, so it is MiniMessage without any
extra step. Joining templates with `+` is how a long message is split over lines; the values
in `{...}` stay plain text, exactly as in one long template:

```tys-body
player.send("<gold>Welcome back, {player.name}! "
    + "<gray>You have played here since {time.format(player.firstPlayed, "dd/MM/yyyy")}.")
```

Choices work the same way:

```tys-body
player.send(player.flying ? "<green>You are flying." : "<gray>You are walking.")
player.send(switch player.gameMode {
    case GameMode.CREATIVE -> "<gold>Creative mode"
    case GameMode.SURVIVAL -> "<green>Survival mode, {player.name}"
    default -> "<gray>Other mode"
})
```

## Constants in messages

Constants are part of the message text, so they can contain tags:

```tys
const PREFIX = "<dark_gray>[<gold>Shop</gold>]</dark_gray> "
const HIGHLIGHT = "<yellow>"

event player.join {
    player.send("{PREFIX}<gray>Type {HIGHLIGHT}/shop</yellow> to buy items.")
}
```

## Components

A `Component` is text that is already formatted. Messages, titles, item names and similar
parameters are `Component`s; a string literal becomes one automatically. Component values keep
their formatting when inserted into a message:

```tys-body
let tag: Component = "<red><bold>[Admin]</bold></red>"
let legacyRank = text.legacy("&6[VIP]")
player.send("{tag} {legacyRank} <white>{player.name}")
```

## The `text` helpers

| Function | Result |
|----------|--------|
| `text.mini(miniMessage)` | formats trusted MiniMessage text (use it only for text players cannot control) |
| `text.escape(text)` | escapes tags, so the text is shown literally inside `text.mini(...)` |
| `text.stripTags(miniMessage)` | the text without its tags |
| `text.plain(component)` | the plain text of a component |
| `text.legacy("&aHi")` | reads `&` or `§` color codes (from old configs and plugins) |
| `text.toLegacy(component)` | a component as `&` color codes |
| `text.json(component)`, `text.fromJson(json)` | to and from the JSON of `/tellraw` |
| `text.translatable("block.minecraft.diamond_block")` | text each player sees in their own language |
| `text.command(text, "/cmd")` | text that runs a command when clicked |
| `text.suggest(text, "/cmd ")` | text that puts a command into the chat box when clicked |
| `text.copy(text, value)` | text that copies a value to the clipboard when clicked |
| `text.link(text, url)` | text that opens a web page when clicked |
| `text.hover(text, hoverText)` | text that shows another text when hovered |
| `text.join(parts, separator)` | components joined with a separator |

Values can go straight into a click action too — `"<click:run_command:'/tpaccept {player.name}'>[Accept]</click>"`
runs `/tpaccept Steve`, with the value inserted as plain text like everywhere else. The click
helpers build the same clickable text **from values** without a template — the command or URL is
set as data, not parsed from MiniMessage:

```tys
@playerOnly
command tpa(target: Player) {
    let accept = text.command("<green>[Accept]", "/tpaccept {player.name}")
    let deny = text.command("<red>[Deny]", "/tpdeny {player.name}")
    let info = text.hover("<gray>[?]", "<yellow>Requests expire after 60 seconds.")
    target.send("<gold>{player.name}</gold> wants to teleport to you. {accept} {deny} {info}")
    player.send("<gray>Request sent to {target.name}.")
}
```

## Where messages go

| Call | Shows |
|------|-------|
| `player.send(message)`, `sender.send(message)` | a chat message to one player or the console |
| `broadcast(message)` | a chat message to every player and the console |
| `broadcast(message, "permission")` | to every player with the permission (and the console) |
| `player.actionBar(message)` | above the hotbar |
| `player.title(title, subtitle)` | a title on screen — see [Titles, boss bars and sidebars](Titles-Boss-Bars-and-Sidebars) |
| `player.kick(reason)` | the disconnect screen |
| `log.info(...)`, `log.warn(...)`, `log.error(...)` | the server console (plain text) |

```tys
event player.join {
    broadcast("<green>+ <gray>{player.name}")
    broadcast("<dark_gray>[Staff] <gray>{player.name} joined from {player.address ?? "?"}", "server.staff")
}
```

Permission broadcasts check each online player's `hasPermission`; the permission need
not be registered by a plugin. The console is included. Paper's `BroadcastMessageEvent`
still allows other plugins to cancel the broadcast or change its message and recipients.

## Formatting the chat

`player.chat` handlers can change the message everyone sees (`event.message`) or cancel the
message and broadcast their own format. Keep the format in the message text and insert the
values — never glue trusted tags and player text together with `+`:

```tys
event player.chat {
    let rank = permissions.group(player) ?? "member"
    event.cancel()
    if player.hasPermission("server.vip") {
        broadcast("<gold>[{rank}]</gold> <white>{player.name}</white><gray>: {message}")
    } else {
        broadcast("<gray>[{rank}] {player.name}: {message}")
    }
}
```

`player.chat` runs off the main thread; broadcasting from it is fine.

## Special characters in strings

| Escape | Character |
|--------|-----------|
| `\n` | a new line |
| `\t` | a tab |
| `\"` | `"` |
| `\\` | `\` |
| `\{`, `\}` | `{`, `}` — braces that do not start a value |
| `é` | a character by its code (here `é`) |

Characters such as `é`, `→` or `★` can also be written directly: scripts are UTF-8.

## Performance

A message with values is prepared once, when the script loads: its MiniMessage is parsed then,
and sending it only fills in the values — about 0.26 µs, 25 to 30 times faster than parsing
MiniMessage for every message. A message without values is built once and reused. Only
`text.mini(...)` parses at run time.

## Next

* [Titles, boss bars and sidebars](Titles-Boss-Bars-and-Sidebars)
* [Vault and PlaceholderAPI](Vault-and-PlaceholderAPI) — prefixes and placeholders
* [Items and inventories](Items-and-Inventories) — names and lore are messages too
