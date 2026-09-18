# 🚨 Error handling

> [!NOTE]
> Describes 2.0.0, which isn't released yet. For 1.8.0, see
> [this page at v1.8.0](https://github.com/brantunger/unruly-engine/blob/v1.8.0/docs/error-handling.md).

The engine throws its own exceptions for rule problems and standard JDK exceptions for misuse of the API.

[← Documentation index](README.md)

- [Exception types](#-exception-types)
- [Exceptions by method](#-exceptions-by-method)
- [Caught when loading or only when running?](#-caught-when-loading-or-only-when-running)
- [Stopping a run](#-stopping-a-run)
- [What happens on each failure](#-what-happens-on-each-failure)
- [Handling failures](#-handling-failures)

---

## 🧬 Exception types

```mermaid
classDiagram
    direction TB
    RuntimeException <|-- UnrulyException
    UnrulyException <|-- RuleCompilationException
    UnrulyException <|-- RuleExecutionException
    UnrulyException <|-- InvalidExpressionException
    RuntimeException <|-- IllegalArgumentException
    RuntimeException <|-- IllegalStateException
    RuntimeException <|-- NullPointerException
    class UnrulyException {
        base type for rule problems
    }
    class RuleCompilationException {
        a rule couldn't be loaded
    }
    class RuleExecutionException {
        a rule failed while running
    }
    class InvalidExpressionException {
        a language rejected an expression
    }
    class IllegalArgumentException {
        a rejected fact, or an invalid import
    }
    class IllegalStateException {
        run() before load(), or a bad builder setting
    }
    class NullPointerException {
        a null argument
    }
```

All of them are unchecked. A language throws `InvalidExpressionException` to reject an expression, and `load()`
reports it as the cause of a `RuleCompilationException`.

> [!WARNING]
> `catch (UnrulyException e)` doesn't catch `IllegalArgumentException`, `IllegalStateException` or
> `NullPointerException`. Those signal a programming error rather than a problem with a rule.

## 📋 Exceptions by method

| Method | Exception | When |
| --- | --- | --- |
| `RulesEngineBuilder.firstMatch()` / `allMatches()` | `NullPointerException` | The output supplier is `null` |
| `language()` | `IllegalArgumentException` | The language's name is `null` or blank, or a language with the same name was added already |
| | `NullPointerException` | The language is `null` |
| `defaultLanguage()` | `NullPointerException` | The name is `null` |
| `imports()` / `listener()` / `listeners()` | `NullPointerException` | The argument or an element is `null`. Nothing is added. |
| `maxCopies()` | `IllegalArgumentException` | The limit on compiled copies is less than 1 |
| `runTimeout()` | `IllegalArgumentException` | The timeout is zero or negative |
| `outputType()` / `outputWriter()` / `option()` | `NullPointerException` | An argument is `null` |
| `fact()` / `facts()` | `IllegalArgumentException` | A fact is declared with the name `output` |
| | `NullPointerException` | A name, a type or the map is `null` |
| `build()` | `IllegalStateException` | The engine has no expression language; it has several and no default language; the default language, or a language given an option, isn't one of its languages; or a language found with `ServiceLoader` has a `null` or blank name, or two found languages have the same name |
| | `IllegalArgumentException` | An import is neither a loadable class nor a valid package name, or names a class that exists but can't be loaded, for example because a class it extends is missing from the class path |
| | `Error` (rethrown) | `ServiceLoader` fails to create a language it found, for example with a `ServiceConfigurationError`. It's thrown unchanged. |
| `Rule.RuleBuilder.build()` | `IllegalStateException` | The name is `null` or blank, or the condition or action is `null`. The message names the field, such as `ruleName must not be null`. |
| `load(rules)` | `RuleCompilationException` | A rule in the list is `null`; two rules share a name; a condition or action is blank; a condition its language rejects (in MVEL, an assignment or `import_static`); an expression has a syntax error its language detects; a rule names an expression language the engine doesn't have; an expression language throws while creating its compiler, for example MVEL given an option it doesn't have or `strongTyping` on when it [can't apply](languages/mvel.md#-strong-typing), or returns `null` instead of a compiler or a compiled expression; a [declared fact](facts.md#-declaring-facts) has a name the rules' languages can't refer to |
| | `IllegalStateException` | The engine is closed |
| | `NullPointerException` | The list itself is `null` |
| | `Error` (rethrown) | A `VirtualMachineError` other than `StackOverflowError`, such as an `OutOfMemoryError`, is thrown while compiling. It's logged with the rule's name, or the language's name when the language fails to create its compiler, then rethrown unchanged, even when the language wraps it in its own exception. Every other `Error` — including a `NoClassDefFoundError` for a class a rule uses whose dependency is missing from the class path — is reported as a `RuleCompilationException` naming the rule, with the error as its cause. |
| `run(facts)` / `runWithResult(facts)` | `RuleExecutionException` | A condition or action throws; a condition evaluates to `null` or a non-boolean; an action returns `null` instead of an `ActionResult`, or a property it returned can't be set on the output; the output supplier throws or returns `null`; an expression language throws or returns `null` when it creates a session for the run; the run's thread is interrupted, which keeps the interrupt status set and makes the cause an `InterruptedException`; or the run passes its [timeout](stopping-runs.md), which makes the cause a `TimeoutException` |
| | `IllegalArgumentException` | A fact is named `output`, or has a name rules can't use (see [Facts](facts.md#-naming-rules)); or a [declared fact](facts.md#-declaring-facts) isn't an instance of its type, and, with `requireDeclaredFacts()`, a declared fact is missing or an undeclared one was supplied |
| | `IllegalStateException` | `load()` has never been called, or the engine is closed |
| | `NullPointerException` | `facts` is `null` |
| | `Error` (rethrown) | A `VirtualMachineError` other than `StackOverflowError`, such as `OutOfMemoryError`, comes from a rule, from Java code a rule calls (a method, a getter or a lambda held in a fact), from the output supplier or from a listener. It's rethrown unchanged even when it arrives as the cause of another exception. Every other `Error`, including a `LinkageError` such as `NoClassDefFoundError` or `IllegalAccessError`, is reported as a `RuleExecutionException` naming the rule, with the error as its cause. |
| `RunOptions.withTimeoutOf()` / `withTimeout()` | `IllegalArgumentException` | The timeout is zero or negative |
| `runWithResult(facts, options)` | | As `runWithResult(facts)` |
| `rules()` | `IllegalStateException` | The engine is closed |
| `new Fact<>(...)` | `NullPointerException` | The name is `null`, or the fact to copy or its name is `null` |
| `FactMap` methods | `IllegalArgumentException` | A `null` name, a key that differs from the fact's name, or a duplicate name in the constructor |
| | `NullPointerException` | A `null` map, array, array element, fact or function passed to a constructor or method |

Messages about a specific rule name it, for example `Failed to evaluate condition for rule 'prime-rate': ...`. Line
breaks and other control characters are escaped (`\n`), so neither a name nor a fact value that a language quoted can
start a log line of its own:

| Part of a message | What the engine does with it |
| --- | --- |
| A rule, fact or language name | Escaped, and shortened to 200 characters |
| Text copied from an exception, such as a language's compile error or warning, or what the output supplier or a listener threw | Shortened to 1,000 characters, then escaped |
| An exception in the chain with no message | The message also names the root cause's class, and its message unless already there: `... (caused by java.lang.RuntimeException: boom)` |
| A `run()` a condition or action started, which failed | `a nested run() failed: ...`, and it isn't logged a second time |
| A `RuleExecutionException` a language or your code throws itself | Logged like any other exception |

The underlying exception itself is never changed: when the expression language or your code threw it, it's available
from `getCause()` and reads exactly as it was written, line breaks and all. An expression the language rejected, such
as a condition with an assignment or an MVEL syntax error, has an `InvalidExpressionException` as its cause.

`load()` compiles every rule before it throws, so one `RuleCompilationException` reports every rule that
failed: `failures()` has each rule's own exception, and the message lists them, such as
`2 rules failed to compile: Condition for rule 'r1' failed to compile at line 1, column 6: Malformed expression; Action for rule 'r2' ...`.

A language that can't create its compiler, and a declared fact name the languages reject, are listed with them, with
no rule name; the message then counts `failures while loading the rules` instead of rules. A `null` rule or a
duplicate name is thrown at once, before anything is compiled.

`getExpressionKind()` on either exception says whether the rule's condition or its action failed. `issues()` on a
`RuleCompilationException` says where the language found each problem, with a line and column when it knows them.

To act on the failing rule without parsing the message, for example to disable it or count failures per rule, call
`getRuleName()` on the `RuleCompilationException` or `RuleExecutionException`. It returns the name exactly as the
rule has it, or `null` for failures that aren't about one rule, such as a failing output supplier, an expression
language that can't create its compiler, or a stopped run.

## 🔍 Caught when loading or only when running?

`load()` compiles every expression, but MVEL's parser is lenient, so some mistakes in MVEL rules only
surface when a rule is evaluated. Another language decides what it catches when compiling.

| Mistake | Detected by |
| --- | --- |
| `null` or blank condition or action | ✅ `load()` |
| Duplicate rule name | ✅ `load()` |
| A rule in an expression language the engine doesn't have | ✅ `load()` |
| Assignment in a condition, which every conforming language rejects (in MVEL: `applicant.approved = true`, `x++`, `with`, `def`, `import_static`) | ✅ `load()` |
| Most syntax errors (`applicant.creditScore >=`) | ✅ `load()` |
| Some malformed expressions (`true)`, `output.put("k" 1)`) | ⚠️ only `run()` |
| A class that isn't imported (`Objects` without `imports("java.util")`) | ⚠️ only `run()` |
| A misspelled fact or property name | ⚠️ only `run()`, unless MVEL's [strong typing](languages/mvel.md#-strong-typing) is on: then `load()` |
| A condition that isn't a boolean (`applicant.name`) | ⚠️ only `run()` |
| A method call that changes a fact inside a condition (`applicant.setApproved(true)`) | ❌ never |

> [!TIP]
> Don't rely on `load()` alone. Test each rule against sample facts; see
> [Testing rules](writing-rules.md#-testing-rules).

## ⏳ Stopping a run

A run stops once its thread is interrupted or it passes its timeout: before each condition and action, when one
returns, or while it waits for a compiled copy. [Stopping a run](stopping-runs.md) covers timeouts, what they can't
stop, nested runs and what listeners see.

## 🧾 What happens on each failure

What `run()` throws, which [listener](listeners-and-logging.md) callbacks each failure reaches, and how the engine logs
it. The listener column leaves out `beforeRun`, except where a run never gets it. "Names the rule" means
`getRuleName()` returns the rule's name; "no rule" means it returns `null`. A
[fatal error](glossary.md#fatal-error) is a `VirtualMachineError` other than `StackOverflowError`, such as
`OutOfMemoryError`.

| Failure | `run()` throws | Listeners get | Logged |
| --- | --- | --- | --- |
| A condition or action throws | `RuleExecutionException` that names the rule | `onError`, then `onRunError`, with that exception | ERROR |
| A condition returns `null` or a non-boolean, an action returns `null`, or a property can't be set | `RuleExecutionException` that names the rule | `onError`, then `onRunError`, with that exception | ERROR |
| The output supplier throws or returns `null` | `RuleExecutionException`, no rule | The conditions' callbacks, then `onRunError`; no `onError` | ERROR |
| A fact name the engine or a language rejects, or a fact that doesn't match its [declaration](facts.md#-declaring-facts) | `IllegalArgumentException` | `onRunError` with that `IllegalArgumentException` | ERROR |
| A language fails to create a session for the run | `RuleExecutionException`, no rule | Nothing, not even `beforeRun`: the run fails before it starts | ERROR |
| The run is stopped between rules | `RuleExecutionException`, no rule, with an `InterruptedException` or `TimeoutException` cause | `onRunError`; the rule it would have gone on to gets nothing | WARN |
| The run is stopped when a condition or action returns or throws an exception | The same as between rules | `onError` with the stop, then `onRunError` | WARN |
| The run is stopped while it waits for a compiled copy | The same as between rules | `beforeRun` only when the wait ends, then `onRunError` | WARN |
| A listener throws an exception, or an `Error` that isn't fatal | Nothing: the run goes on | Every other listener still gets that callback | WARN, with the message escaped and shortened; the stack trace at DEBUG |
| A fatal error from a rule | The error itself | `onError`, then `onRunError`, with a `RuleExecutionException` that names the rule | ERROR |
| A fatal error from `beforeRun`, a `before*` or an `after*` callback | The error itself | Every listener gets that callback first, then `onRunError` | ERROR, naming the listener's error |
| A fatal error from `onError`, closing a failure that isn't fatal itself | The error itself; the reported exception keeps it in `getSuppressed()` | Every listener gets `onError`, then `onRunError` | Only the failure's own line: ERROR, or WARN for a stop |
| A fatal error from `onError`, closing a failure that is fatal itself | The failure's own error; the reported exception keeps the first other one a listener threw in `getSuppressed()` | Every listener gets `onError`, then `onRunError` | The failure's own ERROR line, then `Listener threw exception in onError, kept on the failure` at WARN |
| A fatal error from `afterRun` | The error itself, although the run succeeded | Every listener gets `afterRun`; no `onRunError` | ERROR |
| A fatal error from `onRunError` | That error, in place of the exception the run failed with | Every listener gets `onRunError` | ERROR |
| `run()` before `load()`, on a closed engine, or with `null` facts | `IllegalStateException` or `NullPointerException` | Nothing | Not logged |

> [!NOTE]
> A stack trace or `getClass()` may show `io.github.brantunger.unruly.core.ReportedFailure`. It's an internal subclass
> of `RuleExecutionException` that the engine throws for the failures above. Catch `RuleExecutionException`, and
> never match on the class name.

- **A fatal error from the output supplier** is rethrown the same way; `onRunError` gets a `RuleExecutionException`
  that names no rule. One from a language creating a session reaches no listener, like any other session failure.
- **A `before*` callback that throws a fatal error** is closed with `onError` on every listener, and its condition or
  action doesn't run. The rest of what listeners see is in [Guarantees](listeners-and-logging.md#-guarantees).
- **A run of an empty rule list evaluates nothing**, so an interrupt or a passed deadline can only stop it while it
  waits for a compiled copy. Otherwise it returns normally.

## 🧯 Handling failures

```java
try {
    LoanDecision decision = engine.run(facts);
    // ...
} catch (RuleExecutionException e) {
    if (e.getRuleName() != null) {
        // A rule failed at run time. The message names the rule; getCause() holds the underlying error.
    } else if (e.getCause() instanceof TimeoutException || e.getCause() instanceof InterruptedException) {
        // The run was stopped: it passed its deadline, or its thread was interrupted.
        // A rule's own exception, or a wrong result, as the run stopped is in e.getSuppressed().
    } else {
        // A failure that belongs to no rule, such as an output supplier that threw.
    }
} catch (IllegalArgumentException e) {
    // A fact has a name that rules can't use, or doesn't match what the engine declared.
}
```

`TimeoutException` is `java.util.concurrent.TimeoutException`. Keep in mind:

- **Tell a stop from a failure by its cause.** A stopped run's exception names no rule and has an
  `InterruptedException` or `TimeoutException` cause.
- **A bug near the deadline is reported as a stop.** What the rule threw, or why what it returned was wrong, is only in
  `getSuppressed()`; see [What stops a run](stopping-runs.md#-what-stops-a-run).
- **All-matches runs aren't atomic.** Actions that ran before the failing one keep their changes to the output object
  and to any facts they modified. Discard the output object when `run()` throws.
- **A failed reload is safe.** If `load()` throws, the engine keeps the rules it had before.
- **Failures are already logged.** The engine logs each one at ERROR before throwing, except a run stopped by an
  interrupt or a deadline, which is a WARN; see
  [Logging setup](listeners-and-logging.md#-logging-setup). The message can contain fact values, copied from the
  exception a rule caused, such as `For input string: "123-45-6789"`. With sensitive facts, turn off the
  `io.github.brantunger.unruly` logger and log a redacted form yourself.
- **Listeners hear about it first.** When a condition or action fails, `onError` and then `onRunError` receive the
  same exception before `run()` throws it. A failing output supplier and a rejected fact name don't reach `onError`,
  because no rule is involved, but they do reach `onRunError`. An expression language that fails to create a session
  fails the run before `beforeRun`, so no listener hears about it.
- **An interrupt isn't lost.** When a rule, listener, output supplier or expression language throws an exception
  caused by an `InterruptedException`, the engine sets the interrupt status again; see
  [What stops a run](stopping-runs.md#-what-stops-a-run).
