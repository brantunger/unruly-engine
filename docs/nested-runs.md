# 🪆 Nested runs

This page says what a run started inside another run inherits, how its stops and failures reach the run around it,
and which log line names a failure.

**Who it's for:** application developers whose conditions, actions, output suppliers or listeners start runs, and
language authors.
**You'll be able to:** predict which deadline stops a nested run, what the outer rule fails with, and where a nested
failure is logged.
**Before you start:** [Stopping a run](stopping-runs.md).

[← Documentation index](README.md)

- [What counts as nested](#-what-counts-as-nested)
- [Stops and failures](#-stops-and-failures)
- [What is logged](#-what-is-logged)

---

## 🧭 What counts as nested

A nested run is a run started on the same thread while another run is in progress: from a condition, an action, the
output supplier, or a listener callback up to and including `afterRun` and `onRunError`. A run started before or after
those also inherits the outer run's deadline, but its `parent()` doesn't name the outer run: it names the outer run's
own parent, if it has one. That is a run started from a fact's `getValue()` while the outer run reads its facts, a
language's `newSession()` while it gets its compiled copy, or a session's `close()` while it gives the copy back.

That includes the `beforeRun` and `onRunError` of a run that stopped while waiting for a compiled copy, or while reading
the engine's rules again: it never ran a rule, but a run started from its callbacks inherits its deadline, if it had
one, and names it as its `parent()` on the same engine. Such a run holds no copy, but a run started from its callbacks
doesn't wait either: with that run's deadline passed, or the same interrupt on the thread, it takes a free copy or fails
at once.

- **It stops at whichever deadline comes first,** its own or the outer run's, on any engine. It sees the same
  interrupt, because the interrupt status belongs to the thread.
- **`parent()` names the outer run only on the same engine.** A run on another engine has no parent, although it
  still inherits the deadline; see [Callbacks](listeners-and-logging.md#-callbacks).
- **A nested run never waits for a copy** while a run on its thread holds or is getting one; see
  [Runs that don't wait](compiled-copies.md#runs-that-dont-wait).

## ⏳ Stops and failures

**A stop that leaves the expression stops the outer run too.** When the outer run is past its deadline or
interrupted, its rule gets `onError` with a stop, and the nested stop is in the cause chain of `getSuppressed()`'s
exception, which is what the expression threw. When both stopped for the same interrupt or deadline, the stop is
logged once, by the nested run; otherwise each run logs its own.

**A nested run that stops at an earlier deadline of its own fails the outer rule.** The outer run isn't past its
deadline, so its rule fails with `a nested run() failed: run() passed its deadline ...`, naming the outer rule, and
the stop is in its cause chain.

**A nested run that failed with an `Error` in its cause chain fails the outer rule as well,** even when the outer
run is past its deadline or interrupted; see [What stops a run](stopping-runs.md#-what-stops-a-run). The outer rule
fails with `a nested run() failed: ...`, with the nested failure in its cause chain; `run()` rethrows a fatal
`Error` instead. Each rule it passes through gets one `onError`, each run one `onRunError`.

## 🪵 What is logged

A nested `run()` or `load()` logs its own failure, rejected facts and a fatal `Error` included. What it logged isn't
logged again, even when a listener or a language's `close()` started it: the rule, output supplier or `load()` around
it says `a nested run() failed: <innermost failure>` or `a nested load() failed: ...`, and a listener's or a
`close()`'s WARN is left out. A listener's stack trace is still logged at DEBUG.

The thread remembers 32 failures, and 32 fatal `Error`s, until its outermost run, `load()`, `validate()` or `close()`
ends; then, or past 32, one thrown on may be logged again.

A run on another thread, such as one an action hands to an executor and waits for, isn't nested: it logs its failure on
its own thread. A rule's failure, which `run()` throws as a `RuleExecutionException`, isn't logged again. The waiting
rule's message still reads `a nested run() failed: <innermost failure>`: the engine recognizes a failure `run()`
threw, whichever thread threw it.

What `run()` throws as is, a fatal `Error` or an `IllegalArgumentException` for rejected facts, for example, is logged
again as the waiting rule's failure. When the action lets the `ExecutionException` from `Future.get()` through, the line
quotes that exception's message, which is the error's `toString()`:
`Failed to execute action for rule 'outer-rule': java.lang.InternalError: inner fatal`. When the action rethrows the
error itself, the line has no class name: `Failed to execute action for rule 'outer-rule': inner fatal`.

An exception of your own with a message of its own, wrapped around a nested failure, is news, so it's logged: at ERROR
for a rule's failure, the output supplier's, a language's or a rule that fails to compile in `load()`, and at WARN for
a listener or a `close()`.

The wrapper's message comes first, shortened and escaped, and the nested failure follows as a note, shortened to 1,000
characters before it's escaped, such as
`Failed to execute action for rule 'r': pricing failed (after a nested run() failed: <innermost failure>)`. When
several wrappers have one, the outermost is named. A run further out names that logged line, not the innermost failure.

A wrapper that adds nothing is left out, and the nested text stands alone: one with no message, one whose message is
its cause's `toString()`, as `new RuntimeException(cause)` makes, or its cause's message, or the nested failure's text.
In MVEL, MVEL's own exception around an engine failure from a method the rule calls is left out as well.

A wrapper that puts words around the nested text, as `"order 42: " + e.getMessage()` does, is news: it's logged with
the nested failure as a note, so the nested text appears twice. The engine's own wrappers, such as a rule that fails to
compile, are left out whatever their text, but only while the call that built them is in progress: one a top-level
`load()` threw, rethrown later from inside a run, is news.

Around a nested fatal `Error`, the note is the `Error`'s class and message, such as
`Failed to execute action for rule 'r': audit failed (after a nested run() failed: java.lang.OutOfMemoryError: ...)`,
and the call still rethrows the `Error` itself. One logged already, but not below the code that wrapped it, such as by
an earlier nested run, gets `(caused by java.lang.OutOfMemoryError: ..., already logged)` instead.

A listener's first fatal `Error` in a callback, `onError` included, logs such a wrapper at ERROR, as
`A listener threw <class> in <callback>: ...`; wrapping the failure a listener was told of adds nothing. From `onError`
closing a rule's own fatal `Error`, `run()` rethrows the rule's, and the nested one is kept in its `getSuppressed()`
and in that of the exception `onRunError` gets.

A `close()` logs a wrapper around a nested fatal `Error` at WARN, with the note. So does a listener for any but the
first fatal `Error` thrown in a callback, as `<class>: <message>`, without the note.
