# Benchmarks

TachyonScript makes no performance claim that is not backed by a benchmark in
[`tachyon-benchmarks`](../tachyon-benchmarks) that anyone can run:

```sh
./gradlew :tachyon-benchmarks:jmh                                  # everything, default settings
./gradlew :tachyon-benchmarks:jmh -Pjmh.include=Dispatch           # one class
./gradlew :tachyon-benchmarks:jmh -Pjmh.args="-f 1 -wi 3 -i 5"     # quicker, less precise
```

Results are written to `tachyon-benchmarks/build/jmh-results.json`.

## Latest results

Measured on 2026-09-24 with a **shortened run** (1 fork, 3 × 1 s warm-up, 5 × 1 s
measurement) on a shared cloud VM: 4 vCPUs of an Intel Xeon at 2.1 GHz, OpenJDK
21.0.10. Shared virtual machines are noisy; treat the numbers as orders of magnitude
and compare rows of the same run, not absolute values across machines. Lower is
better.

| Benchmark | Score | Error (99.9%) | Unit |
|-----------|------:|------:|------|
| `TemplateBenchmark.preParsedTemplate` | 264 | ± 34 | ns/op |
| `TemplateBenchmark.miniMessageWithPlaceholders` | 8,222 | ± 798 | ns/op |
| `TemplateBenchmark.miniMessageConcatenated` | 6,650 | ± 1,518 | ns/op |
| `DispatchBenchmark.scriptHandler` | 44.1 | ± 5.5 | ns/op |
| `DispatchBenchmark.javaHandler` | 0.70 | ± 0.09 | ns/op |
| `DispatchBenchmark.eventWithoutHandlers` | 1.61 | ± 0.08 | ns/op |
| `InterpreterBenchmark.scriptIntegerLoop` (1,000 iterations) | 23.4 | ± 3.9 | µs/op |
| `InterpreterBenchmark.javaIntegerLoop` (1,000 iterations) | 0.29 | ± 0.03 | µs/op |
| `InterpreterBenchmark.scriptDoubleLoop` (1,000 iterations) | 25.1 | ± 2.7 | µs/op |
| `InterpreterBenchmark.javaDoubleLoop` (1,000 iterations) | 1.27 | ± 0.06 | µs/op |
| `InterpreterBenchmark.scriptRecursiveCalls` (fib 20, 21,891 calls) | 591 | ± 83 | µs/op |
| `InterpreterBenchmark.javaRecursiveCalls` (fib 20) | 26.4 | ± 2.1 | µs/op |
| `CompilerBenchmark.compileScript` (80 declarations, ~900 lines) | 1,863 | ± 2,020 | µs/op |

## What the numbers say

**Messages.** Sending a message with two values through a pre-parsed template
(`"<gold>[Shop]</gold> <gray>{player.name} bought <white>{amount}</white> diamonds."`)
takes about 0.26 µs. Parsing the equivalent MiniMessage for every message, the way
plugins usually do it, takes 6.6–8.2 µs — roughly 25–30 times longer — and the
concatenating variant is also unsafe (see [ADR 0006](decisions/0006-message-templates.md)).

**Event handlers.** Running a small handler (two property reads, two comparisons,
no message) through the engine costs about 44 ns; an event that no script handles
costs about 1.6 ns inside the engine, and on a server no Bukkit listener is even
registered for it. The plain Java row is not a realistic listener: the JIT sees the
whole method and folds most of it away, so it is a lower bound, not a target. For
scale: 100 players each firing 20 move events per second is 2,000 handler runs per
second, about 0.1 ms of CPU time per second at this cost.

**Interpreter.** Tight arithmetic loops run about 20–80 times slower than the same
code compiled by the JIT, and script-to-script calls about 22 times slower
(~27 ns per call). That is expected for an interpreter; typical scripts spend their
time in the natives they call (Bukkit), not in arithmetic. The planned optimizer
(superinstructions such as add-immediate and compare-and-branch) and the bytecode
backend target this gap.

**Compiler.** A 900-line script compiles in about 2 ms (with high variance on this
machine). Reloads only recompile changed files.

## History

| Change | Effect |
|--------|--------|
| The watchdog reads the clock at the first loop check instead of at the start of every execution (`System.nanoTime()` costs ~27 ns on this VM). | `scriptHandler`: 77.6 ± 11.7 → 44.3 ± 6.2 ns/op |
| MiniMessage templates are parsed without compaction, so markers survive and templates are pre-parsed (before, every template silently fell back to parsing per message). | Found by a unit test before measurement; the benchmark measures the fixed version. |

## Adding a benchmark

Every benchmark compares against a baseline (plain Java, or the usual way to do the
same thing) and documents what is and is not included in the measured operation.
Scripts are compiled through the real pipeline in `@Setup`, never in the measured
method.
