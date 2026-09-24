# ADR 0006: Pre-parsed message templates

## Context

Scripts send formatted messages constantly:
`player.send("<green>Welcome, {player.name}!")`. Building the MiniMessage string
by concatenation and parsing it on every send is slow, and it is unsafe: a
player named `<click:run_command:/op me>` would inject tags.

## Decision

* A string template used where a `Component` is expected is compiled to a
  `ComponentTemplate` whose literal parts are MiniMessage text and whose
  interpolations are runtime values.
* Compile-time constants are substituted into the literal text first, so
  `const PREFIX = "<gold>[Server]</gold> "` behaves like part of the template.
* At link time the platform text service compiles each template once. The
  Adventure implementation parses the text with a unique marker component in
  place of each value and keeps the parsed tree. Rendering replaces the markers
  with the values (as plain text components) and shares every untouched
  subtree. The parse runs without MiniMessage's compaction, which would merge
  markers into neighbouring text.
* Tags that transform their content character by character (for example a
  gradient around a value) cannot be pre-parsed that way. Those templates fall
  back to parsing with placeholder resolvers on each render, which is still
  injection-safe.
* A template without runtime values becomes one constant component, built at
  link time.
* A `string` known only at run time is **not** implicitly converted to a
  `Component` (error `TYS0234`). Implicit conversion would parse whatever the
  string contains — often text typed by a player — as MiniMessage. Scripts either
  interpolate it (`"<gray>{text}"`, plain text) or call `text.mini(text)` to
  state that it is trusted MiniMessage.

## Consequences

* Values are never parsed as MiniMessage, whatever path renders them, and there
  is no implicit way to parse runtime text as MiniMessage.
* Most templates cost a tree copy of the changed path per message instead of a
  MiniMessage parse; see [`../benchmarks.md`](../benchmarks.md) for measurements.
* Template compilation is part of linking, so a malformed template is reported
  when the script loads, not when the message is first sent.
