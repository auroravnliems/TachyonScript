# ADR 0003: Declarations and bindings

## Context

The compiler must know that `player.health` is a `double` property of
`LivingEntity` whose setter takes a `double`, without loading Bukkit: the
compiler, the CLI, tests and a future language server run without a server.
The runtime must know how to read and write it on Paper. Mixing both facts in
one place (for example by reflecting over Bukkit classes) would tie the
compiler to Bukkit and make every script operation depend on reflection.

## Decision

The two facts are kept apart:

* A **declaration** (`tachyon-api`; instances in `tachyon-stdlib` or an addon)
  states the name, owner, parameters, return type, nullability, effects,
  threading requirement and documentation of an operation. Every executable
  operation — global function, method, property getter or setter, event
  variable — is a `NativeDeclaration` with a stable key such as
  `LivingEntity.health:get` or `event player.join:player`.
* A **binding** (`tachyon-platform-paper`, the test platform, or an addon)
  supplies the `NativeFunction` implementing one declaration, plus the Java class
  of each declared type.

The type checker works only with declarations. IR references declarations. The
**linker** resolves each referenced declaration to its binding exactly once per
load; a missing binding is a link error reported before anything is activated.

Natives are shaped by representation (`OfInt`, `OfDouble`, `OfRef`, `OfVoid`, ...)
and receive an `Arguments` view over the caller's registers, so a call boxes
nothing and allocates nothing.

## Consequences

* Nothing below `tachyon-platform-paper` depends on Bukkit.
* The same declarations can generate documentation and drive IDE tooling.
* A second platform (or a test platform) implements the same declarations and
  runs the same compiled scripts; completeness is checked by tests that bind
  every declaration of the standard library.
* Declaring and binding are two steps for extension authors. The addon API
  keeps them close together in one registration call.
