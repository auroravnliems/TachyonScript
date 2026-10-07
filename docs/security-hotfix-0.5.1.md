# Security hotfix — 0.5.1-SNAPSHOT, 2026-10-05

This urgent replacement keeps the snapshot version at the user's explicit request.
It was built from commit `bda56fd` in the isolated `TachyonScript-security-hotfix`
worktree plus the security fixes. Unfinished optimizer work in the main checkout is
excluded from the delivered JAR.

## Changes

- AI outages default to `security.ai.failure-policy: warn`, including configurations
  with legacy `security.ai.required: true`. Startup explains this migration. Set
  `keep-pending` explicitly when a successful external review must gate activation.
- Provider failures share a 60-second request cooldown and one aggregate incident,
  with repeated alerts limited to once per five minutes. Safe error reasons cover
  authentication, billing, rate limits, timeouts and invalid responses.
- Null tool-call envelope fields are accepted; actual tool calls, truncated replies
  and malformed findings remain rejected. Credentials and response bodies stay out
  of failure reports.
- Ordinary event scheduling no longer implies a task storm. Deferred callback bodies
  do not inherit registration loops. Actual loop/recursive scheduling and quotas remain.
- Known command permissions constrain AI escalation. Warnings have compact console
  summaries, with full evidence in `security inspect`.
- Deterministic critical findings, dependent revocation, source-hash checks and existing
  quarantines remain enforced. The patch does not automatically approve scripts.

## Validation

Full `build check :tachyon-plugin:shadowJar :tachyon-cli:installDist` with the separate
wiki checkout passed: **353 tests, zero failures/errors/skips**. Regression cases use
loopback HTTP; they cover 31 unrelated safe scripts plus a dangerous script and its
importer, provider outages/recovery, strict opt-in policy, redaction and AI escalation.

The supplied archive was read without extraction, script execution or external review.
All 32 scripts compiled: **18 ALLOW, 13 WARN, 1 QUARANTINE**. `WARN` permits activation.
`08_GUIs/GUI_DynamicJson.tys` retains a deterministic critical finding at line 138:
`papi.parse` output reaches `server.dispatch` in a menu callback without a recognized
permission guard. It requires code review or an explicit audited override; the AI
outage fix does not release it. This was static verification, not a live server run.

No new performance claim is made. The cooldown bounds repeated provider work during
outages. Paper/Folia runtime smoke tests were not repeated for this patch.

## Install

1. Stop the Minecraft server.
2. Replace its TachyonScript plugin JAR with the tested JAR; retain scripts, config,
   databases and the `security/` audit directory.
3. Start the server. Old `required: true` is migrated in behavior without editing the
   file. `/tys security status` should report `failure-policy: warn` unless explicitly
   configured as `keep-pending`.
4. Check `/tys scripts` and inspect any remaining quarantine separately. `/tys reload`
   reloads scripts; it cannot replace the running plugin JAR.

Delivered JAR SHA-256:

```text
d3f48da19ec7edba510b78f176a4ad26adb795da9323d4fcd4301ec1f3449a9d
```
