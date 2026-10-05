# TachyonScript

**TachyonScript** is a scripting language for Paper and Folia Minecraft servers.
Scripts are small text files you drop into `plugins/TachyonScript/scripts/`. They are
compiled when they load: every name, type and argument is checked, so mistakes are
reported with the line, the column and a suggestion *before* anything runs — and a
script with a mistake never replaces one that works.

```tys
playerdata var coins: int = 100

event player.join {
    player.send("<green>Welcome back, {player.name}! You have {player.coins} coins.")
}

@playerOnly
@cooldown(1 day)
command daily {
    player.coins += 50
    player.playSound(Sound.ENTITY_PLAYER_LEVELUP)
    player.send("<gold>+50 coins! You now have {player.coins}.")
}
```

## Why TachyonScript

* **Everything built in.** Events, commands with typed arguments, timers, variables
  saved per server or per player, SQL databases, menus, items, inventories, boss bars,
  sidebars, holograms, particles, sounds, web requests, JSON, Vault and PlaceholderAPI —
  more than 900 functions and properties and 111 events, without addons.
* **Checked before it runs.** Typos, wrong types and missing arguments are compile
  errors with suggestions, not surprises at 3 a.m.
* **Fast.** Scripts are compiled to a compact register code; a small event handler runs
  in about 44 nanoseconds, and a message with values is sent without parsing MiniMessage.
* **Safe.** Values in messages are plain text (no formatting injection), SQL can only be
  written in the script (no SQL injection), and runaway loops are stopped.
* **Reloads you can trust.** `/tys reload` compiles in the background; timers, menus and
  boss bars of a reloaded script are cleaned up automatically.
* **Folia ready.** The same scripts run on Paper and Folia.

## Start here

1. [Installation](Installation)
2. [Your first script](Your-First-Script)
3. [Language basics](Language-Basics)
4. [Recipes](Recipes) — complete scripts to copy

Coming from Skript? Read [Coming from Skript](Coming-from-Skript). Looking for a function?
Search the [API reference](API-Reference).

## All pages

| The language | The server | Running it |
|--------------|------------|------------|
| [Language basics](Language-Basics) | [Players](Players) | [Installation](Installation) |
| [Control flow](Control-Flow) | [Entities](Entities) | [Configuration](Configuration) |
| [Functions and lambdas](Functions-and-Lambdas) | [Items and inventories](Items-and-Inventories) | [Admin commands](Admin-Commands) |
| [Lists, maps and records](Lists-Maps-and-Records) | [Menus](Menus) | [Performance and Folia](Performance-and-Folia) |
| [Events](Events) | [Worlds, blocks and effects](Worlds-Blocks-and-Effects) | [Error handling](Error-Handling) |
| [Commands](Commands) | [Messages and text](Messages-and-Text) | [Writing addons](Writing-Addons) |
| [Timers and tasks](Timers-and-Tasks) | [Titles, boss bars and sidebars](Titles-Boss-Bars-and-Sidebars) | [API reference](API-Reference) |
| [Saving data](Saving-Data) | [Custom data tags](Custom-Data-Tags) | [FAQ](FAQ) |
| [Databases](Databases) | [Vault and PlaceholderAPI](Vault-and-PlaceholderAPI) | |
| [Modules](Modules) | [Files, web and JSON](Files-Web-and-JSON) | |
