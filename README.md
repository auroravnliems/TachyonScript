# TachyonScript

A compiled, statically typed scripting language for Paper and Folia Minecraft
servers, with compile-time checks, transactional reloads and a typed server API.

```tys
const PREFIX = "<gold>[Server]</gold> "

playerdata var coins: int = 100

event player.join {
    player.send("{PREFIX}<green>Welcome, {player.name}! You have {player.coins} coins.")
    if player.health < 10.0 {
        player.health = player.maxHealth
    }
}

event block.break {
    if !player.hasPermission("build.bypass") {
        event.cancel()
        player.send("<red>You cannot break {block.type} here.")
    }
}

@playerOnly
@cooldown(10 seconds)
command shop {
    let menu = Menu(3, "<dark_green>Shop")
    menu.set(13, ItemStack(Material.DIAMOND, 1, "<aqua>Diamond", ["<gray>100 coins"]), click => {
        if click.player.coins >= 100 {
            click.player.coins -= 100
            click.player.give(ItemStack(Material.DIAMOND))
        }
    })
    menu.open(player)
}
```

> **Status: 0.7.0-SNAPSHOT.** The language, the standard library (menus, items,
> databases, saved data, boss bars, sidebars, Vault, PlaceholderAPI, ...), the
> Paper/Folia platform and the plugin are covered by tests. The core integration
> probe passes on live Paper and Folia 1.21.11 with both execution backends; see [scope and evidence](docs/integration.md).
> There is no published release yet. See
> [docs/status.md](docs/status.md) for exactly what exists.

## Why

* **Mistakes are found before anything runs.** Scripts are type-checked when they
  load. Errors name the file, line and column, show the code, and suggest fixes:

  ```text
  ERROR scripts/welcome.tys:4:12 [TYS0201]

    4 |     player.sned("{PREFIX}<green>Welcome, {player.name}!")
      |            ^^^^

  Unknown member 'sned' on Player.

  Did you mean:
      send
  ```

* **Everything is built in.** Commands with typed arguments and cooldowns, timers,
  variables saved per server or per player, SQL databases, menus, items, inventories,
  worlds, boss bars, sidebars, holograms, web requests, JSON, Vault and PlaceholderAPI —
  more than 900 functions and properties and 111 events, no addons required.
* **Reloads never break a working server.** `/tys reload` compiles in the background
  and switches atomically; a script with errors keeps its previous working version, and
  everything a reloaded script started (timers, menus, boss bars) is cleaned up.

* **Scripts pass security review before activation.** Compiler-backed static and taint
  checks report exact source locations and quarantine dangerous code, including active
  versions and dependents. Optional Qwen review and Discord alerts use configured
  credentials. AI outages warn by default without holding otherwise eligible scripts;
  deterministic enforcement remains active. See [security configuration and administration](docs/security.md).
* **Safe by construction.** Values inserted into messages are plain text, so players
  cannot inject formatting; SQL can only be written in the script, so SQL injection is a
  compile error; `null` must be handled before a value is used.
* **Folia is supported, not emulated.** Handlers run on the region thread that owns
  the event; changes to entities and blocks owned elsewhere are scheduled on their owners.
* **No work that could be done in advance happens at run time.** Names, types,
  overloads, constants (`Material.DIAMOND`) and bindings are resolved when a script
  loads. The interpreter runs a compact register code with no reflection, no text
  parsing and no allocation per call.
* **Runaway scripts cannot hang the server.** Recursion depth and execution time
  are limited; errors are rate-limited and point at the script line.

## Performance

Measured with JMH on a shared 4-vCPU cloud VM (see [docs/benchmarks.md](docs/benchmarks.md)
for the setup, all results and caveats):

| | Time |
|---|---|
| Send a message with two values (pre-parsed template) | ~0.26 µs |
| Same message parsed with MiniMessage per send | ~6.6–8.2 µs |
| Run a small event handler through the engine | ~44 ns |
| Event that no script handles | no listener registered |

The interpreter is much slower than JIT-compiled Java for tight arithmetic loops
(20–80×); scripts mostly call into the server, where that matters less. A bytecode
backend is planned.

## Getting started

### Server owners

There is no published release yet; build the plugin from source:

```sh
./gradlew build
```

Copy `tachyon-plugin/build/libs/TachyonScript-0.7.0-SNAPSHOT.jar` into `plugins/`
on a Paper or Folia 1.21.x server (Java 21), start it, and edit scripts in
`plugins/TachyonScript/scripts/`. See [docs/plugin.md](docs/plugin.md) for commands,
permissions and configuration, and the [wiki](wiki/Home.md) for a guided tour.

### Checking scripts without a server

```sh
./gradlew :tachyon-cli:installDist
tachyon-cli/build/install/tys/bin/tys check plugins/TachyonScript/scripts
tachyon-cli/build/install/tys/bin/tys dump code my-script.tys
tachyon-cli/build/install/tys/bin/tys dump passes my-script.tys
```

`tys check` exits with status 1 when a script has errors, so it can run in CI.
Use `--no-optimize` or repeat `--disable-pass=<name>` with `check`/`dump` to
diagnose optimizer problems. `dump passes` verifies and prints every intermediate
IR snapshot. See the [optimizer contract and tests](docs/compiler/optimizer.md).

## Documentation

* [Wiki](wiki/Home.md) — installation, tutorials, 17 complete recipes, a guide for Skript
  users and the API reference (the `wiki/` folder is ready to upload as the GitHub wiki)
* [The language](docs/language/README.md) — variables, types, control flow,
  functions, modules, events, commands, scheduling, saved data, databases, messages,
  the world, menus, integrations, errors, coming from Skript, and the generated
  [standard library reference](docs/language/reference.md)
* [Running the plugin](docs/plugin.md)
* [Writing addons](docs/addons/getting-started.md)
* [The standard library generator](docs/stdlib-generator.md)
* [Architecture](docs/architecture.md) and [decision records](docs/decisions/)
* Compiler internals: [lexer](docs/compiler/lexer.md),
  [parser](docs/compiler/parser.md), [type system](docs/compiler/type-system.md),
  [IR](docs/compiler/ir.md), [interpreter](docs/compiler/interpreter.md)
* [Implementation status and roadmap](docs/status.md)
* [Benchmarks](docs/benchmarks.md), [dependencies](docs/dependencies.md),
  [changelog](CHANGELOG.md)

## How it works

```text
.tys ─► lexer ─► parser ─► type checker ─► typed register IR ─► verifier ─► optimizer ─► verifier
      ─► assembler (packed int[] code) ─► linker (natives, templates, constants) ─► interpreter
```

Everything up to the interpreter is independent of Bukkit: the compiler, CLI and
tests run without a server. The platform module implements the standard library's
declarations on the Paper API. Details are in [docs/architecture.md](docs/architecture.md).

| Module | Contents |
|--------|----------|
| `tachyon-api` | Type model, declarations, native function interfaces, addon API |
| `tachyon-language` | Source files, diagnostics, lexer, parser, type checker |
| `tachyon-ir` | Typed register IR, verifier, optimizer |
| `tachyon-compiler` | Lowering to IR and the compilation driver (module graph) |
| `tachyon-runtime` | Assembler, linker, interpreter, runtime values |
| `tachyon-engine` | Transactional loading, dispatch, commands, scheduling, saved data, databases |
| `tachyon-stdlib` | Standard library declarations (mostly generated) and server-independent implementations |
| `tachyon-platform-paper` | Paper/Folia bindings (mostly generated), event bridge, menus, text service |
| `tachyon-plugin` | The plugin: `/tys`, configuration, reload, PlaceholderAPI expansion |
| `tachyon-cli` | The `tys` command-line tool |
| `tachyon-tests` | In-memory test platform and end-to-end tests |
| `tachyon-benchmarks` | JMH benchmarks |
| `tools/stdlib-gen` | Generator of the standard library from `spec/*.api` |

## Building

Requires JDK 21. The Gradle wrapper downloads Gradle; dependencies come from Maven
Central and `repo.papermc.io` (for the Paper API).

```sh
./gradlew build                     # compile, test, build the plugin jar
./gradlew :tachyon-benchmarks:jmh   # run the benchmarks
```

The build treats compiler warnings as errors. Documentation and wiki examples are
compiled by the tests, and the standard library reference is generated
(`./gradlew -q :tachyon-cli:run --args=docs > docs/language/reference.md`) and
checked against the declarations.

## License

GNU General Public License v2.0; see [LICENSE](LICENSE).
