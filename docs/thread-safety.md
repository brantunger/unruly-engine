# 🧵 Thread safety

An engine is designed to be configured once and then shared by every thread in your application.

[← Back to README](../README.md)

- [At a glance](#-at-a-glance)
- [Reloading rules while running](#-reloading-rules-while-running)
- [What you must keep thread-safe](#-what-you-must-keep-thread-safe)
- [Compiled rules and concurrent runs](#-compiled-rules-and-concurrent-runs)

---

## 👀 At a glance

| Method | Thread-safe? | When to call it |
| --- | :---: | --- |
| `RulesEngineBuilder` methods and `build()` | ❌ | During setup, on one thread. The engine a builder builds is thread-safe, and its imports, languages and listeners can't change afterwards. |
| `load()` | ✅ | During setup, and again at any time to reload. When several threads call it at once, the last to finish wins. |
| `run()` | ✅ | From any number of threads, once `load()` has completed. |
| `close()` | ✅ | Once the engine is no longer needed. Runs in progress finish first. |

**Stopping a run:** interrupting the thread a run is on, or giving the run a timeout, stops it **between rules** —
before each condition and before each action. It can't stop an MVEL expression that is already running, so a rule
that loops for ever still blocks its thread; run rules you don't trust in a process of their own. See
[Stopping a run](error-handling.md#-stopping-a-run).

## 🔄 Reloading rules while running

`load()` compiles the whole new list first, then swaps it in with a single atomic write, together with the
fact-name checks of the languages its rules use.

```mermaid
sequenceDiagram
    participant A as Request thread
    participant E as RulesEngine
    participant B as Reload thread
    A->>E: run(facts)
    activate E
    Note over E: Uses rule list v1
    B->>E: load(v2)
    Note over E: Compiles v2, then swaps it in
    E-->>A: Output from v1
    deactivate E
    A->>E: run(facts)
    E-->>A: Output from v2
```

- A run already in progress finishes with the rules it started with.
- Runs that start after the swap use the new rules.
- If the new list fails to compile, nothing is swapped and the old rules stay in place.
- A run that starts during the swap may check its fact names with the languages the other list's rules use. Only
  that run is affected, and only in whether it accepts a name one of the lists can't use.
- Each condition and action is compiled on its own, so variables and inline `import` statements in one rule never
  affect another rule or a later reload.

## 🤝 What you must keep thread-safe

The engine protects its own state. These parts are yours:

- **Facts:** build a new `FactStore` for each run and don't share mutable fact objects between concurrent runs.
- **The output supplier:** it must return a new object on every call. A shared instance would be changed by
  several runs at once.
- **Listeners:** the same listener instance is called from every thread running the engine.

## ⚡ Compiled rules and concurrent runs

MVEL caches an accessor in each compiled expression the first time it runs. When a later run binds the same fact
name to a different class, for example when `applicant` is an interface with several implementations, or is a
`Map` in one run and a record in another, MVEL replaces that accessor without synchronization. Two threads running
the same compiled expression can then fail intermittently with a `RuleExecutionException` caused by a
`ClassCastException`.

So concurrent runs never share MVEL's compiled form of an expression. The engine compiles a rule list once, when
`load()` loads it, and every run shares those compiled rules. What an expression language changes while its
expressions run lives in a *session*, and a copy of the rules is one session for each language the rules use. Each
`run()` borrows a copy that no other run is using, makes a new one if every copy is busy (as the first run after
`load()` does), and gives it back when it finishes. By default the engine keeps as many copies as the most runs
it has had in progress at once: the first time N runs overlap, N copies are made, and they stay in memory until the
next `load()` or `close()`. To bound that number, [limit the copies](#limiting-the-copies).

MVEL's session compiles each MVEL expression again the first time it runs it, except the first session, which takes
the expression `load()` compiled. A language whose expressions several threads can run at once keeps nothing
in its sessions (see [Other expression languages](languages/custom.md#-thread-safety)).

### Closing

When `load()` replaces the rules, the engine closes the old rules' idle sessions at once, and a session still in
use when the run using it returns. Once no run uses the old rules, it closes their languages' compilers too.
`close()` does the same for the current rules, and afterwards `run()` and `load()` throw
`IllegalStateException`. `RulesEngine` is `AutoCloseable`, so an engine built for a short task can go in a
try-with-resources block. A failure to close a session or a compiler is logged at WARN and doesn't fail a run.

### Limiting the copies

A copy isn't free: every MVEL expression in it is compiled again, and as it runs, MVEL generates accessor classes for
that copy alone. A thread pool bounds the number of copies, because runs can't overlap more than its threads. Without
one, for example when each request runs on its own virtual thread, thousands of runs can overlap, and the engine
makes and keeps a copy for each.

Give the engine a limit when you build it:

```java
RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new).maxCopies(64).build();
```

- At most 64 copies are made, so at most 64 runs are in progress at once. A run that starts while all of them are in
  use waits until one is free. On a virtual thread, a waiting run doesn't hold a platform thread.
- A run started from inside another run on the same thread, such as from an action or a listener, doesn't wait: the
  copy it would wait for may be its own. If no copy is free, it gets an extra copy, whose sessions are closed when it
  returns.
- If the thread is interrupted while its run waits, `run()` throws a `RuleExecutionException` caused by the
  `InterruptedException`, and the thread's interrupt status stays set. A run whose thread was **already** interrupted
  doesn't wait: it takes a free copy and then stops at its first rule, exactly as it does without a limit.
- While `load()` swaps in a new list, runs still using the old list can hold up to that many copies more.

Choose a limit close to the number of runs that can make progress at once: around the number of processors for rules
that only compute, higher for rules that wait on I/O.

This works with any MVEL optimizer, so the engine leaves MVEL's global optimizer setting alone. MVEL's default JIT
optimizer stays in effect (unless you pass `-Dmvel2.disable.jit=true`), and other libraries in the same JVM that use
MVEL aren't affected.

> [!NOTE]
> Earlier versions switched MVEL to its slower reflective optimizer for the whole JVM when the engine class loaded,
> unless the JVM was started with `-Dunruly.mvel.jit=true`. Later 1.x releases ignored that property, and 2.0 removes
> the `AbstractRulesEngine.JIT_PROPERTY` constant that named it.
