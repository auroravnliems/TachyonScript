# TachyonScript

This wiki documents **0.7.0-SNAPSHOT**, language level 2 and IR format 2, built for
Paper 1.21.11 with Java 21. See the [release notes](Release-Notes) for this update.

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
* **Compiled once.** Scripts run as compact register code in TachyonScript's own engine;
  a message with values is sent without parsing MiniMessage again.
* **Safe.** Values in messages are plain text (no formatting injection), SQL can only be
  written in the script (no SQL injection), and runaway loops are stopped.
* **Controlled updates.** `/tys reload <file>` applies only that file and the necessary
  importers. `/tys disable` stops scripts immediately and remembers the stop across restarts.
* **Review before activation.** Deterministic [security checks](Security) run before scripts
  activate. Optional AI review and Discord alerts are off by default.
* **Paper and Folia integration.** See the threading rules and limits in
  [Performance and Folia](Performance-and-Folia); this update was tested live on Paper.

## New in this update

* [Menus](Menus) own all clicks and drags in their view, including the bottom inventory.
* [Admin commands](Admin-Commands) cover selective reload, emergency disable/enable and
  opt-in slow execution warnings.
* The [API reference](API-Reference) includes statistics, temporary metadata, typed block
  states, both sign sides, display transforms, quaternions, brightness and bounding boxes.
  It contains 111 events, 239 global entries and 780 members across 187 type sections.

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
| [Databases](Databases) | [Vault and PlaceholderAPI](Vault-and-PlaceholderAPI) | [Security](Security) |
| [Modules](Modules) | [Files, web and JSON](Files-Web-and-JSON) | [Release notes](Release-Notes) |
