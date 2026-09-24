# Parser

`dev.tachyonscript.language.parser.Parser` turns the token list of one file into a
syntax tree (`SourceUnit`). It is hand written (see
[ADR 0002](../decisions/0002-hand-written-parser.md)): recursive descent for
declarations and statements, precedence climbing (Pratt) for expressions.

## Declarations

```text
source      = { declaration }
declaration = event | function | const
event       = "event" qualifiedName block                 e.g. event player.join { ... }
function    = "function" name "(" [ param { "," param } ] ")" [ ":" type ] block
param       = name ":" type
const       = "const" name [ ":" type ] "=" expression
```

`module`, `import`, `command`, `playerdata`, `persistent`, `gui`, `cooldown` and
`on load` / `on unload` are recognised and reported as *not supported yet*
(they are planned), instead of producing confusing syntax errors. A keyword
from another language at the start of a declaration (`fn`, `def`, `func`,
`on`, `val`, ...) gets a "did you mean" for the TachyonScript keyword.

## Statements

```text
let name [: type] = expression          immutable local
var name [: type] = expression          mutable local
target = expression                     also += -= *= /= %=
if condition { ... } [ else if ... ] [ else { ... } ]
while condition { ... }
for item in expression { ... }          lists and ranges (a..b, a..<b)
break | continue | return [expression]
expression                              calls, as statements
```

Statements end at a newline or `;`. A newline inside `(...)` or `[...]` does
not end a statement, and a line starting with `.`, `?.`, `&&`, `||` or `??`
continues the previous expression.

## Expression precedence

Lowest to highest. All binary operators are left-associative except `??`
(right-associative) and the comparisons, `is` and ranges, which cannot be
chained (`a < b < c` is an error with a suggestion to use `&&`).

| Level | Operators                  | Associativity | Example                     |
|-------|----------------------------|---------------|-----------------------------|
| 1     | `\|\|`                     | left          | `a \|\| b`                  |
| 2     | `&&`                       | left          | `a && b`                    |
| 3     | `==` `!=`                  | none          | `a == b`                    |
| 4     | `<` `<=` `>` `>=` `is`     | none          | `x is Player`               |
| 5     | `??`                       | right         | `name ?? "unknown"`         |
| 6     | `..` `..<`                 | none          | `0..<10`                    |
| 7     | `+` `-`                    | left          | `a + b`                     |
| 8     | `*` `/` `%`                | left          | `a * b`                     |
| 9     | `as` `as?`                 | left          | `entity as? Player`         |
| 10    | prefix `-` `!`             | —             | `!player.op`                |
| 11    | postfix `.` `?.` `()` `[]` | left          | `player?.name`, `list[0]`   |

`??` binds tighter than the comparisons, so `a ?? b == c` means
`(a ?? b) == c`.

## Literals

* numbers as described in [`lexer.md`](lexer.md): `42`, `42L`, `1.5`, `1.5f`,
  `0xFF`, `1_000_000`;
* durations: a number followed by a unit word, `5 seconds`, `1 tick`,
  `2 minutes` (units: millisecond(s), tick(s) = 50 ms, second(s), minute(s),
  hour(s), day(s)); a duration is a `Duration` value in milliseconds;
* strings `"..."` with escapes `\n \t \r \0 \\ \" \' \{ \} \uXXXX`, and
  interpolation `"Hello {player.name}!"`;
* lists `[1, 2, 3]`, `[]` (the element type comes from the context);
* `true`, `false`, `null`.

## Error recovery and limits

After an error the parser skips to the next statement boundary (a new line
that starts a statement keyword, `;`, `}` or a declaration keyword) so that
one mistake produces one diagnostic. Unclosed brackets are reported with a
label on the opening bracket ("this '(' is never closed").

Block nesting is limited to 100 levels and expression depth to 256, so hostile
input cannot overflow the stack of a server thread; both limits produce a
normal diagnostic (`TYS0106`).
