# Lexer

`dev.tachyonscript.language.lexer.Lexer` turns a `SourceFile` into a `LexResult`:
a list of tokens and, separately, the comments (kept as trivia for
documentation comments `///` and a future formatter). It is a single hand-written
pass with no regular expressions.

## Tokens

* **Identifiers**: ASCII letters, digits and `_`, not starting with a digit.
  Other letters are rejected with a hint to use strings for such text.
* **Reserved keywords**: `let var const function return if else while for in
  break continue true false null is as` and, reserved for planned error
  handling, `try catch throw`. Words such as `event`, `module`, `import` and
  `command` are *contextual*: they are keywords only where a declaration can
  start, so they remain usable as names elsewhere (`event.cancel()`).
  After `.` any word is a member name, which is how `block.break` works.
* **Numbers**: `42`, `0xFF`, `0b1010`, `42L` (long), `1.5` or `1.5d` (double),
  `1.5f` (float), `1e3`, with `_` allowed between digits (`1_000_000`). An
  integer literal that does not fit in `int` has type `long`; one that does not
  fit in `long`, and floating-point literals that overflow or underflow, are
  errors. A number directly followed by letters (`12ab`) is an error.
  `1..10` is lexed as a range, not as `1.` followed by `.10`.
* **Strings**: `"..."` on one line. Escapes: `\n \t \r \0 \\ \" \' \{ \} \uXXXX`.
  An unknown escape is an error listing the valid ones.
* **Templates**: a string containing `{expression}` is lexed as
  `TEMPLATE_START`, the expression's tokens, `TEMPLATE_MIDDLE`, ...,
  `TEMPLATE_END`. The parser builds a template expression from them; nothing
  about interpolation is left for the runtime to parse. `{}` is an error
  (write `\{` for a literal brace).

## Newlines

A `NEWLINE` token is emitted only when the innermost open bracket is `{` (or at
top level). Inside `(...)` and `[...]` line breaks are insignificant, so

```tys-body
player.send(
    "Hello {player.name}"
)
```

needs no special syntax. Consecutive newlines collapse into one token.

## Errors

The lexer reports unterminated strings, invalid characters (with the Unicode
code point when the character is invisible), invalid escapes and out-of-range
numbers, and continues after each error so that the parser still sees the
rest of the file.
