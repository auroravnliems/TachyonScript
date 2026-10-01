# Messages and MiniMessage

Chat messages are [MiniMessage](https://docs.advntr.dev/minimessage/format.html)
text:

```tys
event player.join {
    player.send("<gradient:#ffffff:#a855f7>Welcome {player.name}!</gradient>")
    player.send("<hover:show_text:'<gray>Click to read the rules'><click:run_command:/rules>"
        + "<yellow><u>Read the rules</u></yellow></click></hover>")
    broadcast("<gray>{player.name} joined.")
}
```

## Values in messages are text, never tags

Everything in `{...}` is inserted as plain text. A player named
`<click:run_command:/op me>` shows up literally and cannot inject formatting or
click actions. This is also true for chat messages, item names and anything else
a player controls.

Values can also go inside the text of a click action or an insertion, where they are
inserted as plain text too:

```tys
event player.join {
    player.send("<click:suggest_command:'/msg {player.name} '><aqua>[Reply]</aqua></click>")
}
```

`text.command(text, command)`, `text.suggest`, `text.link`, `text.copy` and `text.hover`
build the same clickable text from values without writing a template.

Text that is only known while the script runs — a variable, a chat message, a
string built with `+` from runtime values — is never formatted automatically,
because it could contain tags typed by a player. Using it where a message is
expected is a compile error that shows both ways out:

```text
ERROR scripts/chat.tys:2:15 [TYS0234]

  2 |     broadcast(message)
    |               ^^^^^^^

This text is only known while the script runs, so it is not formatted as MiniMessage
automatically: it could contain tags from a player.

To show it as plain text, use a template: "{message}" (formatting around it is
allowed, e.g. "<gray>{message}").

If the text is trusted MiniMessage, format it explicitly: text.mini(message)
```

Text written in the script is formatted: literals, constants, literals and templates joined
with `+`, and choices between such texts (`player.flying ? "<green>Flying" : "<gray>Walking"`,
or a `switch` whose values are all such texts). Joining templates is how a long message is
split over lines; it is formatted like the single template it spells, so the values inside
`{...}` stay plain text:

```tys
event player.join {
    player.send("<gold>Welcome back, {player.name}! "
        + "<gray>You have played here since {time.format(player.firstPlayed, "dd/MM/yyyy")}.")
}
```

```tys
event player.chat {
    broadcast("<gray>[Chat] {player.name}: {message}")    // shown as plain text
}

event player.join {
    let motd = "<rainbow>Have fun!</rainbow>"             // your own text
    player.send(text.mini(motd))                          // formatted on purpose
}
```

Other conversions: `text.escape(...)` escapes tags, `text.plain(component)` gives the
plain text, `text.legacy("&aHi")` reads `&` color codes, `text.json(...)` and
`text.fromJson(...)` convert to and from the JSON of `/tellraw`.

Clickable text built from values stays safe with the `text` helpers:

```tys-body
let accept = text.command("<green>[Accept]", "/tpaccept {player.name}")
let info = text.hover("<gray>[?]", "<yellow>Requests expire after 60 seconds")
player.send(text.join([accept, info], " "))
```

## Permission broadcasts

`broadcast(message, permission)` checks `hasPermission` on each online player and also
sends to the console. The permission need not be registered by a plugin; operators follow
the server's normal permission rules. Paper's `BroadcastMessageEvent` can cancel the
broadcast or change its message and recipients, just as with ordinary broadcasts.

## Titles, action bars and the tab list

```tys-body
player.title("<gold>Welcome", "<gray>to the server")
player.title("<red>3", "", 0 seconds, 1 second, 250 milliseconds)  // fade in, stay, fade out
player.actionBar("<yellow>Level {player.level}")
player.tabHeader = "<aqua>My Server"
player.tabFooter = "<gray>{server.onlineCount} online"
player.playerListName = "<red>[Admin] <white>{player.name}"
```

## Boss bars

```tys
let bar = BossBar("<red>Boss fight", 1.0, BarColor.RED, BarStyle.NOTCHED_10)

event player.join {
    bar.show(player)
}

every 1 second {
    bar.progress = math.max(0.0, bar.progress - 0.05)
    bar.title = "<red>Boss fight <gray>({math.round(bar.progress * 100)}%)"
}
```

Boss bars are shown per player (`show`, `hide`, `hideAll`, `viewers`). A boss bar
belongs to the script that created it: when that script reloads, it is hidden from
everyone.

## Sidebars

```tys
playerdata var coins: int = 0

event player.join {
    let board = Sidebar("<gold><bold>My Server")
    board.lines = ["<gray>Coins: <yellow>{player.coins}", "", "<gray>play.example.com"]
    board.show(player)
}
```

A sidebar has a title and up to 15 lines (`lines`, `line(index, text)`,
`removeLine(index)`, `clear()`), and is shown to chosen players. It is removed when its
script reloads. Folia has no scoreboards, so sidebars are not available there.

## Performance

A message with values is compiled once, when the script loads: its MiniMessage is
parsed then, and sending it only fills in the values. A message without values
(including constants and `+` between constant strings) is built once and reused.
Only `text.mini(...)` parses at run time. See [`../benchmarks.md`](../benchmarks.md)
for measurements.

## Constants in messages

Constants become part of the MiniMessage text, so they can contain tags:

```tys
const PREFIX = "<gold>[Shop]</gold> "

event player.join {
    player.send("{PREFIX}<gray>Type /shop to buy items.")
}
```
