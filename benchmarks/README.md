# 📊 Benchmarks

JMH benchmarks of the engine. This project isn't published, and the measurements aren't part of `check`: identical
configurations differed by up to 1.6x between sweeps on a shared machine, so a pass/fail threshold in CI would only be
flaky. Run them by hand when a change could affect speed or allocation, and put the before and after numbers in the
pull request.

What `check` does run is `RunBenchmarkWorkloadTest`, which asserts the shape of the workload below — how many rules
match and how deep the first match sits. Those are counts, not timings, so they can't be flaky.

## 🏃 Running them

```bash
# Everything: 72 run configurations, 2 runs with no rules, 6 load configurations and 4 escaping inputs, each in its
# own JVM.
./gradlew :benchmarks:jmh

# One slice, quickly, with allocation figures
./gradlew :benchmarks:jmh -PjmhArgs="RunBenchmark.run -f 1 -wi 2 -i 3 -p rules=100 -prof gc"

# What a run costs before its first rule
./gradlew :benchmarks:jmh -PjmhArgs="RunBenchmark.Empty -f 5"

# Compiling rule lists
./gradlew :benchmarks:jmh -PjmhArgs="RunBenchmark.Load"

# On another JDK, to compare
./gradlew :benchmarks:jmh -PbenchmarkJdk=25 -PjmhArgs="RunBenchmark.run -p rules=100"
```

Everything after `-PjmhArgs=` goes to JMH, so `-h` lists what it accepts. `-prof gc` is the one to remember:
`gc.alloc.rate.norm` is **bytes allocated per run**. Use `-f 5` whenever you mean to compare two versions; see below.

## 🔬 What's measured

`RunBenchmark.run` is one `run()` with a fresh five-fact store, as a request handler would build it. Its parameters:

| Parameter | Values | Why |
| --- | --- | --- |
| `rules` | 10, 100, 1000 | Per-rule costs dominate a large list; fixed per-run costs dominate a small one |
| `policy` | `firstMatch`, `allMatches` | A first-match engine stops at the first match, so it evaluates fewer conditions — except at 10 rules, where the match is the last one |
| `facts` | `record`, `map` | A language reaches a record's components and a map's keys by different routes |
| `listener` | `none`, `noop`, `logging` | What being called costs, apart from what a listener does; `logging` is `LoggingRuleListener` with DEBUG off |
| `language` | `mvel`, `noop` | See below |

One rule in ten matches, so a run does real work without firing everything, and the matching tenth carries the lowest
priorities, so a first-match run evaluates the nine tenths above it before it stops. A rule's threshold falls by one
for each step down the priority order and crosses the facts' score nine tenths of the way along, which keeps both
figures at every size: 1 of 10, 10 of 100 and 100 of 1,000 match, and a first-match run evaluates 10, 91 and 901
conditions.

`facts` is inert for `language=noop`. `NoopLanguage` reads the `score` fact and nothing else, so for those
configurations the parameter only changes how the fact store is built. That's deliberate: the noop language is the
cheap baseline, and making it read `applicant` would spend the comparison it exists for. The MVEL conditions read two
of the applicant's properties, so the `record` and `map` values differ where it matters.

`listener=logging` registers a `LoggingRuleListener`. The benchmarks log through slf4j-simple at its default INFO
level, so it measures the listener with DEBUG off, the usual setting.

`RunBenchmark.Empty` is one `run()` of an engine with no rules: what every run pays before its first rule, such as
borrowing a copy of the rules and giving it back, and checking at its start that the stack has room for that. A change
to that fixed cost disappears into `RunBenchmark.run`'s rules, so measure it here. With `copies=shared` every run shares
one set of sessions and holds no permit; with `copies=kept` each run borrows the one kept MVEL copy under
`maxCopies(1)`, with the engine's permit, and gives both back.

`RunBenchmark.Load` measures a warm reload: `load()` of the whole rule list once the JVM has warmed up, after 100
earlier loads. Each load fills an empty engine, so retiring the rules a reload replaces isn't counted. A JVM's first
load costs several times more and isn't measured. Over three sweeps with `-f 3`, on one machine with JDK 21.0.7, its
error bars were under 2% for MVEL at 100 and 1,000 rules, and 7% to 23% for the other four configurations, whose
loads take about a millisecond or less.

`EscapeBenchmark` measures the escaping every failure's message goes through, on 999 characters: plain text, a
zero-width space or a control character throughout, and Hebrew with a right-to-left mark every twentieth character.

## 🧭 Why a second language

`NoopLanguage` is the cheapest language that still does a rule's work: a condition compares one fact with a number,
an action returns one property. Running the same rule list through it and through MVEL separates **what the engine
costs** from **what an expression language costs**. Without that split, a change to the engine disappears into MVEL's
numbers — at 100 rules, MVEL is most of both the time and the allocation.

For scale, what a run allocates with the cheapest language, including the fresh five-fact store the benchmark builds
for each run and the result each noop action returns: with `language=noop`, `listener=none`, `policy=allMatches` and
`facts=record`, a run allocated 3,960 bytes at 10 rules and 9,232 at 100, so about 3,400 bytes per run plus 59 bytes
per rule (a straight line through those two points).

Those are medians of five forks on one machine with JDK 21.0.7. The forks agreed to within 240 bytes at 10 rules and
80 at 100.

## 🚧 Reading the numbers

- **Latency between machines means nothing.** Compare a before and an after measured in the same sweep, on the same
  machine, with nothing else running. Differences under about 15% are noise.
- **Allocation is reproducible within a fork, but not across forks.** `gc.alloc.rate.norm` repeats to within a byte
  across the iterations of one JVM, and can differ between forks of the *same* build, because each JVM makes its own
  inlining decisions and escape analysis follows them. In the 10-rule measurement above, the five forks settled at
  3,744 to 3,984 bytes per run. A single `-f 1` run therefore produces a number that looks exact and isn't
  comparable. **Use `-f 5` for any before-and-after claim**, measure the baseline from a worktree of the other commit
  with identical flags, and quote the error bar.
- **The first run after `load()` is not measured here.** It builds the first compiled copy, which costs far more than
  a steady-state run. JMH's warmup absorbs it.
