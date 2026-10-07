# Live Paper/Folia integration

The 0.7.0-SNAPSHOT core integration probe passed locally on 2026-10-07 with
Microsoft JDK 21.0.12.1 on Windows 11, once with each execution backend, all four runs
on the same plugin jar (SHA-256 `2f5c0fa3d7f2024827073b8eeedf44123d655c749b53f7cc2808581ac43042d6`).
These are real servers running the production plugin, a separate test plugin and a
compiled `.tys` fixture.

| Server | Pinned build | Backend | Checks | Shutdown |
|---|---|---|---:|---|
| Paper 1.21.11 | 132 | interpreter | 28 passed | Exit 0, unload hook, SQLite flushed, no error log |
| Paper 1.21.11 | 132 | bytecode | 28 passed | Exit 0, unload hook, SQLite flushed, no error log |
| Folia 1.21.11 | 14 | interpreter | 32 passed | Exit 0, unload hook, SQLite flushed, no error log |
| Folia 1.21.11 | 14 | bytecode | 32 passed | Exit 0, unload hook, SQLite flushed, no error log |

One check more than in 0.6.0 (27 Paper, 31 Folia) confirms from the startup line that
the requested backend really runs the scripts. A fifth Paper run started with a
`security/` folder damaged by the old storage code (a forked journal and an empty
report): the plugin started, logged both recoveries, kept the damaged journal and passed
29 checks. Evidence: `validation/integration/runs/20261007T0024*`, `…T0025*`, `…T0026*`
and `…T002938229077Z-paper`; summary in `validation/claude-probes-0.7.0-20261007.log`.

The Folia probe requires two independently owned regions, 2,048 blocks apart; it
fails if both entities belong to the executing region. The Paper branch checks its
shared owner behavior. Tests cover:

- Plugin/script activation, actual spawn events and console command dispatch.
- Entity/block reads and writes on the owner, deferred writes to a remote owner,
  and rejection of foreign-region health/name/location reads and entity creation.
- Spawn/drop on an owned region, async entity reads, global-thread read rejection,
  teleport to another region and entity scheduling after the move.
- Entity timers and async → global → entity handoff.
- Transactional reload, saved values, failed-compile rollback, retired callback
  suppression and cancellation of scheduled work from the previous generation.
- A bounded but deliberately slow loop hitting the watchdog and a subsequent call
  recovering correctly. The fixture does not disable or bypass static security.
- Actual server stop with active scripts: unload hooks and persisted unload counter
  are checked after Java exits. SQLite is opened read-only by the runner; `saved=5`
  and `unloads=2` prove reload plus shutdown writes reached the database.

## Reproduce

Build the production plugin and the test-only probe with Java 21:

```sh
./gradlew :tachyon-plugin:shadowJar :tachyon-integration:jar
python validation/integration/run.py --project folia --eula-file /path/to/accepted/eula.txt
python validation/integration/run.py --project paper --eula-file /path/to/accepted/eula.txt
```

`--backend bytecode` runs the same probe on the bytecode backend (the default is
`interpreter`). `--seed-security <folder>` copies a security folder into the server first
and adds a check that it was recovered, for example one damaged by an older version.

On Windows use `gradlew.bat`; `--java` accepts an explicit Java executable. An
existing EULA file must contain `eula=true`. `--accept-eula` is also available for
an operator or CI environment that explicitly accepts the Minecraft EULA.

`validation/integration/servers.json` pins the server URL, version, build and hash.
Downloads use the [official PaperMC downloads service](https://docs.papermc.io/misc/downloads-service/)
and are SHA-256 checked even when cached. The runner creates a new directory under
`validation/integration/runs/`, binds to localhost on an available port, installs
only the two test artifacts and fixture, and disables external AI/Discord services.
It never loads a live server's configuration. The normal timeout is 300 seconds;
the runner sends `stop` and allows up to 60 seconds for shutdown before termination.

Each run writes `probe-result.json`, `result.json` and the full `server.log`, with
artifact/fixture hashes and platform information. An exit code of zero alone is
insufficient: probe checks, unload, saved data and error-free logs must all pass.
Results from failed attempts are retained in their own directories.

The [CI matrix](../.github/workflows/integration.yml) builds and runs both servers
on pull requests, pushes to main and manual dispatch, and uploads evidence even
when a check fails. It has been added locally; no remote CI run is claimed here.

## Findings and limits

The first Folia attempt correctly quarantined an infinite-loop fixture. The fixture
was changed to a long finite loop, keeping security policy intact. The next attempt
revealed two production problems: legacy entity getters accessed Bukkit state
before ownership checks, and plugin shutdown tried to schedule onto Folia's stopped
global scheduler. Both now have regression tests and passing live coverage. Failed
attempts are evidence of those findings, not successful validation.

The probe is deliberately limited. It has no player client, does not verify menus,
player disconnection, inventories during disable, PlaceholderAPI/Vault, external
MySQL, multi-world travel, or prolonged load/reload stress. Existing Paper GUI
results do not establish Folia GUI coverage. The scheduler model follows the
[PaperMC Folia guidance](https://docs.papermc.io/paper/dev/folia-support/), but a
`folia-supported` marker is not a claim that every binding and addon is verified.

Validated result snapshots are kept in
[`validation/integration/evidence-20261006.json`](../validation/integration/evidence-20261006.json).
