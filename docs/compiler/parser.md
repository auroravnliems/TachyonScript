# Parser

`dev.tachyonscript.language.parser.Parser` turns the token list of one file into a
syntax tree (`SourceUnit`). It is hand written (see
[ADR 0002](../decisions/0002-hand-written-parser.md)): recursive descent for
declarations and statements, precedence climbing (Pratt) for expressions.

## Declarations

```text
source      = { declaration }
declaration = event | function | const | global | record | command | placeholder
            | lifecycle | task
module      = "module" qualifiedName
import      = "import" qualifiedName [ "as" name ]
            | "import" "{" name { "," name } "}" "from" qualifiedName
event       = "event" qualifiedName block                 e.g. event player.join { ... }
function    = "function" name "(" [ param { "," param } ] ")" [ ":" type ] block
param       = name ":" type
const       = "const" name [ ":" type ] "=" expression
global      = [ "persistent" | "playerdata" ] ("let" | "var") name [ ":" type ] "=" expression
record      = "record" name "(" fields ")" [ "{" methods "}" ]
command     = annotations "command" qualifiedName [ "(" parameters ")" ] block
placeholder = "placeholder" name block
lifecycle   = "on" ("load" | "unload") block
task        = annotations ("every" duration | "at" dailyTime) block
```

The optional module name comes first, then imports, then the declarations. 0.2
implements all declarations above. Annotations are checked by the binder for the
declaration they precede. Command parameters support defaults and a final greedy
`string...` parameter; record fields support trailing defaults.

The declarations `gui`, `eventtype`, `script` and `cooldown`, and the statement
`wait`, produce `TYS0109` with alternatives (Menu, annotations, `after`). A keyword
from another language at the start of a declaration (`fn`, `def`, `func`,
`on`, `val`, ...) gets a "did you mean" for the TachyonScript keyword.

## Statements

```text
let name [: type] = expression          immutable local
var name [: type] = expression          mutable local
target = expression                     also += -= *= /= %=
target++ | target-- | ++target | --target
target &= expression                    also |= ^= <<= >>= >>>=
if condition { ... } [ else if ... ] [ else { ... } ]
while condition { ... }
for item in expression { ... }          lists and ranges (a..b, a..<b)
for key, value in expression { ... }    maps
break | continue | return [expression]
expression                              calls, as statements
switch expression { case value -> statement; default -> statement }
try block [ catch [name] block ] [ finally block ]
throw expression
after duration [ for entity ] block
every duration [ for entity ] block
async block | sync block
```

Statements end at a newline or `;`. A newline inside `(...)` or `[...]` does
not end a statement, and a line starting with `.`, `?.`, `&&`, `||` or `??`
continues the previous expression.

## Expression precedence

Lowest to highest, using the actual `BinaryOperator.Precedence` levels.
All binary operators are left-associative except `??`
(right-associative) and the comparisons, `is` and ranges, which cannot be
chained (`a < b < c` is an error with a suggestion to use `&&`). The conditional
`condition ? yes : no` is right-associative. A range is only valid in a `for` or
membership test; it is not a first-class value.

| Level | Operators                  | Associativity | Example                     |
|-------|----------------------------|---------------|-----------------------------|
| 0     | `? :`                      | right         | `ok ? 1 : 0`                |
| 1     | `\|\|`                     | left          | `a \|\| b`                  |
| 2     | `&&`                       | left          | `a && b`                    |
| 3     | `\|`                       | left          | `mask \| flag`              |
| 4     | `^`                        | left          | `mask ^ flag`               |
| 5     | `&`                        | left          | `mask & flag`               |
| 6     | `==` `!=`                  | none          | `a == b`                    |
| 7     | `<` `<=` `>` `>=` `is` `!is` `in` `!in` | none | `x is Player`       |
| 8     | `??`                       | right         | `name ?? "unknown"`         |
| 9     | `..` `..<`                 | none          | `0..<10`                    |
| 10    | `<<` `>>` `>>>`            | left          | `mask << 1`                 |
| 11    | `+` `-`                    | left          | `a + b`                     |
| 12    | `*` `/` `%`                | left          | `a * b`                     |
| 13    | `as` `as?`                 | left          | `entity as? Player`         |
| 14    | prefix `-` `!` `~` `++` `--` | —           | `!player.op`                |
| 15    | postfix `.` `?.` `()` `[]` `++` `--` | left | `player?.name`, `list[0]` |

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
* maps `{"gold": 20}`, `{}` (key/value types come from context);
* lambdas `n => n * 2`, `(n: int) => { return n * 2 }`, and function values;
* switch expressions `switch x { case 0 -> "zero"; default -> "other" }`;
* `true`, `false`, `null`.

## Error recovery and limits

After an error the parser skips to the next statement boundary (a new line
that starts a statement keyword, `;`, `}` or a declaration keyword) so that
one mistake produces one diagnostic. Unclosed brackets are reported with a
label on the opening bracket ("this '(' is never closed").

Block nesting is limited to 100 levels and expression depth to 256, so hostile
input cannot overflow the stack of a server thread; both limits produce a
normal diagnostic (`TYS0106`).
