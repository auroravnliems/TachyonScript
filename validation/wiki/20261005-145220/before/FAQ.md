# FAQ

### Which servers does TachyonScript run on?

Paper and Folia for Minecraft 1.21.x (built against 1.21.11), with Java 21. Forks of Paper
(Purpur and similar) work as well. Spigot and CraftBukkit are not supported: TachyonScript uses
Paper's API (Adventure text, region schedulers, ...).

### Can it run my Skript scripts?

No — TachyonScript is its own language. Converting is usually quick; the
[Coming from Skript](Coming-from-Skript) page maps Skript syntax to TachyonScript line by line.
Both plugins can be installed at the same time while you migrate.

### Do I have to restart the server after changing a script?

No. Save the file and run `/tys reload`. Only changed files are compiled, and a script with
errors keeps its previous working version. Restart only after changing `config.yml` or updating
the plugin.

### How do I disable a script without deleting it?

Rename it so the name starts with `-` (`-shop.tys`) and run `/tys reload`. The same works for
folders (`-old/`).

### Where are my saved variables?

In `plugins/TachyonScript/data.db` (SQLite) by default, or in your MySQL database if
`storage.type: mysql`. See [Saving data](Saving-Data). They survive reloads and restarts;
renaming a variable or the script's module starts a new, empty variable.

### Why does `player.send(text)` say "This text is only known while the script runs"?

Text that comes from a variable could contain MiniMessage tags typed by a player (a chat
message, a sign, a player name). TachyonScript never formats such text by accident. Write
`player.send("{text}")` to show it as plain text, or `player.send(text.mini(text))` if it is your
own trusted MiniMessage. See [Messages and text](Messages-and-Text).

### Why doesn't my code wait after `after 5 seconds`?

`after` schedules its block and continues immediately; it does not pause. Put everything that
should happen later **inside** the block.

### Why can't I use my variable inside `after` or a lambda?

A scheduled block copies the local variables it uses when it is created. If the variable changes
later, the block would silently see an old value, so the compiler refuses (`TYS0236`). Copy the
value into a `let` first, or use a script variable (declared at the top of the file). See
[Functions and lambdas](Functions-and-Lambdas#what-a-lambda-can-see).

### How do I save a player?

Save the `uuid` or the `OfflinePlayer`, not the `Player`: players leave and come back as new
objects. Per-player values are easiest with `playerdata var`.

### How do I make a cooldown that survives restarts?

`@cooldown` is kept in memory. For long cooldowns, save the time of the last use:

```tys
playerdata var lastKit: Instant? = null

@playerOnly
command kit {
    let last = player.lastKit
    if last != null && time.since(last) < 1 day {
        player.send("<red>Come back in {format.duration(1 day - time.since(last))}.")
        return
    }
    player.lastKit = time.now
    player.give(Material.IRON_SWORD, 1)
    player.give(Material.BREAD, 16)
}
```

### How do I run something for every player every second?

```tys
every 1 second {
    for p in server.players {
        p.actionBar("<gray>Level {p.level}")
    }
}
```

### My command says "Another plugin already uses /x"

Another plugin registered the same command name first. The script command is still available as
`/tachyonscript:x`; rename one of them, or use `@aliases` to give yours a free name.

### My placeholder does not show up

* PlaceholderAPI must be installed when the server starts (TachyonScript registers its `tys`
  expansion then).
* Use `%tys_<name>%` with the name of the `placeholder` declaration.
* `/tys status` lists the placeholders and whether PlaceholderAPI is connected.

### Can scripts use Java classes directly, like skript-reflect?

No, on purpose: every name a script uses is known and type-checked, and scripts cannot reach into
the server in ways that break it. To give scripts access to your plugin, write an
[addon](Writing-Addons) — a few lines of Java per function, checked like the standard library.

### Is it faster than Skript?

TachyonScript compiles scripts and resolves names, types, constants and message formats when they
load, so none of that happens while the server runs; a small handler takes about 44 ns and a
message with values about 0.26 µs. There is no published head-to-head benchmark against Skript
yet; the measured numbers and how to reproduce them are on
[Performance and Folia](Performance-and-Folia).

### Does it work on Folia?

Yes, the same scripts run on Paper and Folia. Sidebars are the only feature Folia cannot provide
(it has no scoreboards). See [Performance and Folia](Performance-and-Folia#folia).

### Is there syntax highlighting or autocompletion for editors?

Not yet — a language server is planned. Until then, a Kotlin or Swift highlighting mode reads
well, and `tys check` checks scripts from the command line (see
[Admin commands](Admin-Commands#checking-scripts-without-a-server)).

### Can players' messages be translated?

The messages of script commands are in `config.yml` (`commands.messages`). Scripts can check
`player.locale` (`en_us`, `vi_vn`, ...) to answer in the player's language, and
`text.translatable(key)` shows Minecraft's own texts in every language.

### How do I update TachyonScript?

Replace the jar in `plugins/` and restart. Scripts written for the same language level (shown by
`/tys version`) keep working; the [changelog](https://github.com/auroravnliems/TachyonScript/blob/main/CHANGELOG.md)
lists what changed.

### How do I report a bug?

Open an issue on [GitHub](https://github.com/auroravnliems/TachyonScript/issues) with the output of
`/tys version`, the smallest script that shows the problem, and the console output. For internal
compiler errors, attach the file from `plugins/TachyonScript/logs/`.
