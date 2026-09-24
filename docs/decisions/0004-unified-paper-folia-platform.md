# ADR 0004: One platform for Paper and Folia

## Context

Folia runs regions of the world on different threads. Code may only touch an
entity or a block from the thread that owns it, there is no main thread, and
Bukkit's classic scheduler does not exist. Paper has a main thread but also an
async chat thread and asynchronous events. Both expose the same region-aware
scheduler API (`GlobalRegionScheduler`, `RegionScheduler`, `EntityScheduler`,
`AsyncScheduler`) and ownership checks (`Bukkit.isOwnedByCurrentRegion`).

## Decision

There is **one platform module**, `tachyon-platform-paper`, for both servers.

* `PlatformCapabilities` detects once, at startup, whether regions tick in
  parallel (the presence of Folia's `RegionizedServer` class), never from brand
  strings.
* Bindings never check threads themselves. Writes that must run on the owner of
  some state go through the `Threading` interface: `forEntity(entity, action)`
  and `global(action)`. `BukkitThreading` runs the action immediately when the
  current thread owns the state, and otherwise forwards it to the entity's
  scheduler or the global region scheduler (Folia) or to the main thread
  (Paper).
* Event handlers run on the thread that fired the event, which on Folia is the
  owner of the event's entity or location. The engine is thread-safe: compiled
  code is immutable, each thread has its own execution stack, and the active
  scripts are published as one immutable generation.
* Teleports go through the owner and use `teleportAsync` on Folia, which only
  supports asynchronous teleports.

## Consequences

* One code path, tested once, behaves correctly on both servers; the plugin
  declares `folia-supported: true`.
* A write to an entity the handler does not own is applied when the owner next
  ticks rather than immediately; reads see the old value until then. This is the
  unavoidable price of Folia's model and matches what hand-written Folia
  plugins do.
* Scheduling constructs (`after`, `every`, `async`) will be built on the same
  abstraction.
