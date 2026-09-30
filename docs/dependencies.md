# Dependencies

TachyonScript keeps third-party code to what it cannot reasonably do without. Every
dependency is listed here with its reason; the versions are in
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml).

## Shipped in the plugin jar

None. The plugin jar contains only TachyonScript's own modules.

## Provided by the server (compile-only)

| Dependency | Used by | Why |
|------------|---------|-----|
| `io.papermc.paper:paper-api` (1.21.11) | `tachyon-platform-paper`, `tachyon-plugin` | The Bukkit/Paper API the platform binds to. Paper and Folia provide it at run time, together with Adventure (MiniMessage), which the text service uses. |

Everything below `tachyon-platform-paper` (API, language, IR, compiler, runtime,
engine, standard library, CLI) has **no third-party dependency** at all.

## Build and test only

| Dependency | Used by | Why |
|------------|---------|-----|
| JUnit Jupiter 6 (`org.junit:junit-bom`) | all tests | Test framework. |
| JMH 1.37 (`jmh-core`, `jmh-generator-annprocess`) | `tachyon-benchmarks` | The standard harness for trustworthy JVM micro-benchmarks. |
| `paper-api` | tests of the Paper platform and plugin, benchmarks | Real Bukkit event classes and Adventure in tests; MiniMessage in the template benchmark. |
| `org.xerial:sqlite-jdbc` (3.49.1.0) | `tachyon-tests` (test runtime only) | Runs the database tests against real SQLite files. On a server, Paper provides the same driver. |

The build uses the Gradle wrapper and a small convention plugin in `build-logic/`
written in Java (no Kotlin DSL plugin toolchain).

## Provided by the server at run time (not compiled against)

| Library | Used for | How |
|---------|----------|-----|
| SQLite JDBC (`org.sqlite.JDBC`) | saved variables (default storage), `Database.sqlite(...)` | Paper ships it; loaded by class name. |
| MySQL Connector/J (`com.mysql.cj.jdbc.Driver`) | MySQL storage and databases | Paper ships it; loaded by class name. |
| Vault | `economy.*`, `chat.*`, `permissions.*` | Optional plugin; found through Bukkit's services manager and called reflectively. |
| PlaceholderAPI | `papi.parse`, the `%tys_...%` expansion | Optional plugin. `papi.parse` is called reflectively; the expansion class is compiled against small stand-ins in `tachyon-plugin/src/stubs` that are not packaged. |

## Planned

| Dependency | For | Notes |
|------------|-----|-------|
| ASM (`org.ow2.asm`) | the bytecode backend | Will be shaded and relocated into the plugin jar, because other plugins ship their own ASM versions. |
