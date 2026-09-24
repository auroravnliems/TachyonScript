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

```tys
event player.chat {
    broadcast("<gray>[Chat] {player.name}: {message}")    // shown as plain text
}

event player.join {
    let motd = "<rainbow>Have fun!</rainbow>"             // your own text
    player.send(text.mini(motd))                          // formatted on purpose
}
```

`text.escape(...)` escapes tags in a string, for MiniMessage you assemble yourself.

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
