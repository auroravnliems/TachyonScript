# Installation

## Requirements

* A **Paper** or **Folia** server for Minecraft **1.21.x** (TachyonScript is built
  against 1.21.11).
* **Java 21** (the version Paper 1.21 requires).
* Optional plugins that TachyonScript uses when present:
  * [Vault](https://www.spigotmc.org/resources/vault.34315/) with an economy plugin, for
    `economy.balance(...)`, `economy.deposit(...)`, chat prefixes and groups;
  * [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/), to show
    script placeholders (`%tys_...%`) in other plugins and to read theirs.

Databases need nothing extra: Paper already contains the SQLite and MySQL drivers.

## Installing

1. Use `TachyonScript-0.7.0-SNAPSHOT.jar` (or build it: `./gradlew build` in the
   repository, then take `tachyon-plugin/build/libs/TachyonScript-0.7.0-SNAPSHOT.jar`).
   This is the packaged plugin; the individual module JARs are not server plugins.
2. Put it into your server's `plugins/` folder.
3. Start the server. TachyonScript creates:

```text
plugins/TachyonScript/
├── config.yml          settings (storage, databases, messages, limits)
├── scripts/
│   └── example.tys     your scripts go here (subfolders are fine)
├── data.db             saved variables (created when first needed)
├── databases/          SQLite files opened by scripts
├── files/              files scripts read and write
├── disabled-scripts.properties  persistent operator stops (created when needed)
├── security/           security audit and preserved incidents
└── logs/               reports of internal errors
```

4. Edit `scripts/example.tys` or add your own `.tys` files, then run **`/tys reload`**.

A file or folder whose name starts with `-` is ignored on a normal reload.
For an immediate persistent stop use `/tys disable shop.tys`; unlike renaming followed by
reload, emergency disable skips unload hooks. See [Admin commands](Admin-Commands).

The deterministic [security scanner](Security) is enabled by default. A compiled script
can still be blocked before activation; inspect the reported incident instead of assuming
reload succeeded. Optional AI and Discord settings start disabled.

## Checking that it works

Run `/tys version` in the console or as an operator:

```text
TachyonScript 0.7.0-SNAPSHOT
  Language level: 2
  IR format: 2
  Backend: interpreter
  Reload mode: lenient
  Server: Paper 1.21.11
  Java: 21.0.12
  Addons: none
```

`/tys status` shows where saved data goes, which databases are configured, and the
commands and placeholders your scripts registered.

## Editors

Scripts are plain text. Any editor works; the file extension is `.tys`. Syntax
highlighting similar to Kotlin or Swift reads well.

To check scripts without starting a server (for example in CI), build the command-line
tool and run `tys check`:

```sh
./gradlew :tachyon-cli:installDist
tachyon-cli/build/install/tys/bin/tys check plugins/TachyonScript/scripts
```

Next: [Your first script](Your-First-Script).
