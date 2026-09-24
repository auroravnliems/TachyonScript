# ADR 0002: Hand-written parser

## Context

Parser generators (ANTLR, JavaCC) produce parsers quickly, but they add a
runtime dependency or generated code, and their error messages are generic
("mismatched input"). TachyonScript is used by server administrators, often
without programming experience, so error messages are a product feature. The
grammar is small and designed to be parsed with one token of lookahead in
almost all places.

## Decision

* A **hand-written lexer** produces a token list plus comments (kept as trivia
  for documentation comments and a future formatter). Newlines are statement
  terminators only when the innermost open bracket is a brace, so calls and
  lists can span lines. String interpolation is lexed into template tokens, so
  nothing about interpolation is parsed at run time.
* A **recursive-descent parser** handles declarations and statements, and a
  **Pratt parser** (precedence climbing) handles expressions. The precedence
  table is in [`../compiler/parser.md`](../compiler/parser.md).
* Error recovery synchronizes on statement boundaries (a new line starting a
  statement keyword, `;`, `}` and declaration keywords) and suppresses cascades.
  The parser explains common mistakes (`if x = 5`, `for (x in list)`, `fn`
  instead of `function`, unclosed parentheses with the opening location).
* Nesting depth and expression depth are bounded, so hostile input cannot
  overflow the stack of a server thread.

## Consequences

* No dependencies and no generated code; the parser is plain Java that can be
  debugged and profiled like the rest of the compiler.
* Diagnostics can be as specific as needed, which is the main reason for this
  decision.
* Grammar changes need code changes and tests instead of editing a grammar
  file. The grammar is documented by the parser tests and `parser.md`.
