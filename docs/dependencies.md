# Dependencies

TachyonScript keeps third-party code to what it cannot reasonably do without. Every
dependency is listed here with its reason; the versions are in
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml).

## Shipped in the plugin jar

ASM is the only bundled library; the plugin jar is about 1.9 MB.

Script HTTP (`web.get`, `web.post`) needs the addresses the security policy validated
to be the addresses actually connected to; the JDK's `HttpClient` resolves host names
again when it connects, which leaves a DNS-rebinding race. Up to 0.5.1 this was solved
with OkHttp, which with Okio and the Kotlin standard library made up 2.9 MB of the
5 MB jar. Since 0.6.0 a small HTTP/1.1 client on JDK sockets
(`SecureWebTransport`) connects to the validated addresses itself, keeps TLS SNI and
HTTPS host-name verification, bounds headers, bodies (2 MB, also after gzip) and the
whole request (30 s, writes included), and follows redirects by hand under the same
checks. Its tests cover rebinding, redirects, chunked/gzip bodies, truncation, header
injection, deadlines, cancellation and host-name verification against a local TLS
server.

ASM 9.10.1 (`org.ow2.asm:asm`) generates JVM class files for the optional bytecode
backend, including stack-map frames and source lines. The runtime keeps the
interpreter as its reference backend. ASM is shaded under
`dev.tachyonscript.internal.asm` in the plugin so server/addon versions cannot
collide. This release is recorded in the [official ASM history](https://asm.ow2.io/versions.html).
`asm-util`, its tree and analysis dependencies are used only by bytecode tests.

## Provided by the server (compile-only)

| Dependency | Used by | Why |
|------------|---------|-----|
| `io.papermc.paper:paper-api` (1.21.11) | `tachyon-platform-paper`, `tachyon-plugin` | The Bukkit/Paper API the platform binds to. Paper and Folia provide it at run time, together with Adventure (MiniMessage), which the text service uses. |

The API, language, IR, compiler and standard library remain independent of Bukkit.
The runtime uses ASM for bytecode generation; it does not depend on a server API.

## Build and test only

| Dependency | Used by | Why |
|------------|---------|-----|
| JUnit Jupiter 6 (`org.junit:junit-bom`) | all tests | Test framework. |
| ASM util 9.10.1 (`org.ow2.asm:asm-util`) | runtime tests | Structural/dataflow checks of emitted JVM classes. |
| Shadow 9.6.1 (`com.gradleup.shadow`) | plugin build only | Bundles the modules and relocates ASM; never runs on the server. |
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
