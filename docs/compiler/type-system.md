# Type system

TachyonScript is statically typed. Every expression has one type, known when the
script is compiled; the runtime never looks at a value to decide what an
operation means. The binder (`dev.tachyonscript.language.semantic`) checks and
annotates the syntax tree; its output is a bound tree in which every node
carries its type and every name its resolved symbol.

## Types

| Type                       | Values                                   | Representation |
|----------------------------|------------------------------------------|----------------|
| `int`                      | 32-bit signed integers                   | primitive      |
| `long`                     | 64-bit signed integers                   | primitive      |
| `float`, `double`          | IEEE 754 numbers                         | primitive      |
| `bool`                     | `true`, `false`                          | primitive      |
| `Duration`                 | lengths of time in milliseconds          | primitive (long) |
| `string`                   | text                                     | reference      |
| `Component`                | formatted text (Adventure component)     | reference      |
| `List<T>`                  | mutable lists                            | reference      |
| `T?`                       | a `T` or `null`                          | reference (boxed if `T` is primitive) |
| `any`                      | any non-null value                       | reference      |
| registry types             | `Player`, `Entity`, `World`, `Location`, ... | reference  |

Registry types come from declarations (the standard library or addons), with
single or multiple supertypes: `Player` is both a `LivingEntity` and a
`CommandSender`. Type names are resolved at compile time; no Bukkit class is
loaded by the compiler.

Locals declared with `let` are immutable, `var` mutable. A type annotation is
optional when the initializer determines the type (`let names: List<string> = []`
needs one, because `[]` alone has no element type).

## Implicit conversions

An implicit conversion is inserted by the binder as an explicit node, so the IR
never converts anything silently.

| From → to                       | Cost | Notes                                   |
|---------------------------------|------|-----------------------------------------|
| identity, subtype → supertype   | 0    | `Player` → `Entity`                     |
| `null` → any `T?`               | 0    |                                         |
| `int` → `long`, `float`, `double`; `long` → `double`; `float` → `double` | 1 | Java's widening, except lossy `long → float` |
| `T` → `T?` for primitive `T`    | +1   | boxing                                  |
| primitive → `any`               | 1    | boxing                                  |
| `string` → `Component`          | 2    | constant text only; parsed as MiniMessage when the script loads |

There are no narrowing conversions: `double` → `int` needs `math.floor(x)`,
`math.round(x)` and friends, and `T?` → `T` needs a null check.

A `string` that is only known at run time never converts to `Component`
implicitly (`TYS0234`), because its content may come from a player and would be
parsed as MiniMessage tags. It has to go into a template (`"{text}"`, inserted as
plain text) or through `text.mini(text)`, which states that the text is trusted.

Costs drive **overload resolution**: among the declarations whose parameters
accept the arguments, the one whose conversions are cheapest for every argument
wins; if no candidate dominates, the call is ambiguous and reported with the
candidates listed.

## Contextual typing

Some expressions take their type from where they are used:

* an integer literal becomes the expected numeric type (`let d: double = 5`);
  on its own it is `int`, or `long` if it does not fit in `int`;
* `[]` becomes a `List<T>` of the expected element type;
* a string template used where a `Component` is expected becomes a
  **component template**: its literal text is MiniMessage and its
  interpolations are inserted as plain text (see
  [ADR 0006](../decisions/0006-message-templates.md)). Used where a `string` is
  expected, the same template is a plain string concatenation.

## Operators

* **Arithmetic** `+ - * / %` on numbers uses binary numeric promotion: the
  result is `double` if either side is `double`, else `float` if either side is
  `float` (but `long` with `float` gives `double`), else `long` if either side is
  `long`, else `int`. Integer division by a literal zero is a compile-time error;
  by a runtime zero, a runtime error.
* **Durations**: `Duration ± Duration`, `Duration * integer`,
  `integer * Duration`, `Duration / integer`, and comparisons between durations.
* **Strings**: `+` concatenates when either side is a `string` (the other side
  is converted with its text form). `string + Component` is an error, because
  it is ambiguous whether the string is MiniMessage.
* **Comparisons** `< <= > >=` work on numbers (after promotion) and durations.
* **Equality** `== !=` works on numbers (after promotion), booleans, durations
  and references. References compare by value (`Objects.equals`): strings by
  content, players by identity of the player. Comparing types that can never be
  equal (for example `string` with `Player`) is an error.
* **Logical** `&& || !` work on `bool` and short-circuit.

## Null safety

A value of type `T` is never null. `T?` may be, and must be checked before use:

```tys
event player.death {
    if killer != null {
        killer.send("You killed {victim.name}")   // killer is Player here
    }
    let name = killer?.name ?? "nobody"           // string
}
```

* `x?.member` evaluates to `null` when `x` is null (the result type is nullable);
* `a ?? b` gives `b` when `a` is null;
* `x != null`, `x is T`, early `return`/`break`/`continue` and `&&`/`||`
  **narrow** the type of a local or parameter for the code that can only run
  when the check held;
* comparing a non-nullable value with `null` is a warning ("never null").

## Type tests and casts

* `x is T` tests the runtime type and narrows `x` to `T` where it holds;
* `x as T` casts and fails at run time if the value is not a `T`;
* `x as? T` gives `null` instead of failing.

## Constants

`const NAME = expression` must be computable at compile time (literals,
arithmetic, string concatenation, other constants). Constants are inlined where
they are used; in component templates their text becomes part of the
MiniMessage source, so `const PREFIX = "<gold>[Server]</gold> "` formats as
expected.

## Diagnostics

Every type error names the expected and the received type where that helps,
points at the exact expression, and suggests close matches for unknown names
("Did you mean: send?"). Codes are stable (`TYS0201` unknown member, ...); the
full list is `DiagnosticCode`.
