# 🧨 Exceptions by method

What each method of the engine's API throws, and when, and what the engine does with the text of an exception.

**Who it's for:** application developers who call the engine and handle what it throws.
**You'll be able to:** look up what a method throws and when, read an engine message, and find the failing rule
without parsing the message.
**Before you start:** [Exception types](error-handling.md#-exception-types).

[← Documentation index](README.md)

---

| Method | Exception | When |
| --- | --- | --- |
| `RulesEngineBuilder.firstMatch()` / `allMatches()` / `uniqueMatch()` | `NullPointerException` | The output supplier is `null` |
| `language()` | `IllegalArgumentException` | The language's name is `null` or blank, or a language with the same name was added already |
| | `NullPointerException` | The language is `null` |
| `defaultLanguage()` | `NullPointerException` | The name is `null` |
| `imports()` / `listener()` / `listeners()` | `NullPointerException` | The argument or an element is `null`. Nothing is added. |
| `maxCopies()` | `IllegalArgumentException` | The limit on compiled copies is less than 1 |
| `copiesAtLoad()` | `IllegalArgumentException` | The number of copies is negative: `copiesAtLoad must not be negative, but was -1` |
| `runTimeout()` | `IllegalArgumentException` | The timeout is zero or negative |
| `clock()` | `NullPointerException` | The clock is `null` |
| `outputType()` / `outputWriter()` / `option()` | `NullPointerException` | An argument is `null` |
| `fact()` / `facts()` | `IllegalArgumentException` | A fact is declared with the name `output` |
| | `NullPointerException` | A name, a type or the map is `null` |
| `build()` | `IllegalStateException` | The engine has no expression language; it has several and no default language; the default language, or a language given an option, isn't one of its languages; or a language found with `ServiceLoader` has a `null` or blank name, or two found languages have the same name |
| | `IllegalArgumentException` | An import is neither a loadable class nor a valid package name, or names a class that exists but can't be loaded, for example because a class it extends is missing from the class path; or `copiesAtLoad(n)` is more than `maxCopies(...)`: `copiesAtLoad(3) is more than maxCopies(2): no more copies than that are used at once` |
| | `Error` (rethrown) | `ServiceLoader` fails to create a language it found, for example with a `ServiceConfigurationError`. It's thrown unchanged. |
| `Rule.RuleBuilder.build()` | `IllegalStateException` | The name is `null` or blank, or the condition or action is `null`. The message names the field, such as `ruleName must not be null`. Also a tag that is `null` or blank, and a `validTo` that isn't after `validFrom`, including an equal one: `validTo must be after validFrom, but validFrom is ... and validTo is ...` |
| `load(rules)` | `RuleCompilationException` | A rule in the list is `null`; two rules share a name; a condition or action is blank; a condition its language rejects (in MVEL, an assignment or `import_static`); an expression has a syntax error its language detects; a rule names an expression language the engine doesn't have; an expression language throws while creating its compiler, for example MVEL given an option it doesn't have or `strongTyping` on when it [can't apply](languages/mvel.md#-strong-typing), or returns `null` instead of a compiler or a compiled expression; a [declared fact](facts.md#-declaring-facts) has a name the rules' languages can't refer to; or, once every rule has compiled, an engine built with [`copiesAtLoad(n)`](compiled-copies.md#making-copies-at-load) makes its copies and an expression language throws while creating or warming up a session, or returns `null` instead of a session, which is reported on its own, naming the language |
| | `IllegalStateException` | The engine is closed. It's checked before any rule is compiled, so a broken list throws this too, unless the `load()` was already compiling when `close()` ran; see [Closing](thread-safety.md#closing) |
| | `NullPointerException` | The list itself is `null` |
| | `Error` (rethrown) | A `VirtualMachineError` other than `StackOverflowError`, such as an `OutOfMemoryError`, is thrown while compiling, or while making the copies of `copiesAtLoad(n)`. It's logged with the rule's name, or the language's name when the language fails to create its compiler or a copy's session, or to warm one up, then rethrown unchanged, even when the language wraps it in its own exception. Every other `Error` — including a `NoClassDefFoundError` for a class a rule uses whose dependency is missing from the class path — is reported as a `RuleCompilationException` naming the rule, the language or the declared fact, with the error in its cause chain, or in that of one of its `failures()` (MVEL reads a `NoClassDefFoundError`'s message itself, so when its `getMessage()` throws, what that throws takes the error's place), and so is a `Throwable` that is neither an `Exception` nor an `Error`. A fatal error from closing the sessions or compilers of the failed load's rules, of the rules a reload replaced, or of rules a closed engine dropped is logged at WARN and rethrown once everything is closed. It wins over the load's own failure (a `RuleCompilationException`, or the `IllegalStateException` of an engine closed while it compiled), which it keeps in `getSuppressed()`, or logs at WARN if the error can't keep one; if the load itself failed with a fatal error, that one came first and is thrown instead, and the one from closing is only logged at WARN. After a reload, the new rules are already in and serve. See [A fatal error while closing](thread-safety.md#a-fatal-error-while-closing). |
| `run(facts)` / `runWithResult(facts)` | `RuleExecutionException` | A condition or action throws; a condition evaluates to `null` or a non-boolean; an action returns `null` instead of an `ActionResult`, or a property it returned can't be set on the output; the output supplier throws or returns `null`; an expression language throws or returns `null` when it creates a session for the run; more than one rule matches on a [unique-match](engines-and-runs.md#unique-match-one-rule-or-none) engine, which names them all and belongs to no rule; the run's thread is interrupted, which keeps the interrupt status set and makes the cause an `InterruptedException`; or the run passes its [timeout](stopping-runs.md), which makes the cause a `TimeoutException` |
| | `IllegalArgumentException` | A fact is named `output`, or has a name rules can't use (see [Facts](facts.md#-naming-rules)); or a [declared fact](facts.md#-declaring-facts) doesn't match its type (a primitive type [widens](facts.md#primitive-types-widen)), and, with `requireDeclaredFacts()`, a declared fact is missing or an undeclared one was supplied |
| | `IllegalStateException` | `load()` has never been called, or the engine is closed; or the engine's rule list was closed over and over while the run was borrowing a copy of it, which means an engine invariant has broken rather than that the call was wrong |
| | `NullPointerException` | `facts` is `null`, or the engine's clock returned a `null` instant: `the engine's clock returned a null instant`, before any listener hears of the run |
| | `Error` (rethrown) | A `VirtualMachineError` other than `StackOverflowError`, such as `OutOfMemoryError`, comes from a rule, from Java code a rule calls (a method, a getter or a lambda held in a fact), from the output supplier or from a listener. It's rethrown unchanged even when it arrives as the cause of another exception. Every other `Error`, including a `LinkageError` such as `NoClassDefFoundError` or `IllegalAccessError`, is reported as a `RuleExecutionException` naming the rule, with the error as its cause. A `Throwable` that is neither an `Exception` nor an `Error` is never fatal: wherever it comes from, it's handled like an exception from the same place. A fatal error can also come from closing what the run closes: an extra copy it gives back, a copy it gives back that couldn't be kept for a later run, a copy of rules a reload or `close()` retired, and those rules' compilers when it's the last run to give one back; the sessions of a new copy that was only partly made; or, when its borrow failed while it was the last user, the retired rules. It's thrown even when the rules ran without failing, and in place of a run failure that isn't fatal, which it keeps in `getSuppressed()`, or logs at WARN if the error can't keep one; a fatal error of the run's own came first and is thrown instead. See [A fatal error while closing](thread-safety.md#a-fatal-error-while-closing). |
| `RunOptions.withTimeoutOf()` / `withTimeout()` | `IllegalArgumentException` | The timeout is zero or negative |
| | `NullPointerException` | The timeout is `null` |
| `RunOptions.withTags()` | `IllegalArgumentException` | The collection is empty, or a tag in it is `null` or blank |
| | `NullPointerException` | The collection itself is `null` |
| `runWithResult(facts, options)` | `NullPointerException` | `options` is `null`: `options must not be null`, before any listener hears of the run |
| | | Everything else as `runWithResult(facts)` |
| `validate(rules)` | `IllegalStateException` | The engine is closed |
| | `NullPointerException` | The list itself is `null`. A `null` entry is returned as a problem, not thrown |
| | `Error` (rethrown) | As `load(rules)`, from compiling, and a fatal error from closing the compilers it created, logged at WARN and thrown once they're all closed. Everything else is returned in the list, not thrown. `validate()` makes no copies, so nothing from `copiesAtLoad(n)` reaches it at all |
| `rules()` | `IllegalStateException` | The engine is closed |
| `close()` | `Error` (rethrown) | A fatal error from closing a session or a compiler. It's logged at WARN and rethrown once every idle session is closed, and the compilers too if no run still uses the rules; if there are several, the first. The engine is closed all the same, so closing it again does nothing. Every other failure to close is only logged at WARN; see [A fatal error while closing](thread-safety.md#a-fatal-error-while-closing) |
| `new Fact<>(...)` | `NullPointerException` | The name is `null`, or the fact to copy or its name is `null` |
| `FactMap` methods | `IllegalArgumentException` | A `null` name, a key that differs from the fact's name, or a duplicate name in the constructor |
| | `NullPointerException` | A `null` map, array, array element, fact or function passed to a constructor or method |

Messages about a specific rule name it, for example `Failed to evaluate condition for rule 'prime-rate': ...`. Line
breaks, tabs, control characters, the Unicode line and paragraph separators and Unicode format characters (bidi
controls, zero-width characters, the soft hyphen, the byte order mark, tag characters) and lone surrogates are
escaped as `\n`, `\r`, `\t` or `\u` and four lowercase hex digits, such as `\u202e`. That's one escape per UTF-16
unit, so an escaped character outside the BMP, such as a tag character, becomes two.

Neither a name nor a fact value that a language quoted can then start a log line of its own or change how the line
reads, and escaping twice changes nothing. A zero-width joiner or a bidi mark in ordinary text shows as an escape too.
Shortening never cuts a surrogate pair (a character that takes two `char`s) in half, and the `(N more characters)`
count is the `char`s left out after the cut:

| Part of a message | What the engine does with it |
| --- | --- |
| A rule, fact or language name | Escaped, and shortened to 200 characters |
| Text copied from an exception, such as a language's compile error or warning, or what the output supplier or a listener threw | Shortened to 1,000 characters, then escaped |
| A list of names: the rules a unique-match engine matched, a rule's or a run's tags, or the engine's languages | Each name shortened to 200 characters, the list to 1,000, then escaped |
| What the output supplier threw, a listener's exception logged at WARN, or the fatal error in `The run failed with` | Its class, then `: <message>` if it has one, or, since 2.6.1, ` (message unavailable: <class>)` naming what reading the message threw if its `getMessage()` or `toString()` throws, such as `Output factory threw java.lang.IllegalStateException: boom` or `The run failed with java.lang.OutOfMemoryError: Java heap space` |
| An exception in the chain with no message, or an unreadable one | A note on the root cause at the end of a message the engine throws, or logs at WARN or ERROR, that copies an exception's text, except an `InvalidExpressionException`, a language's warning or a nested run's failure. MVEL's own compile error names the root cause whenever MVEL's description is missing or `null` and there is a cause, whatever the rest of this row says; see [Errors when rules load](languages/mvel.md#-errors-when-rules-load). A root cause with no message gives `... (caused by java.io.IOException)`; one with a message gives `... (caused by java.io.IOException: disk full)`, unless the first exception's message already contains it; one whose `getMessage()` throws gives `... (caused by com.example.UnreadableException: (message unavailable: java.lang.IllegalStateException))`. There's no note when the exception has no cause, or when every exception in the chain has a readable message. The chain is read up to where it loops back on itself, and a `getCause()` that throws ends it |
| A `run()` a condition or action started, which failed | `a nested run() failed: ...`, and it isn't logged a second time |
| A `RuleExecutionException` a language or your code throws itself | Logged like any other exception |

The underlying exception itself is never changed: when the expression language or your code threw it, it's available
from `getCause()` and reads exactly as it was written, line breaks and all. An expression the language rejected, such
as a condition with an assignment or an MVEL syntax error, has an `InvalidExpressionException` as its cause.

`load()` compiles every rule before it throws, so one `RuleCompilationException` reports every rule that
failed. `failures()` has each rule's own exception. The message counts them all, then lists them while they fit in
about 1,000 characters, always the first one whole:

```text
2 rules failed to compile: Condition for rule 'r1' failed to compile at line 1, column 6: Malformed expression; Action for rule 'r2' ...
```

When more failed than fit, the message ends with `; and N more (see failures())`, N being how many it left out;
`failures()` still has them all. A language that can't create its compiler, and a declared fact name the languages
reject, are listed with them, with no rule name; the message then counts `failures while loading the rules` instead of
rules. A `null` rule or a duplicate name is thrown at once, before anything is compiled.

`getExpressionKind()` on either exception says whether the rule's condition or its action failed. `issues()` on a
`RuleCompilationException` says where the language found each problem, with a line and column when it knows them.

To act on the failing rule without parsing the message, for example to disable it or count failures per rule, call
`getRuleName()` on the `RuleCompilationException` or `RuleExecutionException`. It returns the name exactly as the
rule has it, or `null` for failures that aren't about one rule, such as a failing output supplier, an expression
language that can't create its compiler or a session, or a stopped run.
