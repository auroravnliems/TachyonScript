# TachyonScript

A compiled, statically typed scripting language for Paper and Folia Minecraft
servers.

```tys
const PREFIX = "<gold>[Server]</gold> "

event player.join {
    player.send("{PREFIX}<green>Welcome, {player.name}!")
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
```

> **Status: early development (0.1.0-SNAPSHOT).** The compiler, interpreter, reload
> engine, Paper/Folia platform, plugin and CLI work and are covered by tests, but the
> plugin has not yet been run on a live server by CI and the standard library is
> small. There is no release yet. See [docs/status.md](docs/status.md) for exactly
> what exists and what is planned.

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

* **Reloads never break a working server.** `/tys reload` compiles in the
  background and switches atomically; a script with errors keeps its previous
  working version.
* **Messages are safe and fast.** MiniMessage templates are parsed once when the
  script loads. Values are inserted as plain text, so a player named
  `<click:run_command:/op me>` cannot inject anything, and text typed by players is
  never parsed as MiniMessage implicitly.
* **Folia is supported, not emulated.** Handlers run on the region thread that owns
  the event; changes to entities owned elsewhere are scheduled on their owners.
* **No work that could be done in advance happens at run time.** Names, types,
  overloads and bindings are resolved when a script loads. The interpreter runs a
  compact register code with no reflection, no text parsing and no allocation per
  call.
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

Copy `tachyon-plugin/build/libs/TachyonScript-0.1.0-SNAPSHOT.jar` into `plugins/`
on a Paper or Folia 1.21.x server (Java 21), start it, and edit scripts in
`plugins/TachyonScript/scripts/`. See [docs/plugin.md](docs/plugin.md) for commands,
permissions and configuration.

### Checking scripts without a server

```sh
./gradlew :tachyon-cli:installDist
tachyon-cli/build/install/tys/bin/tys check plugins/TachyonScript/scripts
tachyon-cli/build/install/tys/bin/tys dump code my-script.tys
```

`tys check` exits with status 1 when a script has errors, so it can run in CI.

## Documentation

* [The language](docs/language/README.md) — variables, types, control flow,
  functions, events, messages, and the generated
  [standard library reference](docs/language/reference.md)
* [Running the plugin](docs/plugin.md)
* [Writing addons](docs/addons/getting-started.md)
* [Architecture](docs/architecture.md) and [decision records](docs/decisions/)
* Compiler internals: [lexer](docs/compiler/lexer.md),
  [parser](docs/compiler/parser.md), [type system](docs/compiler/type-system.md),
  [IR](docs/compiler/ir.md), [interpreter](docs/compiler/interpreter.md)
* [Implementation status and roadmap](docs/status.md)
* [Benchmarks](docs/benchmarks.md), [dependencies](docs/dependencies.md),
  [changelog](CHANGELOG.md)

## How it works

```text
.tys ─► lexer ─► parser ─► type checker ─► typed register IR ─► verifier
      ─► assembler (packed int[] code) ─► linker (natives, templates) ─► interpreter
```

Everything up to the interpreter is independent of Bukkit: the compiler, CLI and
tests run without a server. The platform module implements the standard library's
declarations on the Paper API. Details are in [docs/architecture.md](docs/architecture.md).

| Module | Contents |
|--------|----------|
| `tachyon-api` | Type model, declarations, native function interfaces, addon API |
| `tachyon-language` | Source files, diagnostics, lexer, parser, type checker |
| `tachyon-ir` | Typed register IR, verifier, optimizer |
| `tachyon-compiler` | Lowering to IR and the compilation driver |
| `tachyon-runtime` | Assembler, linker, interpreter |
| `tachyon-engine` | Transactional loading, dispatch, errors, profiler, addon assembly |
| `tachyon-stdlib` | Standard library declarations and server-independent implementations |
| `tachyon-platform-paper` | Paper/Folia bindings, event bridge, MiniMessage text service |
| `tachyon-plugin` | The plugin: `/tys`, configuration, reload |
| `tachyon-cli` | The `tys` command-line tool |
| `tachyon-tests` | In-memory test platform and end-to-end tests |
| `tachyon-benchmarks` | JMH benchmarks |

## Building

Requires JDK 21. The Gradle wrapper downloads Gradle; dependencies come from Maven
Central and `repo.papermc.io` (for the Paper API).

```sh
./gradlew build                     # compile, test, build the plugin jar
./gradlew :tachyon-benchmarks:jmh   # run the benchmarks
```

The build treats compiler warnings as errors. Documentation examples are compiled
by the tests, and the standard library reference is generated
(`./gradlew -q :tachyon-cli:run --args=docs > docs/language/reference.md`) and
checked against the declarations.

## License

GNU General Public License v2.0; see [LICENSE](LICENSE).
