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
logged once, by the nested run, also when it's found only among suppressed exceptions, within the
[glossary's limit](glossary.md#fatal-error); otherwise each run logs its own.

**A nested run that stops at an earlier deadline of its own fails the outer rule.** The outer run isn't past its
deadline, so its rule fails with `a nested run() failed: run() passed its deadline ...`, naming the outer rule, and
the stop is in its cause chain.

**A nested run that failed with an `Error` in its cause chain fails the outer rule as well,** even when the outer
run is past its deadline or interrupted; see [What stops a run](stopping-runs.md#-what-stops-a-run). The outer rule
fails with `a nested run() failed: ...`, with the nested failure in its cause chain; `run()` rethrows a fatal
`Error` instead. Each rule it passes through gets one `onError`, each run one `onRunError`.

**Building the JVM's first engine initializes the classes with a static initializer that a run's own steps use.** A
run deep in a stack, as a nested run can be, would otherwise be the first to use them, and a `StackOverflowError`
while a class runs its static initializer leaves that class unusable for the life of the JVM. A language's own
classes, a listener's or your code's aren't among them.

The engine's tests check this in a new JVM. They give engines first runs with map and bean outputs, declared,
mistyped and missing facts, every listener callback, a failing condition, a write to read-only facts, and nested runs
that throw a fatal error or pass their deadline. They fail if those runs initialize any class with a static
initializer, the engine's, the JDK's or a library's, other than the hidden classes the JDK makes for method handles,
which have no name to initialize ahead of time.

A path the tests don't take may still initialize one. The engine names the JDK classes it initializes from first runs
on JDK 21, 25 and 26, and skips any a JDK doesn't have, so another JDK release may use one it doesn't name. In a
native image it names none, leaving those classes to the image.

## 🪵 What is logged

A nested `run()` or `load()` logs its own failure, rejected facts and a fatal `Error` included. What it logged isn't
logged again, even when a listener or a language's `close()` started it: the rule, output supplier or `load()` around
it says `a nested run() failed: <innermost failure>` or `a nested load() failed: ...`, and a listener's or a
`close()`'s WARN is left out. A listener's stack trace is still logged at DEBUG.

The thread remembers 32 failures nested runs threw as is, 32 rule failures that runs threw, and 32 fatal `Error`s,
until its outermost run, `load()`, `validate()` or `close()` ends. Then, or past 32, one thrown as is may be logged
again. A rule's failure that `run()` threw as a `RuleExecutionException` is never logged again, but past 32 in the same
outermost run a wrapper notes it as a nested run's, as described below. A `RuleExecutionException` your own code builds
is news, so it's logged.

A run on another thread, such as one an action hands to an executor and waits for, isn't nested: it logs its failure on
its own thread. A rule's failure, which `run()` throws as a `RuleExecutionException`, isn't logged again. The waiting
rule's message still reads `a nested run() failed: <innermost failure>`: the engine recognizes a failure `run()`
threw, whichever thread threw it, and always reads such a failure from another thread as a nested run's.

What `run()` throws as is, a fatal `Error` or an `IllegalArgumentException` for rejected facts, for example, is logged
again as the waiting rule's failure. When the action lets the `ExecutionException` from `Future.get()` through, the line
quotes that exception's message, which is the error's `toString()`:
`Failed to execute action for rule 'outer-rule': java.lang.InternalError: inner fatal`. When the action rethrows the
error itself, the line has no class name: `Failed to execute action for rule 'outer-rule': inner fatal`.

An exception of your own with a message of its own, wrapped around a nested failure, is news, so it's logged: at ERROR
for a rule's failure, the output supplier's, a language's or a rule that fails to compile in `load()`, and at WARN for
a listener or a `close()`.

The wrapper's message comes first, shortened and escaped, and the nested failure follows as a note, shortened to 1,000
characters, such as
`Failed to execute action for rule 'r': pricing failed (after a nested run() failed: <innermost failure>)`. When
several wrappers have one, the outermost is named. A run further out names that logged line, not the innermost failure.

That note usually means a run the wrapping code started logged the failure; the exceptions are below. When a run that
had already ended logged it, the note says the failure was logged already, as for a fatal `Error` below:
`Failed to execute action for rule 'r': pricing failed (caused by <innermost failure>, already logged)`.

The run that logged it may be an earlier sibling whose failure your code kept, at any depth, or, for a rule's failure
`run()` threw, a run in an earlier outermost run on the thread, whatever the count. The failure still has one line of
its own.

A rule's failure that `run()` threw still gets the nested run's note when it came from another thread, was built
outside any run, was deserialized, or is past the 32 the thread remembers in the same outermost run. Rejected facts or
another failure thrown as is, kept from an earlier outermost run, get no nested-run note: the wrapper's words stand
alone, apart from a root cause they would hide, as the thread forgot them when that run ended.

A failure a nested run logged, rejected facts included, also gets the nested run's note when a later rule in the same
run throws it, though an earlier rule started the run that logged it: the engine tells where a failure was logged run
by run, not rule by rule. That holds with or without words of its own; see
[#961](https://github.com/brantunger/unruly-engine/issues/961).

Code that throws a failure that gets the `already logged` note above on with no words of its own, as is (`throw e`) or
in `new RuntimeException(e)`, has it read as that failure followed by `(already logged)`:
`Failed to execute action for rule 'second-rule': <innermost failure> (already logged)`. The failure's text is
shortened as any nested failure's is, and the note follows its `(N more characters)`.

Only what that code throws reads so. A run further out, around the run whose rule threw it, still names the failure
as a nested run's: `Failed to execute action for rule 'z-rule': a nested run() failed: ...`. The cases above that get
the nested run's note read `a nested run() failed: ...` here too. The failure is still logged once, and the wrapper
isn't logged.

Rejected facts or another failure thrown as is, kept from an earlier outermost run, read by their own text when thrown
on as is, or by the wrapper's message, which is their `toString()`, when wrapped in `new RuntimeException(e)`. They
are logged again, as the thread forgot them.

The nested failure's message is mostly escaped already, so where it's shortened, as a note or as a failed `load()` or
rejected facts, the cut never splits an escape: the whole escape is left out, so up to 5 fewer characters show. Its
`(N more characters)` counts the characters of the message as the nested failure holds it: escaped where the engine
escaped it, a language's raw text otherwise. It matches the nested line's count only when that line shows the same
text.

A language's raw text that reaches the same cut, such as its rejection of a fact name, is cut before anything that
reads as an escape too, so it can show up to 5 fewer characters than the nested line.

A wrapper that adds nothing is left out, and the nested text stands alone: one with no message, one whose message is
its cause's `toString()`, as `new RuntimeException(cause)` makes, or its cause's message, or the nested failure's text.
In MVEL, MVEL's own exception around an engine failure from a method the rule calls is left out as well.

A wrapper that puts words around the nested text, as `"order 42: " + e.getMessage()` does, is news: it's logged with
the nested failure as a note, so the nested text appears twice. The engine's own wrappers, such as a rule that fails to
compile, are left out whatever their text, but only while the call that built them is in progress: one a top-level
`load()` threw, rethrown later from inside a run, is news.

Around a nested fatal `Error`, the note is the `Error`'s class and message, such as
`Failed to execute action for rule 'r': audit failed (after a nested run() failed: java.lang.OutOfMemoryError: ...)`,
or `(after a nested load() failed: ...)` for a nested `load()`, and the call still rethrows the `Error` itself.

As for a failure that isn't fatal, a fatal `Error` logged already, but not below the code that wrapped it, such as by
an earlier nested run, gets `(caused by java.lang.OutOfMemoryError: ..., already logged)` instead when it's in the
cause chain, or
`(with suppressed java.lang.OutOfMemoryError: ..., already logged)` when it's found among the suppressed
exceptions or what they lead to, within the [glossary's limit](glossary.md#fatal-error).

A listener's first fatal `Error` in a callback, `onError` included, logs such a wrapper at ERROR, as
`A listener threw <class> in <callback>: ...`; wrapping the failure a listener was told of adds nothing. From `onError`
closing a rule's own fatal `Error`, `run()` rethrows the rule's, and the nested one is kept in its `getSuppressed()`
and in that of the exception `onRunError` gets.

A `close()` logs a wrapper around a nested fatal `Error` at WARN, with the note. So does a listener for any but the
first fatal `Error` thrown in a callback, as `<class>: <message>`, without the note.
