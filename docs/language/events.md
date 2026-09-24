# Events

A script reacts to things that happen on the server with event handlers:

```tys
event player.join {
    player.send("<green>Welcome {player.name}!")
}

event block.break {
    if !player.hasPermission("build.bypass") {
        event.cancel()
        player.send("<red>You cannot build here.")
    }
}
```

Inside a handler:

* the event's **variables** (`player`, `block`, `killer`, ...) are available by
  name. They are listed per event in the [reference](reference.md#events);
* `event` is the event object, with members such as `event.cancel()`,
  `event.cancelled`, `event.joinMessage` or `event.damage`.

A script can have several handlers for the same event, and several scripts can
handle the same event; all of them run, in order of file name and then declaration.
If one handler fails with a runtime error, the error is logged (with the script
line) and the other handlers still run.

## Available events

| Event            | Variables                     | Cancellable |
|------------------|-------------------------------|-------------|
| `player.join`    | `player`                      | no          |
| `player.quit`    | `player`                      | no          |
| `player.death`   | `victim`, `killer` (nullable) | no          |
| `player.chat`    | `player`, `message`           | yes         |
| `player.move`    | `player`, `from`, `to`        | yes         |
| `block.break`    | `player`, `block`             | yes         |
| `entity.damage`  | `entity`, `cause`             | yes         |

Addons can add more events.

## Threads

Handlers run on the thread that fired the event. On Folia that is the region thread
that owns the player or block, so handlers for different regions run in parallel;
`player.chat` runs off the main thread on both Paper and Folia. Scripts do not need
to care: when a handler changes something that another thread owns (for example the
health of a player in a different region), TachyonScript hands the change to the
owner, and it is applied on that owner's next tick.

## Costs

A server only listens to an event while at least one script handles it, so an
unused `player.move` handler costs nothing. Handlers for frequent events
(`player.move` fires many times per second per player) should stay short.
