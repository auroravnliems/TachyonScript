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

The build uses the Gradle wrapper and a small convention plugin in `build-logic/`
written in Java (no Kotlin DSL plugin toolchain).

## Planned

| Dependency | For | Notes |
|------------|-----|-------|
| ASM (`org.ow2.asm`) | the bytecode backend | Will be shaded and relocated into the plugin jar, because other plugins ship their own ASM versions. |
| A JDBC driver pool / SQLite driver | persistent storage | To be decided with the storage design; likely provided through Paper's library loader instead of shading. |
