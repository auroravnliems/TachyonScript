# ADR 0007: Transactional reload with immutable generations

## Context

Reloading must never leave a server with half-loaded scripts, and it must not
block event threads (on Folia there are many). A script with an error must not
take down scripts that worked.

## Decision

* A **generation** is an immutable snapshot of everything that runs: loaded
  scripts, their linked code, a handler table indexed by event, and the set of
  events that have handlers.
* A load reads all scripts, recompiles only those whose content hash changed,
  links them, and builds a complete new generation. Only then is it published
  with a single volatile write. Dispatch reads the current generation once per
  event and never takes a lock; loads are serialized.
* **Lenient mode** (default): a script that fails keeps its previous working
  version (or stays inactive if it never loaded); all other scripts update.
  **Strict mode**: any failure cancels the whole load and the previous
  generation stays active.
* After activation the platform is told which events have handlers and
  registers exactly one listener per such event (and unregisters the others).
* The first load runs during plugin startup; reloads compile on an async thread.

## Consequences

* A broken edit never replaces working code, and the server never runs a mix of
  old and new versions of one script.
* Unchanged scripts are not recompiled, so reloading one file in a large
  scripts directory costs little.
* The previous generation becomes garbage once no event is still running it; a
  test verifies that old generations are collected.
* State in memory (future global variables) does not survive a reload by
  design; persistent storage is the mechanism for that.
