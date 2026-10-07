# Release notes

## 0.7.0-SNAPSHOT — 2026-10-07

### Fixed: the plugin no longer stops because of its security folder

* After switching the AI reviewer off and on again (with restarts), 0.6.0 could refuse to
  start with `Security audit integrity check failed; activation blocked` or
  `Invalid incomplete security report`, and no script ran. Stopping the server while a
  background review was being applied interrupted a file write. Security files are now
  written so that an interrupt cannot cut them short, a failed write is undone, and the
  journal is checked before every new incident.
* A damaged journal or report is repaired at startup instead of blocking everything: the
  damaged journal is kept as evidence, the journal is rebuilt from the per-incident reports,
  quarantines stay in force, and the console and `/tys security status` say what happened.
  A folder damaged by 0.6.0 is repaired on the first start of 0.7.0.

### Bytecode backend (opt-in)

* `runtime.backend: bytecode` compiles script functions to JVM classes: loops about 11 times
  and script calls about 3 times faster, with the same behaviour as the interpreter. Loading
  is slower; the interpreter stays the default. See [Performance](Performance-and-Folia#bytecode-backend).
* `tys dump bytecode <file>` shows the generated classes.
* The live Paper and Folia probe passes with both backends.

## 0.6.0-SNAPSHOT — 2026-10-06

### Lighter and faster than 0.5.1

* AI security reviews run in the background (`security.ai.review-mode: background`): a
  reload returns at once and a changed script keeps its previous version until its review
  finishes, then activates by itself. In 0.5.1 one changed script made a reload wait about
  18 seconds, and a restart reviewed every script in turn.
* Finished reviews are stored in `security/ai-reviews.json`; unchanged scripts are never
  sent again, also after a restart.
* Review requests are about five times smaller (6.0 MB to 1.2 MB for a 32-script server).
* The interpreter is 1.5-1.9 times faster: revocation is checked at function entry, before
  natives and in the watchdog's loop check instead of before every instruction.
* The plugin jar is 1.9 MB instead of 5.0 MB: script HTTP uses the JDK instead of OkHttp
  and Kotlin, with the same rebinding, TLS, size, redirect and time protections.
* The deterministic security analysis is 3.2 times faster with identical results, and the
  security service's memory no longer grows with every edit and reload.
* Recursion without loops now also hits the execution-time watchdog.

The optimizer now includes block-local copy/constant propagation, primitive and
branch folding, exception-aware liveness/dead-store elimination, jump threading
and unreachable-block removal. Calls, runtime errors, globals and watchdog checks
retain their behavior. Unused registers no longer consume frame slots.

Source-to-runtime differential tests compare full optimization, each pass alone
and each pass disabled, including intermediate verified IR. Seeded programs also
use Java arithmetic oracles; engine tests cover imports, events, saved state,
commands, scheduling and retired callbacks. CLI `dump passes`, `--no-optimize`
and repeatable `--disable-pass=<name>` make these comparisons available for debugging.

Live core integration passed on Paper 1.21.11 build 132 (27 checks) and Folia build
14 (31 checks). The probe uncovered and verified fixes for legacy entity reads
outside ownership and shutdown scheduling after Folia had halted its schedulers.
Display-name writes are now forwarded to the owner. Unload changes are checked in
SQLite after server stop. A pinned-download runner and CI matrix are included;
remote CI and live Folia player/GUI coverage remain pending.

The 2026-10-06 JMH loop results have overlapping confidence intervals; no speedup
is claimed. Compilation has additional optimization cost. See [Performance and Folia](Performance-and-Folia).
Language level and IR format remain at 2. Security behavior from the delivered
0.5.1 hotfix remains in place.

## 0.5.1-SNAPSHOT — 2026-10-05

This wiki is synchronized from its earlier 0.2 documentation to the delivered
**0.5.1-SNAPSHOT** plugin and CLI. TachyonScript retains its own compiler, IR and interpreter.
Language level and IR format remain at 2; the build uses Java 21, Paper 1.21.11 and Gradle 9.7.1.
Future delivered updates increment the runtime version and update the matching documentation.

### Security hotfix — same requested snapshot version

At the user's explicit request, the urgent security replacement retains `0.5.1-SNAPSHOT`.
AI outages now use `security.ai.failure-policy: warn` by default, including with the
legacy `required: true` configuration. An explicit `keep-pending` preserves mandatory
AI review. Provider failures use a shared 60-second cooldown and at most one aggregate
alert per five minutes. Deterministic critical findings and existing quarantines still
block execution. Ordinary scheduling and deferred menu callbacks produce fewer false
positives; console warnings are compact and detailed incidents remain inspectable.

The isolated security hotfix passed full build/check, **353 tests with no skips**, and
compiler/static review of the supplied 32-script archive: 18 allow, 13 warn, one quarantine.
The archive check did not execute scripts or contact external services. No live Paper or
Folia run was performed for this security patch; the earlier GUI evidence below remains
historical. See [Security](Security) for migration and recovery.

### Menus

Generic inventory click/drag handlers no longer receive events anywhere in a `Menu(...)`
view, including bottom slots and outside clicks. This holds at every priority and with
`allowTaking = true`. Slot callbacks still run once, and `MenuClick.cancelled` starts as
`!allowTaking` and remains writable. Ordinary inventories and other plugins' GUIs retain
their generic cancellation API. See [Menus](Menus) and [Events](Events).

### Operating scripts

* A targeted reload reads only its selected file and uses active sources for importers.
  Unrelated disk changes stay unapplied; a failed selected group rolls back together.
* Persistent `/tys disable` / `/tys enable` support emergency stops and dependent scripts.
  Emergency stops skip unload hooks and revoke old callbacks and owned resources.
* Slow execution warnings are off by default; the opt-in threshold defaults to 50 ms,
  applies on tick threads, and is throttled per function. The profiler remains available.
* Server logs use compact diagnostics with source lines, carets and line/column locations.
* [Security](Security) documents the existing activation checks, quarantine, review,
  approvals, audit and optional integrations alongside these operator controls.

### Library additions

* [Player statistics](Players#statistics) with typed material/entity overloads.
* [Temporary metadata](Custom-Data-Tags#temporary-metadata) on Entity, Block and World,
  owned by the latest writing script revision and cleaned up with that revision.
* [Typed BlockData and sign sides](Worlds-Blocks-and-Effects#typed-block-states-and-signs).
* [Display transforms, Quaternion and Brightness](Titles-Boss-Bars-and-Sidebars#typed-display-transforms).
* [Copied bounding-box operations](Entities#bounding-boxes).

The generated [API reference](API-Reference) has 111 events, 239 global entries and
780 members in 187 type sections. Type members listed there exclude inherited members.

### Verification and limits

The delivered plugin passed the Gradle build/check and 36 live GUI cases on an isolated
Paper 1.21.11 server. This update did not run live Folia tests. Menu callbacks that explicitly
allow taking, and external plugins' Java listeners, retain their existing authority.

The Skript source audit recorded decisions for 29 of 941 classes; 912 remain unreviewed.
This is selective API expansion, not full parity. Loot/AnvilView APIs and offline statistics
remain gaps. See [Coming from Skript](Coming-from-Skript#migration-scope).

To verify a separate wiki checkout against the matching source tree, run from the plugin repo:

```sh
./gradlew :tachyon-cli:test -Ptachyon.wiki=../wiki
```

This compiles the `tys` and `tys-body` examples and checks that `API-Reference.md` exactly
matches `tys docs --wiki`. Regenerate that page with the matching CLI whenever the registry
changes; handwritten pages keep their own examples and explanations.
