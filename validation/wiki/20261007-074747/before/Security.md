# Script security

TachyonScript 0.7.0-SNAPSHOT reviews compiled sources **before activation**: before
initializers, load hooks, commands, handlers, placeholders or tasks can run. The default
deterministic scanner uses the bound module graph, including imports and active dependencies.
Source and dependency hashes are checked again before activation.

AI review and Discord alerts are optional and **disabled by default**. The built-in scanner
does not need either service. Analysis is bounded and conservative; an allowed script is not
a proof that its game logic, permissions or economy are correct. Use the
[operator emergency stop](Admin-Commands#tys-disable-and-tys-enable) for a logic bug.

## Decisions

| Result | Meaning |
|--------|---------|
| Allow / warn | The revision can activate; warnings remain available for review. |
| Quarantine / disable | Execution is revoked, including dependent scripts and old callbacks. The source and report are preserved. |
| Pending | The deterministic scanner exhausted its budget, or AI failed with an explicit `failure-policy: keep-pending`. The revision cannot activate. |

Concrete deterministic critical findings can quarantine automatically. AI critical findings
also need valid identities, hashes, compiler locations, compatible capabilities and concrete
evidence; an unsupported claim is not enough. Higher-confidence wording cannot replace these
checks. High/medium findings normally warn for administrator review.

Quarantine is logical: the original `.tys` file stays on disk. Retired code cannot continue
through old function references or queued effects, and security revocation does not run its
`finally` or unload hooks. Neither strict rollback nor lenient reload reactivates revoked code.
Ordinary compile failures retain the usual reload behavior.

## Commands and permissions

All commands below require `tachyonscript.security.admin` (OP by default).
Detailed in-game alerts use the separate `tachyonscript.security.alerts` permission, also OP
by default; it can be granted to a non-OP administrator.

| Command | Behavior |
|---------|----------|
| `/tys security status` | Shows policy, provider, blocked scripts and delivery backlog. |
| `/tys security incidents` | Lists recent incident IDs. |
| `/tys security inspect <incident>` | Shows source locations, redacted context, data flows, hashes, decisions and explanations. |
| `/tys security scan <path.tys>` | Reviews the graph containing this script; does not activate allowed disk edits. |
| `/tys security scanall` | Reviews all scripts. |
| `/tys security quarantine <path.tys>` | Revokes execution and preserves the source/report. |
| `/tys security disable <path.tys>` | Disables the script and dependents with audit evidence. |
| `/tys security approve <path.tys>` | Scans first, then records an override for the current source and related-module context; does not activate it. |
| `/tys security restore <incident>` | Releases an explicitly approved revision, then performs a guarded reload. |
| `/tys security enable <path.tys>` | Releases the latest security disable after approval, then performs a guarded reload. |
| `/tys security reload` | Reads security configuration and rescans sources. |
| `/tys security test-webhook` | Queues a new audited test message to the configured webhook. |

Use exact script paths relative to `plugins/TachyonScript/scripts/`. To investigate a block:

1. Inspect the incident and follow its source/sink locations, including imported modules.
2. Repair the code and review the full caller/dependency context.
3. Approve the current script revision only if the reported operation is intended.
4. Restore the quarantine incident, or use security enable for a security disable.
   Restore affected dependents separately after their dependency is repaired and restored.

Approvals include the source hash, dependency hashes, transitive importing sources and
security facts. Editing a caller can invalidate approval of an unchanged library. Approval
does not bypass compilation or a required provider that still cannot complete its review.

`/tys enable` and `/tys security enable` clear different controls. A script stopped by both
needs both controls resolved. Disabling the scanner does not clear existing quarantines.

## Configuration

The complete default YAML is in [Configuration](Configuration#the-whole-file).

| Setting | Default / purpose |
|---------|-------------------|
| `security.enabled` | `true`; deterministic review before activation |
| `security.max-source-nodes` / `max-findings` | `25000` / `500`; bounded analysis |
| `security.max-pending-tasks` | `1024` per script; includes declared and scheduled tasks |
| `security.allowed-network-hosts` | `[]`; exact administrator-owned host exceptions |
| `security.native-capabilities` | `{}`; resolved native declaration IDs mapped to addon capabilities |
| `security.ai.enabled` | `false`; requires an endpoint, model and server-owned secret when enabled |
| `security.ai.failure-policy` | `warn`; AI outages retain deterministic decisions. `keep-pending` explicitly requires a completed AI review. |
| `security.ai.max-output-tokens` | `4096`; requested output budget, not permission to accept truncated JSON |
| `security.ai.review-mode` | `background`; loads never wait for the provider (a changed script keeps its previous version until reviewed). `blocking` waits, as before 0.6.0. |
| `security.ai.max-concurrent-reviews` | `2`; parallel provider requests (1-8) |
| `security.discord.enabled` | `false`; enables delivery to a configured webhook |

For optional Qwen-compatible review, configure the endpoint and model for the server's
account and supply `QWEN_API_KEY` in the server process environment. Discord uses
`TACHYON_SECURITY_DISCORD_WEBHOOK`. Production endpoints require HTTPS. Keep these secrets
in server-owned configuration/environment, outside scripts. Missing enabled credentials or
invalid configuration fails closed; an incomplete required review cannot approve code.

The 0.5.1 security hotfix replaces the legacy `security.ai.required` setting. Even an
existing `required: true` now uses `warn` unless `failure-policy: keep-pending` is set
explicitly; startup explains this migration. Existing quarantines and deterministic
critical findings still block execution. `WARN` allows activation.

After a provider failure, requests pause for 60 seconds across scripts. One aggregate
incident describes the cause and affected count; repeated outage alerts are limited to
once per five minutes. Authentication, credit, rate-limit and invalid-response failures
have safe explanations without response bodies or credentials. `scanall` respects the
cooldown; `security reload` resets it after configuration changes. A service outage is
not evidence of a script vulnerability.

Ordinary scheduling from an event no longer warns by itself. Deferred menu/HTTP
callbacks do not inherit the loop that registered them; scheduling inside their own
loops, recursive scheduling and runtime task quotas remain checked. Compiler-established
command permissions also prevent AI from escalating guarded command use to an automatic
quarantine without respecting that boundary. Full findings remain available in `inspect`;
console warnings show a compact summary.

The reviewer receives normalized compiler information and redacted local context. It cannot
execute commands or access Bukkit or the filesystem. Only evidence tied to the registered
source and compiler nodes can affect policy. Network requests run away from tick threads.

### Background reviews (0.6.0)

With `review-mode: background`, `/tys reload` and server start never wait for the AI.
A new or changed revision that needs a review is held back while its previous version,
if any, keeps running; scripts importing it wait with it. When the review finishes,
TachyonScript applies it by itself (the console logs a normal reload summary): an
approved revision activates, a denied one is quarantined. `/tys security status` shows
the reviews running, the reviews stored and the scripts waiting to activate.

Finished reviews are stored in `security/ai-reviews.json` by exact input (source and
dependency hashes, compiler evidence, provider, model and prompt). An unchanged script
is never sent again, including after a restart, and its decision is recomputed with the
current thresholds. Deleting the file only causes reviews to be repeated.

The request contains every action, capability, loop, import, untrusted source, tainted or
secret argument and compiler-proven danger. Pure computations and property reads without
untrusted data are only counted; code shared by several handlers is sent once with all
of them. Requests are about five times smaller than in 0.5.1, so reviews are faster and
cheaper. Scripts with nothing to review never contact the provider.

## File and network boundaries

Script files stay inside `plugins/TachyonScript/files/`; escaping paths, symlinks and directory
junctions are rejected, and reads are bounded. Script HTTP checks DNS results and each
redirect, blocks private/metadata destinations by default, limits bodies and deadlines,
and rejects HTTPS downgrades and cross-origin POST-body forwarding.

An entry in `allowed-network-hosts` intentionally permits that exact host's private addresses.
Use it only for a service the administrator intends scripts to reach; wildcards are not
supported. See [Files, web and JSON](Files-Web-and-JSON) for the script API.

## Audit and recovery

`plugins/TachyonScript/security/` stores the hash-chained `audit.jsonl`, per-incident reports
and exact source snapshots. Reports are redacted; source archives retain the original code
and need the same access restrictions as the scripts. Quarantine does not delete or rename
the original file.

A damaged journal never stops TachyonScript, and never loses a quarantine. If a record no
longer verifies (edited, removed, cut off, or the journal replaced by another copy), the
console logs a SEVERE line starting with `Security journal damaged:` once, and
`/tys security status` repeats it. The damaged journal is kept as
`security/audit-damaged-<UTC time>.jsonl`; the journal is rebuilt from the records before
the damage and the per-incident reports. Quarantines and disables stay in force (one found
only in the damaged part is kept too); approvals and restores count only with their report.
An unreadable report (a write cut off by a crash) is renamed `*.report.json.unreadable`.
Nothing else has to be done; review `/tys security incidents`. Do not edit, delete or
upload over this folder while the server runs: a journal replaced underneath the server is
detected and recovered before the next incident is added, but the copy you uploaded is
then kept only as evidence.

Before 0.7.0, stopping the server while a background review was being applied could leave
this folder damaged, after which the plugin refused to start with `Security audit
integrity check failed; activation blocked` or `Invalid incomplete security report`.
Installing 0.7.0 repairs such a folder on its first start.

When enabled, Discord delivery uses a durable outbox and retries unconfirmed messages.
Unavailable delivery does not delay revocation. A retry may produce a duplicate after an
ambiguous acknowledgement; match incident IDs. Restart retries the existing outbox, without
reconstructing messages from historical audit reports.

## Next

* [Admin commands](Admin-Commands) — operator stops and selective reload
* [Configuration](Configuration) — all defaults
* [Error handling](Error-Handling) — compiler/runtime failures
