# Script security

TachyonScript reviews compiled `.tys` sources before linking or running initializers,
load hooks, handlers, commands, placeholders or tasks. Deterministic checks are enabled
by default. Qwen and Discord are opt-in and require server-owned configuration.

The `tachyon-security` module is independent of Bukkit. `ScriptEngine` supplies the
complete bound-module graph, including reused modules and imported functions. It
checks source hashes again after review and before activation. Initial Paper loading,
reloads and administrator scans run in the background. External requests never run
on a Minecraft tick thread.

```text
ScriptDirectory -> lexer/parser -> binder -> compiler/IR verifier
                                                |
                                  bound graph + exact source snapshot
                                                |
                  capabilities + static rules + interprocedural taint
                                                |
                           dependency hashes + security manifests
                                                |
                          optional bounded Qwen review batches
                                                |
                       exact-hash policy and durable audit commit
                                   /                        \
                             ALLOW/WARN                DISABLE/QUARANTINE
                                   |                        |
                         linker -> activation       revoke -> retire -> archive
                                                            |
                                                  console/admin/Discord
```

## Source locations and evidence

Source positions come from the existing compiler's `SourceFile` and bound-node
`Span`, which uses half-open UTF-16 offsets. Lines and columns are one-based; the end
column is exclusive. LF, CRLF and CR retain their original bytes in the source hash.
The compiler already preserves native-call spans through IR instructions and
assembled code units; regression tests compare those locations with security nodes.

Every finding records its source SHA-256, script ID, rule/category, origin, severity,
confidence, function/handler, source span, locally generated redacted context, source,
sink, evidence, recommendation and data-flow steps with their own spans. Imported
callee findings point to the callee file; dependency disables point to the importing
statement. An unavailable location uses `UNKNOWN` (-1), with an explicit explanation.
The AI cannot replace locations, code snippets, sources, sinks or compiler taint paths.

Qwen receives normalized compiler nodes with stable IDs, resolved native declaration
IDs, capabilities/effects, arguments, permissions, imports/exports, handlers, commands,
tasks, loops, dependency hashes and taint paths. Large manifests are divided into
bounded requests without dropping nodes. All parts must succeed when AI is required.
Responses use strict JSON; duplicate keys, malformed or truncated envelopes, tool
calls, foreign identities and stale hashes are rejected. AI finding IDs are converted
to opaque internal identifiers before reporting.

## Policy and runtime protection

| Evidence | Default result |
| --- | --- |
| Concrete deterministic CRITICAL finding | Automatic quarantine |
| AI CRITICAL, confidence at least the configured threshold, matching identity/hash, valid compiler node/span, compatible dangerous capability and concrete evidence | Automatic quarantine |
| HIGH/MEDIUM finding or ungrounded confident AI statement | Warn and retain evidence for administrator review |
| Required provider failure, invalid response or scanner budget failure | Revision remains pending; it cannot activate |
| Explicit administrator approval | Audited override for the source SHA-256 and reviewed importer/dependency context; compilation and required-provider checks still apply |

The analyzer follows locals, globals, branches, loop-carried flows, captures, callback
inputs, containers and imported function arguments/returns. It distinguishes typed
numeric data from unsafe command text and prepared SQL values from query syntax.
The language already rejects nonconstant `Sql` arguments at compile time. Permission
annotations and dominating permission checks retain taint evidence while reducing
automatic findings for authorized command handlers.

Checks cover privileged/console/player command execution, OP and permission changes,
private/metadata destinations and dynamic URLs, path escapes, dynamic SQL, recognized
credentials and exfiltration, proven constant infinite loops, scheduling growth and
reconstructed dangerous commands. The real console native is `server.dispatch`.
Additional addon capabilities are assigned to resolved declaration IDs, not names
suggested by an AI response.

The analysis is conservative and bounded, not a proof that arbitrary code is safe.
It does not implement a general symbolic solver or infer the meaning of every addon.
Recursive/dynamic flows can require review; exhausted fixed-point/node/path budgets
leave revisions pending. Existing interpreter time/depth limits remain in force.
Per-script pending task quotas limit both declared tasks and scheduled blocks.

Quarantine immediately revokes execution guards, including in-flight instructions,
old function references, imported calls and queued Paper/Folia effects. A script
cannot catch revocation or execute its `finally` or unload hook after security
revocation. Retirement unregisters handlers, commands and placeholders, cancels tasks
and owned requests/resources, invalidates exports and retires dependent scripts.
Neither strict rollback nor lenient reload can preserve a security-revoked runtime.
Ordinary compiler-error reload behavior remains intact. Restoring a script never
resurrects old revoked function references.

Script HTTP runs through a bounded worker pool. The actual connection resolver rejects
private addresses, including mixed DNS answers, and pins the connection to validated
addresses. Every redirect is checked, with redirect/body/deadline limits, no HTTPS
downgrade and no cross-origin POST-body forwarding. Script files remain inside the
files root; traversal, absolute paths, symlinks and escaping directory junctions are
rejected, with bounded reads. AI has no filesystem, command, reflection or Bukkit
execution interface; its only actionable output is evidence tied to an already
registered script.

## Configuration

Merge these settings into `plugins/TachyonScript/config.yml`. Select the endpoint and
model appropriate to your Qwen account; neither is hard-coded. Export the secret
environment variables for the server process.

```yaml
security:
  enabled: true
  provider: qwen
  max-source-nodes: 25000
  max-findings: 500
  max-pending-tasks: 1024
  allowed-network-hosts: []
  native-capabilities: {}
  ai:
    enabled: false
    required: true
    endpoint: ""
    model: ""
    api-key: "${QWEN_API_KEY}"
    connect-timeout-ms: 5000
    read-timeout-ms: 15000
    max-retries: 2
    max-request-bytes: 1048576
    max-response-bytes: 262144
    max-review-parts: 32
    max-output-tokens: 4096
    confidence:
      warn: 0.80
      auto-disable: 0.95
  discord:
    enabled: false
    webhook: "${TACHYON_SECURITY_DISCORD_WEBHOOK}"
    include-source-snippets: true
    timeout-ms: 10000
    max-retries: 2
```

Production provider/webhook endpoints require HTTPS. Literal loopback HTTP endpoints
are accepted for local protocol tests. Invalid configuration or missing enabled
credentials fails closed. Values are withheld from configuration errors and debug
representations. Recognized source credentials and configured API/webhook secrets are
masked before review, reporting or alerts. Control and bidirectional override
characters are neutralized without shifting snippet columns.

The output-token budget is sent as `max_tokens` on every provider request. Its default
is 4096, configurable from 128 to 65536. This bounds the requested completion budget
instead of relying on a provider's potentially much larger default. An exhausted
budget (`finish_reason: length`) is a failed review, so a required review remains
pending; partial JSON cannot approve a script.

Qwen can also be reviewed through OpenRouter's compatible endpoint. Merge
[`validation/security/openrouter-security.yml`](../validation/security/openrouter-security.yml)
into the server configuration and export `OPENROUTER_API_KEY` and
`TACHYON_SECURITY_DISCORD_WEBHOOK` for that server process. This profile enables both
integrations; set `security.discord.enabled: false` when no webhook is configured.
This profile selects `qwen/qwen3-coder`, a 60-second review timeout and a 4096-token
output budget. `provider: qwen` selects the existing Qwen security reviewer; the
configured endpoint determines the transport. API keys remain in the environment.
Endpoint details are documented in the
[OpenRouter quickstart](https://openrouter.ai/docs/quickstart).

For the prepared Windows Paper installation, `validation/security/configure_paper_security.ps1`
installs the built JAR and this configuration, preserving the previous JAR, config and
launcher under `plugins/TachyonScript/security-setup-backups`. Supply the two credentials
in the installer process environment and select `-ServerDirectory` and optionally `-Model`.
The original server must be stopped; installation does not start it.

Credentials are exported as Windows DPAPI-encrypted `SecureString` values to
`%LOCALAPPDATA%/TachyonScript/security/secrets.clixml`, in a directory restricted to the
current Windows account. They are not embedded in YAML, Java, the JAR or the project.
The launcher imports `security-env.ps1` before starting Node/Paper, so subsequent
launches automatically populate the plugin's credential environment variables. The
encrypted store belongs to that Windows account on that machine; use the installer
again with replacement environment values when rotating credentials.

`allowed-network-hosts` contains exact administrator-owned exceptions, with no wildcard
matching. An exception intentionally permits that host's private addresses. Addon
`native-capabilities` entries use the complete resolved native declaration ID and a
`Capability` enum value. Disabling the scanner does not release existing quarantines.

## Administrator operations

`tachyonscript.security.alerts` controls detailed in-game notifications. Delivery checks
this permission explicitly, so a non-OP administrator can receive alerts.
`tachyonscript.security.admin` controls the security commands. Both default to OP and
can be granted independently by a permissions plugin.

| Command | Behavior |
| --- | --- |
| `/tys security status` | Policy, provider, blocked scripts, audit count and Discord backlog |
| `/tys security incidents` | Recent incident IDs with inspection links |
| `/tys security inspect <incident>` | Full locations, redacted snippets, flows, decisions, hash, dependencies and AI/static explanations; clickable location copies |
| `/tys security scan <path.tys>` | Review the complete graph so imports remain sound; do not activate allowed edits |
| `/tys security scanall` | Review all scripts |
| `/tys security quarantine <path.tys>` | Immediate runtime revocation and preserved source/report |
| `/tys security disable <path.tys>` | Immediate disable, including dependents, with audit/alerts |
| `/tys security approve <path.tys>` | Scan first, then approve the current source hash and related-module context; does not activate or release quarantine |
| `/tys security restore <incident>` | Release an explicitly approved current revision, then perform the normal guarded reload |
| `/tys security enable <path.tys>` | Release the latest disable after explicit approval, then guarded reload |
| `/tys security test-webhook` | Queue a new audited delivery test |
| `/tys security reload` | Read security configuration and rescan sources |

Use exact relative script paths. Restore dependent scripts separately after repairing
and restoring their dependency. Editing a quarantined file is allowed; approve the
repaired hash before restoring the incident. The original archived revision is retained.

Approval fingerprints include imported sources, transitive importing sources and native
capability/security facts. Editing a caller can introduce taint into unchanged library
code, so it invalidates that library's previous override. Approvals cannot bypass a
newly edited importer merely because the callee's source hash is unchanged.

## Audit and delivery

`plugins/TachyonScript/security` contains an append-only, fsynced hash-chained
`audit.jsonl`, immutable per-incident `.report.json` files and exact `.source.tys`
snapshots for disables/quarantines. Quarantine is logical: the original script stays
on disk and cannot run until explicitly restored. Nothing is automatically deleted
or renamed. Reports are redacted; source archives preserve the original source and
must have the same access restrictions as the scripts they contain.

Write-ahead reports recover revocation after an interrupted journal append. An older
recovered restore cannot undo a newer committed quarantine. Corrupt audit state blocks
startup instead of silently losing revocations. All approvals/restores are audited.

Console and in-game alerts include actionable locations and an incident inspection
command. Discord uses colored cards with Vietnamese labels, inline script/location/
severity/action/confidence/analyzer fields, code blocks for the local source snippet
and complete taint flow, dependencies, SHA-256, an inspect command and incident/time
footer. An ordinary finding fits in one card; long text and multiple findings use
bounded continuation cards without losing compiler locations or flow steps. Cards
respect Discord's field, embed and total message limits, disable mentions and use
`wait=true` confirmation. A durable outbox stages each new
alert before audit commit; it becomes deliverable only after that exact commit.
429/5xx failures retry and unconfirmed alerts survive restart. API/scanner/webhook
failures are also audited and notified without creating fake vulnerabilities. Discord
availability cannot prevent immediate runtime revocation. Notifications require a
working configured transport for eventual remote delivery; they cannot guarantee
delivery while Discord is unavailable. Ambiguous transport acknowledgement can cause
a retry duplicate, identified by the same incident ID.

Restart retries payloads already in the outbox. Historical audit reports are never
used to reconstruct or replay old webhook messages.
Queued alerts from the previous plain-text format remain byte-for-byte unchanged
and can still be delivered; upgrading the display does not recreate past alerts.

## Verification

```powershell
.\gradlew.bat build
.\gradlew.bat :tachyon-security:test :tachyon-tests:test :tachyon-platform-paper:test :tachyon-plugin:test
.\gradlew.bat :tachyon-plugin:jar
```

The packaged artifact is `tachyon-plugin/build/libs/TachyonScript-0.5.1-SNAPSHOT.jar`.
OkHttp, Okio and Kotlin are relocated to avoid other plugins' dependency versions.

Tests check compiler/IR/source agreement, multiline/nested calls, Unicode, comments,
blank lines, LF/CRLF/CR, interprocedural imports, exact source/sink paths, stale async
reviews, wrong AI lines/snippets, strict JSON, bounded complete review batches,
required-review gating, both reload modes, runtime retirement, permanent revocation,
audit corruption/crash recovery and webhook retry/commit boundaries.

`validation/security/paper_security_smoke.py` runs the packaged JAR on a fresh
loopback-only Paper server using cached `server.args` and local mock APIs. It preserves
the supplied installation and stops its own server in `finally`. Supply `--paper-home`
and `--java`; optional `--mineflayer` points to an installed module for two local
clients that verify detailed alerts to a non-OP administrator and no alerts to an
ordinary player. Its Java permission probe is a test fixture and is never included
in the production plugin. Results and logs remain under `build/security-paper`.

The automated suites and Paper harness use local protocol mocks. An opt-in live probe
reviews two synthetic sources through the provider from the packaged plugin JAR. It
checks a safe constant command and a concrete chat-to-console flow, including exact
compiler node IDs, hashes, UTF-16 spans, CRLF, Unicode, local snippets and the AI-only
policy decision. It never uploads existing server scripts or executes the fixtures.
Export `OPENROUTER_API_KEY` for the probe process, then run:

```powershell
$probeJar = 'tachyon-plugin/build/libs/TachyonScript-0.5.1-SNAPSHOT.jar'
New-Item -ItemType Directory -Path build/security-openrouter/classes -Force | Out-Null
javac -Xlint:all -Werror -encoding UTF-8 -cp $probeJar -d build/security-openrouter/classes validation/security/OpenRouterSecurityProbe.java
java -cp "build/security-openrouter/classes;$probeJar" OpenRouterSecurityProbe https://openrouter.ai/api/v1/chat/completions qwen/qwen3-coder build/security-openrouter/result.json $probeJar
python validation/security/verify_integration.py
```

This invokes the real API using account credit. The report contains findings and the
tested JAR hash, never the credential. The verifier includes successful live evidence
only when its JAR SHA-256 matches the currently delivered artifact.

`validation/security/paper_live_security_smoke.py` also tests real Qwen approval and
real Discord delivery on disposable loopback-only Paper. It requires the two credential
environment variables and uses only synthetic sources. The deliberately vulnerable
[`command-injection.tys`](../validation/security/fixtures/command-injection.tys) fixture
routes chat input into `server.dispatch` at line 5, column 5. It is copied into the
disposable test installation, where quarantine must prevent its initializer from
running, retire the previous tasks/commands, disable dependents and preserve its source.
It is not installed in the original server's active scripts directory.

The live harness posts new test incidents to the explicitly configured webhook and
checks its durable `wait=true` acknowledgements, exact locations, snippets, taint flow,
hashes, non-OP administrator delivery and absence of credentials. It never replays
historical alerts. Reports stay under `build/security-live-paper`; its own server stops
in `finally`, while the original Paper installation remains stopped.
