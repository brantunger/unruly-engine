# 📖 Glossary

> [!NOTE]
> Describes 2.0.0, which isn't released yet.

Short definitions of the words the unruly-engine guides use, each with a link to the guide that explains it.

**Who it's for:** everyone.
**You'll be able to:** look up a term you meet in a guide, a Javadoc page or an error message, and find where it's
explained in full.
**Before you start:** nothing.

[← Documentation index](README.md)

---

### Action

The expression a rule runs when it fires. It changes the [output object](#output-object) in place, or returns properties
for the engine to set on it. A language keeps an action's own variables local to that action, as MVEL does. See
[Writing rules](writing-rules.md#actions-change-the-output).

### ActionResult

What a compiled action returns to the engine: `ActionResult.done()` when it changed the output itself, or
`ActionResult.set(properties)` for properties the engine sets on the output, in order, once the action returns. Only
language authors deal with it. See [Other expression languages](languages/custom.md#-writing-a-language).

### All-matches engine

An engine built with `RulesEngineBuilder.allMatches(...)`, which evaluates every condition and then fires every match.
See [match policy](#match-policy).

### Artifacts

The engine is published as three jars in the `io.github.brantunger` group: `unruly-engine`
(the MVEL language, which brings in the engine), `unruly-engine-core` (the engine without a language) and
`unruly-engine-test` (the [contract test kit](#contract-test-kit)). The `benchmarks` project isn't published. See
[Installation](../README.md#-installation) and [Packaging a language](languages/custom.md#-packaging-a-language).

### Carrier thread

The platform thread that a virtual thread runs on. On JDK 21 to 23, a virtual thread waiting on a monitor keeps its
carrier, which is why MVEL rules on many virtual threads can deadlock. See [MVEL](languages/mvel.md#-virtual-threads).

### Checksum

A lowercase hex SHA-256 that identifies a loaded rule list: each rule's name, priority,
[resolved language](#resolved-language), condition and action, in [evaluation order](#evaluation-order), but not its
description. `RuleSetInfo.checksum()` gives the engine's current one, and `RunResult.ruleSetChecksum()` the one a run
used. See [Migrating to 2.0](migrating-to-2.md#-a-run-reports-what-it-did-and-an-engine-reports-its-rules).

### Close

`RulesEngine.close()` marks the engine closed and returns at once, without waiting for runs in progress. Runs already
going finish with the rules they have; their sessions are closed as each one returns, and the compilers after the last.
Afterwards `run()`, `runWithResult()`, `load()` and `rules()` throw `IllegalStateException`. See
[Thread safety](thread-safety.md#closing).

### Compiled copy

The per-run state of a loaded rule list: one [session](#session) for each language its rules use. A run borrows a copy
no other run is using, or makes a new one, and gives it back when it ends; the engine keeps copies until the next
`load()` or `close()`. See [Thread safety](thread-safety.md#-compiled-rules-and-concurrent-runs).

### Compiler

An `ExpressionCompiler`: what an [expression language](#expression-language) creates for each rule list that uses it,
when `load()` compiles the list. It compiles conditions and actions, checks fact names and creates sessions. The
engine closes it after a later `load()` or `close()` replaces the rule list, once no run still uses it. See
[Other expression languages](languages/custom.md#-writing-a-language).

### Condition

The expression that decides whether a rule [matches](#match-and-fire). It must evaluate to a boolean; `null` or any
other type fails the rule. A language rejects assignments in a condition where it can detect them, as MVEL does. See
[Writing rules](writing-rules.md#-anatomy-of-a-rule).

### Contract test kit

`ExpressionLanguageContractTest`, in the `unruly-engine-test` artifact, which a language's own test extends to check the
promises every language must keep. The same artifact has `LanguageTestContexts` for testing a compiler without an
engine. See [Testing a language](languages/custom.md#-testing-a-language).

### Copy limit

The most [compiled copies](#compiled-copy) an engine keeps, and so the most runs that make progress at once. By default
an engine limits runs on virtual threads only, to one copy for every two processors and at least one; `maxCopies(n)`
limits every thread and `unlimitedCopies()` removes the limit. The limit belongs to the engine, so every rule list it
loads shares it and a reload never raises it. See [Limiting the copies](thread-safety.md#limiting-the-copies).

### Deadline

The instant a run must stop, taken from its [timeout](#timeout) when `run()` is called. A [nested run](#nested-run)
stops at whichever comes first, its own deadline or the outer run's. See
[Stopping a run](error-handling.md#-stopping-a-run).

### Declared fact

A fact named on the builder with `fact(name, type)` or `facts(map)`. A run fails with `IllegalArgumentException` when
the fact is present and its value isn't of that type. A `null` value passes. A missing fact passes too, unless
`requireDeclaredFacts()` is set, which rejects a missing declared fact and any fact nobody declared. See
[Declaring facts](facts.md#-declaring-facts).

### Default language

The language of a rule whose `language` is `null`: the one named with `defaultLanguage(name)`, or else the engine's only
language. `build()` fails when an engine has several languages and no default. See
[Choosing a language per rule](languages/custom.md#-choosing-a-language-per-rule).

### Evaluation order

The order a run evaluates rules in: highest [priority](#priority) first, equal priorities in their list order, and a
`null` priority last. `rules().rules()` lists the loaded rules in this order. See [Rules](../README.md#rules).

### Expression language

A plug-in, `ExpressionLanguage`, that compiles and runs conditions and actions. MVEL, from the `unruly-engine` artifact,
is one; an engine finds languages with `ServiceLoader` unless you give it some with `language(...)`. See
[Choosing a language](writing-rules.md#-choosing-a-language).

### Extra copy

A [compiled copy](#compiled-copy) made above the [copy limit](#copy-limit) for a run that doesn't wait: a
[nested run](#nested-run), or a run that waited five seconds without any copy being given back. It isn't kept: its
sessions are closed when the run ends. See [Runs that don't wait](thread-safety.md#runs-that-dont-wait).

### Fact

A named input value that rules refer to by its name, such as `applicant`. `Fact` is the built-in `FactReference`. A fact
can't be named `null` or `output`, or have a name the rules' languages can't refer to. See
[Facts](facts.md#-the-fact-types).

### Fact store

The facts for one run: a `FactStore`, of which `FactMap` is the built-in implementation. The engine reads it through
`asMap()` when the run starts and never changes it; build a new one for each request. See
[Facts](facts.md#-the-fact-types).

### Fatal error

A `VirtualMachineError` other than `StackOverflowError`, such as `OutOfMemoryError`, found anywhere in the cause chain.
The engine logs it and rethrows it unchanged from `load()` or `run()`; every other `Error` from a rule is reported as
that rule's failure. See [Exceptions by method](error-handling.md#-exceptions-by-method).

### First-match engine

An engine built with `RulesEngineBuilder.firstMatch(...)`, which evaluates conditions in order and fires only the first
match. See [match policy](#match-policy).

### Listener

A `RuleListener` added with `listener(...)` or `listeners(...)` on the builder, told about each run, condition, action
and failure. Every `beforeRun` is followed by exactly one `afterRun` or `onRunError`, and the same listener is called
from every thread. See [Callbacks](listeners-and-logging.md#-callbacks).

### Load

`load(rules)` compiles a whole [rule list](#rule-list), then swaps it in with one atomic write. If it fails, `load()`
throws a `RuleCompilationException` and the old rules stay in place; runs already going finish with the rules they
started with. See [Reloading rules while running](thread-safety.md#-reloading-rules-while-running).

### Loaded rules

The rules an engine runs now, reported by `rules()` as a `RuleSetInfo`: `rules()` in
[evaluation order](#evaluation-order), `checksum()` and `loadedAt()`. Before the first `load()` it has no rules, the
checksum of an empty list and a `null` load time. See
[Migrating to 2.0](migrating-to-2.md#-a-run-reports-what-it-did-and-an-engine-reports-its-rules).

### Match and fire

A rule *matches* when its condition evaluates to `true`, and *fires* when its action runs. Every rule that fires has
matched, but on a first-match engine only the first match fires. See
[Choosing an engine](../README.md#choosing-an-engine).

### Match policy

Which matching rules fire, fixed when the engine is built: `firstMatch` fires the first match in evaluation order, and
`allMatches` evaluates every condition, then fires every match in priority order. `RunContext.matchPolicy()` returns
`"firstMatch"` or `"allMatches"`. 1.x called them *stateless* and *stateful*. See
[Choosing an engine](../README.md#choosing-an-engine).

### Missing fact

A name that isn't in the [fact store](#fact-store) at all, unlike a [null reference](#null-reference). In MVEL,
referring to it fails the rule with `unresolvable property or identifier`, so test for it with `isdef`; another language
decides for itself. See [Null and missing facts](facts.md#-null-and-missing-facts).

### Nested run

A run started on the same thread from inside another run, such as from an action or a listener, on any engine. It never
waits for a compiled copy, and it stops at the outer run's deadline if that comes first. On the same engine,
`RunContext.parent()` names the outer run. See [Runs that don't wait](thread-safety.md#runs-that-dont-wait).

### Null reference

A fact whose value is `null`, or a `null` `FactReference` in the store. The name is bound to `null`, so a condition such
as `coapplicant == null` is `true`, unlike a [missing fact](#missing-fact). See
[Null and missing facts](facts.md#-null-and-missing-facts).

### Output object

The object a run's actions change, which rules see as `output`. The [output supplier](#output-supplier) creates it once
per run that matches a rule, and `run()` returns it, or `null` when no rule fired. See
[The output object](../README.md#the-output-object).

### Output supplier

The `Supplier` given to `firstMatch(...)` or `allMatches(...)` that creates the [output object](#output-object). It must
return a new, non-`null` object on every call. Javadoc calls it the output factory. See
[The output object](../README.md#the-output-object).

### Priority

An optional `Integer` on a rule. Higher numbers are evaluated and fire first, equal priorities keep their list order,
and `null` sorts last. See [Rules](../README.md#rules).

### Read-only view

A map of facts that throws `UnsupportedOperationException` on a write. Conditions, actions, the listener callbacks
that receive facts, and `RunContext.facts()` all get one, and `FactStore.asMap()` returns an unmodifiable map of the
store. A fact object's own methods can still change it. See
[Guarantees](listeners-and-logging.md#-guarantees).

### Resolved language

The language a rule is compiled in: its `language`, or the [default language](#default-language) when that is `null`.
The [checksum](#checksum) uses it, so the same rules on engines with different defaults have different checksums. See
[Choosing a language per rule](languages/custom.md#-choosing-a-language-per-rule).

### Rule

An immutable `Rule`, created with `Rule.builder()`: a unique name, a condition, an action, and an optional priority,
description and language. See [Rules](../README.md#rules).

### Rule list

The `List<Rule>` you pass to `load()`. Its order only matters between rules with equal priorities; the engine keeps the
rules sorted into [evaluation order](#evaluation-order). See
[Reloading rules while running](thread-safety.md#-reloading-rules-while-running).

### Run

One call of `run(facts)` or `runWithResult(...)`: check the facts, evaluate conditions, create the output object if a
rule matched, fire actions and return. `run()` throws `IllegalStateException` before the first `load()`. See
[How it works](../README.md#-how-it-works).

### Run context

The `RunContext` that identifies one run to listeners: `runId()`, `parent()`, `matchPolicy()`, `ruleSetChecksum()` and
`facts()`. Only the engine creates one. See [Callbacks](listeners-and-logging.md#-callbacks).

### Run options

A `RunOptions` passed to `runWithResult(facts, options)` for one run. Today it holds only a timeout, from
`RunOptions.withTimeoutOf(duration)`, that replaces the engine's; `RunOptions.defaults()` changes nothing. See
[Stopping a run](error-handling.md#-stopping-a-run).

### Run result

The `RunResult` that `runWithResult(...)` returns: `output()`, `firedRules()` in firing order, and `ruleSetChecksum()`.
Its output is `null` exactly when no rule fired; a failed run throws instead of returning one. See
[The output object](../README.md#the-output-object).

### Session

A language's `Session`: its state for one [compiled copy](#compiled-copy), used by one run at a time. A language whose
expressions keep no state returns `Session.none()`; when every language of a rule list does, all its runs share one
set of sessions. See [Thread safety](languages/custom.md#-thread-safety).

### Stop

A run ended because its thread was interrupted or it passed its [deadline](#deadline). Unlike a rule failure, it throws
a `RuleExecutionException` whose `getRuleName()` is `null`, with an `InterruptedException` or `TimeoutException` cause,
and it's logged at WARN rather than ERROR. See [Stopping a run](error-handling.md#-stopping-a-run).

### Timeout

How long a run may take: `runTimeout(duration)` on the builder, or `RunOptions.withTimeoutOf(duration)` for one run. The
engine never interrupts the thread: it checks before and after each condition and action, and while a run waits for a
copy, so an expression that is already running isn't stopped unless its language checks `isCancelled()`. See
[Stopping a run](error-handling.md#-stopping-a-run).
