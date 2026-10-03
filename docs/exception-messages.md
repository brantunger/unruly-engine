# 📨 Reading exception messages

What the engine does with the text of an exception, how `load()` reports several failures at once, and how to find the
failing rule without parsing a message.

**Who it's for:** application developers who log, show or act on the engine's exceptions.
**You'll be able to:** read an engine message, tell what was shortened or escaped, and find the failing rule without
parsing the message.
**Before you start:** [Exceptions by method](exceptions-by-method.md) lists what each method throws.

[← Documentation index](README.md)

---

## 🧾 Shortening and escaping

Messages about a rule name it, for example `Failed to evaluate condition for rule 'prime-rate': ...`. Line
breaks, tabs, control characters, line and paragraph separators, format characters such as bidi controls, lone
surrogates, and the other characters Unicode marks default-ignorable, which show as nothing (U+3164 HANGUL FILLER,
variation selectors, unassigned ones), are escaped as `\n`, `\r`, `\t` or `\u` and four lowercase hex digits
(`\u202e`), one per UTF-16 unit, so two for a character outside the BMP.

Neither a name nor a quoted fact value can start a log line, change how it reads or pass for another name through
an invisible character, and escaping twice changes nothing. Ordinary text shows escapes too, such as an emoji's
U+FE0F. Shortening never splits such a character or an escape. Limits count `char`s before escaping, except for
[text escaped already](nested-runs.md#-what-is-logged), so escaped text can show more than its limit, and the
`(N more characters)` count is the `char`s cut:

| Part of a message | What the engine does with it |
| --- | --- |
| A rule, fact or language name | Shortened to 200 characters, then escaped |
| Text copied from an exception, such as a language's compile error or warning, or what the output supplier or a listener threw | Shortened to 1,000 characters, then escaped. MVEL's [compile errors](languages/mvel.md#-errors-when-rules-load) and rejections already fit once escaped |
| A list of names: the rules a unique-match engine matched, a rule's or a run's tags, or the engine's languages | Each name shortened to 200 characters, the list to 1,000, then escaped |
| What the output supplier threw, a listener's exception logged at WARN, or the fatal error in `The run failed with` | Its class, then `: <message>` if it has one, or, since 2.6.1, ` (message unavailable: <class>)` naming what reading the message threw if its `getMessage()` or `toString()` throws, such as `Output factory threw java.lang.IllegalStateException: boom` or `The run failed with java.lang.OutOfMemoryError: Java heap space` |
| An exception in the chain with no message, or an unreadable one | A note on the root cause at the end of a message the engine throws, or logs at WARN or ERROR, that copies an exception's text, except an `InvalidExpressionException`, a language's warning or a nested run's failure the engine wrapped. For MVEL's own compile error, see [Errors when rules load](languages/mvel.md#-errors-when-rules-load). A root cause with no message gives `... (caused by java.io.IOException)`; one with a message gives `... (caused by java.io.IOException: disk full)`, unless the part of the first exception's text that the message shows already has it; one whose `getMessage()` throws gives `... (caused by com.example.UnreadableException: (message unavailable: java.lang.IllegalStateException))`. There's no note when the exception has no cause, or when every exception in the chain has a readable message. The chain ends where it loops or a `getCause()` throws |
| A failed [nested](nested-runs.md#-what-is-logged) `run()` or `load()`, a rejected fact included, or a waited-for `run()` on another thread | `a nested run() failed: <innermost failure>` or `a nested load() failed: ...`, not logged again unless wrapped with its own message |
| A `RuleExecutionException` a language or your code throws itself | Logged like any other exception |

Your code's or the language's exception is `getCause()`, unchanged, unless its `getMessage()` throws in MVEL: at
[run time](languages/mvel-gotchas.md#-calling-java-code) or for a `NoClassDefFoundError` in `load()`. An expression
the language rejected, such as an MVEL syntax error, has an `InvalidExpressionException` as its cause.

## 📋 Several failures from load()

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

## 🎯 Finding the failing rule

To act on the failing rule without parsing the message, call `getRuleName()` on the `RuleCompilationException` or
`RuleExecutionException`. It returns the rule's exact name, or `null` for failures that aren't about one rule, such as
a failing output supplier, an expression language that can't create its compiler or a session, or a stopped run.

`getExpressionKind()` on either exception says whether the rule's condition or its action failed. `issues()` on a
`RuleCompilationException` says where the language found each problem, with a line and column when it knows them.
