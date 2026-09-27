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
output supplier, or a listener callback up to and including `afterRun` and `onRunError`.

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

The thread remembers 32, and a fatal `Error`, until its outermost run, `load()`, `validate()` or `close()` ends; then,
or past 32, one thrown on may be logged again.

An exception of your own with a message of its own, wrapped around a nested failure that isn't a fatal `Error`, is
news, so it's logged: at ERROR for a rule's failure, the output supplier's or a rule that fails to compile in `load()`,
and at WARN for a listener or a `close()`.

The wrapper's message comes first, shortened and escaped, and the nested failure follows as a note, shortened to 1,000
characters before it's escaped, such as
`Failed to execute action for rule 'r': pricing failed (after a nested run() failed: <innermost failure>)`. When
several wrappers have one, the outermost is named. A run further out names that logged line, not the innermost failure.

A wrapper that adds nothing is left out, and the nested text stands alone: one with no message, one whose message is
its cause's `toString()`, as `new RuntimeException(cause)` makes, or one whose message already contains the nested
failure's text. In MVEL, MVEL's own exception around an engine failure from a method the rule calls is left out as well.

A wrapper around a nested fatal `Error` isn't logged at ERROR: the call it was thrown in rethrows the `Error`, which the
nested run logged. From `onError` closing a rule's own fatal `Error`, `run()` rethrows the rule's, and the nested one is
kept in the `getSuppressed()` of the exception `onRunError` gets. A `close()` still logs such a wrapper at WARN, as does
a listener for any but the first fatal `Error` thrown in a callback.
