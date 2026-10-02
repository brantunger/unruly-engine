# 🧨 Exceptions by method

What each engine method throws, when, and what the engine does with an exception's text.

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
| `imports()` / `languageImports()` / `listener()` / `listeners()` | `NullPointerException` | An argument or an element is `null`. Nothing is added. |
| `maxCopies()` | `IllegalArgumentException` | The limit on compiled copies is less than 1 |
| `copiesAtLoad()` | `IllegalArgumentException` | The number of copies is negative: `copiesAtLoad must not be negative, but was -1` |
| `runTimeout()` | `IllegalArgumentException` | The timeout is zero or negative |
| `clock()` | `NullPointerException` | The clock is `null` |
| `outputType()` / `outputWriter()` / `option()` | `NullPointerException` | An argument is `null` |
| `fact()` / `facts()` | `IllegalArgumentException` | A name is `output` or blank. Nothing is added. |
| | `NullPointerException` | A name, type or map is `null`. Nothing is added. |
| `build()` | `IllegalStateException` | The engine has no expression language; it has several and no default language; the default language, or a language given an option or `languageImports(...)`, isn't one of its languages; or a language found with `ServiceLoader` has a `null` or blank name, or shares its name with a different class; a second copy is [skipped](languages/README.md#-how-the-engine-picks-a-language) |
| | `IllegalArgumentException` | An import is [over 1,000 characters or 64 dot-separated parts](languages/mvel.md#-classes-and-imports), is neither a loadable class nor a valid package name, or names an existing class that can't load, such as one missing its superclass; a `languageImports(...)` import is over 1,000 characters; or `copiesAtLoad(n)` is more than `maxCopies(...)`: `copiesAtLoad(3) is more than maxCopies(2): no more copies than that are used at once` |
| | `Error` (rethrown) | Thrown unchanged, such as `ServiceConfigurationError`, when `ServiceLoader` can't create a language |
| `Rule.RuleBuilder.build()` | `IllegalStateException` | The name is `null` or blank, or the condition or action is `null`. The message names the field, such as `ruleName must not be null`. Also a tag that is `null` or blank, and a `validTo` that isn't after `validFrom`, including an equal one: `validTo must be after validFrom, but validFrom is ... and validTo is ...` |
| `load(rules)` | `RuleCompilationException` | A rule in the list is `null`; two rules share a name; a condition or action is blank; a condition its language rejects (in MVEL, an assignment or `import_static`); an expression has a syntax error its language detects; a rule names an expression language the engine doesn't have; an expression language throws while creating its compiler, for example MVEL given an option it doesn't have, `strongTyping` on when it [can't apply](languages/mvel.md#-strong-typing) or any `languageImports(...)`, or returns `null` instead of a compiler or a compiled expression; a [declared fact](facts.md#-declaring-facts) has a name the rules' languages can't refer to; or, once every rule has compiled, an engine built with [`copiesAtLoad(n)`](compiled-copies.md#making-copies-at-load) makes its copies and an expression language throws while creating or warming up a session, or returns `null` instead of a session, which is reported on its own, naming the language |
| | `IllegalStateException` | The engine is closed. It's checked before any rule is compiled, so a broken list throws this too, unless `load()` was already compiling when `close()` ran; see [Closing](thread-safety.md#closing) |
| | `NullPointerException` | The list itself is `null` |
| | `Error` (rethrown) | A `VirtualMachineError` other than `StackOverflowError`, such as an `OutOfMemoryError`, is thrown while compiling, or while making the copies of `copiesAtLoad(n)`. It's logged with the rule's name, or the language's when the language fails to create its compiler or a copy's session, or to warm one up, then rethrown unchanged, even when the language wraps or suppresses it in an exception. Every other `Error` — including a `NoClassDefFoundError` for a class a rule uses whose dependency is missing from the class path — is reported as a `RuleCompilationException` naming the rule, the language or the declared fact, with the error in its cause chain, or in that of one of its `failures()` (MVEL reads a `NoClassDefFoundError`'s message itself, so when its `getMessage()` throws, what that throws takes the error's place), and so is a `Throwable` that is neither an `Exception` nor an `Error`. A fatal error from closing the sessions or compilers of the failed load's rules, the idle copies of the rules a reload replaced and, if no run uses them, their compilers, or of rules a closed engine dropped is logged at WARN and rethrown once everything is closed, in place of a non-fatal failure of the load's own. After a reload, the new rules serve; it also retries old rules an earlier call left half retired. If retiring threw nothing fatal, a failure that stopped it part way, such as a `StackOverflowError`, is logged at WARN, and the next `load()` or `close()` tries again. A failed load's own fatal error carries such a failure; otherwise the load throws it instead. See [A fatal error while closing](error-handling.md#-a-fatal-error-while-closing). |
| `run(facts)` / `runWithResult(facts)` | `RuleExecutionException` | A condition or action throws; a condition evaluates to `null` or a non-boolean; an action returns `null` instead of an `ActionResult`, or a property it returned can't be set on the output; the output supplier throws or returns `null`; an expression language throws or returns `null` when it creates a session for the run; more than one rule matches on a [unique-match](engines-and-runs.md#unique-match-one-rule-or-none) engine, which names them all and belongs to no rule; the run's thread is interrupted, which keeps the interrupt status set and makes the cause an `InterruptedException`; or the run passes its [timeout](stopping-runs.md), which makes the cause a `TimeoutException` |
| | `IllegalArgumentException` | A fact's name is `null`, blank, `output` or one rules can't use ([Facts](facts.md#-naming-rules)); or a [declared fact](facts.md#-declaring-facts) doesn't match its type (a primitive type [widens](facts.md#primitive-types-widen)), and, with `requireDeclaredFacts()`, a declared fact is missing or an undeclared one was supplied |
| | `IllegalStateException` | `load()` was never called, or the engine is closed; or the engine's current rule list was closed although nothing replaced it: an engine invariant broke, not a wrong call |
| | `NullPointerException` | `facts` is `null`, its `asMap()` returns `null`, or the engine's clock returned a `null` instant: `the engine's clock returned a null instant`, before any listener hears of the run |
| | Anything (rethrown) | What `asMap()` or a fact's `getValue()` throws, unchanged and unlogged, before any listener hears of the run; see [Implementing FactStore](facts.md#-implementing-factstore) |
| | `Error` (rethrown) | A `VirtualMachineError` other than `StackOverflowError`, such as `OutOfMemoryError`, comes from a rule, from Java code a rule calls (a method, a getter or a lambda held in a fact), from the output supplier or from a listener. It's rethrown unchanged, even wrapped, or suppressed within the [glossary's limit](glossary.md#fatal-error). Every other `Error`, including a `LinkageError` such as `NoClassDefFoundError` or `IllegalAccessError`, is reported as a `RuleExecutionException` naming the rule, with the error as its cause. A `Throwable` that is neither an `Exception` nor an `Error` is never fatal: it's handled like an exception from the same place. A fatal error can also come from closing copies, sessions, compilers or [`runScopedClosing`](languages/custom.md#-reading-facts) values as the run leaves. It's thrown even when the rules succeeded, in place of a run failure that isn't fatal. See [A fatal error while closing](error-handling.md#-a-fatal-error-while-closing). |
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
| `close()` | `Error` (rethrown) | A fatal error from closing a session or a compiler. It's rethrown once every idle session is closed, and the compilers too if no run still uses the rules; of several, the first, carrying the rest, unless one carries it, in `getSuppressed()`. The engine is closed anyway; closing again does nothing, unless retiring stopped part way, as a thrown `StackOverflowError` can: then it finishes. Every failure to close is logged at WARN unless a [nested run](nested-runs.md#-what-is-logged) logged it; see [A fatal error while closing](error-handling.md#-a-fatal-error-while-closing) |
| `run()` / `runWithResult()` / `load()` / `validate()` / `close()` | `StackOverflowError` | The thread has less than about 8 KB of stack left (more before the JIT compiles the engine's check). It's thrown unchanged and unlogged before the call takes anything, leaving the engine as it was: `close()` throws it even on a closed engine, and leaves an open one open. Best effort: a language or a listener can still run out of stack deeper in |
| `new Fact<>(...)` | `NullPointerException` | The name is `null`, or the fact to copy or its name is `null` |
| `FactMap` methods | `IllegalArgumentException` | A `null` name, a key that differs from the fact's name, or a duplicate name in the constructor |
| | `NullPointerException` | A `null` map, array, array element, fact or function passed to a constructor or method |

Messages about a specific rule name it, for example `Failed to evaluate condition for rule 'prime-rate': ...`. Line
breaks, tabs, control characters, line and paragraph separators, format characters such as bidi controls, lone
surrogates, and the other characters Unicode marks default-ignorable, which show as nothing (U+3164 HANGUL FILLER,
variation selectors, unassigned ones), are escaped as `\n`, `\r`, `\t` or `\u` and four lowercase hex digits
(`\u202e`), one per UTF-16 unit, so two for a character outside the BMP.

Neither a name nor a quoted fact value can then start a log line, change how it reads or pass for another name through
an invisible character, and escaping twice changes nothing. Ordinary text shows escapes too, such as an emoji's
U+FE0F. Shortening never splits such a character. The engine's limits count `char`s before escaping, so an escaped
text can show more than its limit, and the `(N more characters)` count is the `char`s cut:

| Part of a message | What the engine does with it |
| --- | --- |
| A rule, fact or language name | Shortened to 200 characters, then escaped |
| Text copied from an exception, such as a language's compile error or warning, or what the output supplier or a listener threw | Shortened to 1,000 characters, then escaped. MVEL's compile error [already fits once escaped](languages/mvel.md#-errors-when-rules-load) |
| A list of names: the rules a unique-match engine matched, a rule's or a run's tags, or the engine's languages | Each name shortened to 200 characters, the list to 1,000, then escaped |
| What the output supplier threw, a listener's exception logged at WARN, or the fatal error in `The run failed with` | Its class, then `: <message>` if it has one, or, since 2.6.1, ` (message unavailable: <class>)` naming what reading the message threw if its `getMessage()` or `toString()` throws, such as `Output factory threw java.lang.IllegalStateException: boom` or `The run failed with java.lang.OutOfMemoryError: Java heap space` |
| An exception in the chain with no message, or an unreadable one | A note on the root cause at the end of a message the engine throws, or logs at WARN or ERROR, that copies an exception's text, except an `InvalidExpressionException`, a language's warning or a nested run's failure the engine wrapped. For MVEL's own compile error, see [Errors when rules load](languages/mvel.md#-errors-when-rules-load). A root cause with no message gives `... (caused by java.io.IOException)`; one with a message gives `... (caused by java.io.IOException: disk full)`, unless the part of the first exception's text that the message shows already has it; one whose `getMessage()` throws gives `... (caused by com.example.UnreadableException: (message unavailable: java.lang.IllegalStateException))`. There's no note when the exception has no cause, or when every exception in the chain has a readable message. The chain ends where it loops or a `getCause()` throws |
| A failed [nested](nested-runs.md#-what-is-logged) `run()` or `load()`, a rejected fact included, or a waited-for `run()` on another thread | `a nested run() failed: <innermost failure>` or `a nested load() failed: ...`, not logged again unless wrapped with its own message |
| A `RuleExecutionException` a language or your code throws itself | Logged like any other exception |

Your code's or the language's exception is `getCause()`, unchanged, unless its `getMessage()` throws in MVEL: at
[run time](languages/mvel-gotchas.md#-calling-java-code) or for a `NoClassDefFoundError` in `load()`. An expression
the language rejected, such as an MVEL syntax error, has an `InvalidExpressionException` as its cause.

`load()` compiles every rule before it throws, so one `RuleCompilationException` reports every rule that
failed. `failures()` has each rule's own exception. The message counts them all, then lists them while they fit in
about 1,000 characters, always the first one whole:

```text
2 rules failed to compile: Condition for rule 'r1' failed to compile at line 1, column 6: Malformed expression; Action for rule 'r2' ...
```

When more failed than fit, the message ends with `; and N more (see failures())`, N being how many it left out. A
language that can't create its compiler, and a declared fact name the languages reject, are listed with them, with no
rule name; the message then counts `failures while loading the rules` instead of rules. A `null` rule or a duplicate
name is thrown at once, before anything is compiled.

`getExpressionKind()` on either exception says whether the rule's condition or its action failed. `issues()` on a
`RuleCompilationException` says where the language found each problem, with a line and column when it knows them.

To act on the failing rule without parsing the message, call `getRuleName()` on the `RuleCompilationException` or
`RuleExecutionException`. It returns the name exactly as the rule has it, or `null` for failures that aren't about one
rule, such as a failing output supplier, an expression language that can't create its compiler or a session, or a
stopped run.
