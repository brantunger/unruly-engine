# 📖 Glossary

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
language authors deal with it. See [Actions and results](languages/custom.md#-actions-and-results).

### All-matches engine

An engine built with `RulesEngineBuilder.allMatches(...)`, which evaluates every condition and then fires every match.
See [match policy](#match-policy).

### Artifacts

The engine is published as three jars in the `io.github.brantunger` group: `unruly-engine`
(the MVEL language, which brings in the engine), `unruly-engine-core` (the engine without a language) and
`unruly-engine-test` (the [contract test kit](#contract-test-kit)). The `benchmarks` project isn't published. See
[Installation](../README.md#-installation) and [Packaging](languages/custom.md#-packaging).

### Build slot

What a run on a virtual thread waits for, on an engine built with `unlimitedCopies()`, before it makes a new
[compiled copy](#compiled-copy); it holds the slot until that copy's first run ends. An engine has one for each
processor. They pace how many new copies are in their first run at once, without bounding how many copies exist: a
run that gives up waiting makes its copy without one. See
[Waiting for a build slot](virtual-threads.md#-waiting-for-a-build-slot).

### Carrier thread

The platform thread that a virtual thread runs on. On JDK 21 to 23, a virtual thread waiting on a monitor keeps its
carrier, which is why MVEL rules on many virtual threads can deadlock. See [MVEL](languages/mvel.md#-virtual-threads).

### Checksum

A lowercase hex SHA-256 that identifies a loaded rule list: each rule's name, priority,
[resolved language](#resolved-language), condition, action, whether it's enabled, its
[validity window](#validity-window) and its [tags](#tag), in [evaluation order](#evaluation-order), but not its
description, and not the match policy. `RuleSetInfo.checksum()` gives the engine's current one, and
`RunResult.ruleSetChecksum()` the one a run used. See [Auditing a decision](engines-and-runs.md#-auditing-a-decision).

### Close

`RulesEngine.close()` marks the engine closed and returns at once, without waiting for runs in progress. Runs already
going finish with the rules they have; their sessions are closed as each one returns, and the compilers after the last.
Afterwards `run()`, `runWithResult()`, `load()` and `rules()` throw `IllegalStateException`. See
[Thread safety](thread-safety.md#closing).

### Compiled copy

The per-run state of a loaded rule list: one [session](#session) for each language its rules use. A run borrows a copy
no other run is using, or makes a new one, and gives it back when it ends; the engine keeps copies until the next
`load()` or `close()`. An engine can also make them when the rules load, as [copies at load](#copies-at-load). See
[Compiled copies](compiled-copies.md).

### Compiler

An `ExpressionCompiler`: what an [expression language](#expression-language) creates for each rule list that uses it,
when `load()` compiles the list. It compiles conditions and actions, checks fact names and creates sessions. The
engine closes it after a later `load()` or `close()` replaces the rule list, once no run still uses it. See
[Lifecycle at a glance](languages/custom.md#-lifecycle-at-a-glance).

### Condition

The expression that decides whether a rule [matches](#match-and-fire). It must evaluate to a boolean; `null` or any
other type fails the rule. It can't assign to a fact: a language that passes the
[contract test kit](#contract-test-kit), and can express an assignment, rejects that at `load()` or when the rule
runs, and the engine itself rejects any write to the facts. See [Writing rules](writing-rules.md#-anatomy-of-a-rule).

### Contract test kit

`ExpressionLanguageContractTest`, in the `unruly-engine-test` artifact, which a language's own test extends to check the
promises every language must keep. The same artifact has `LanguageTestContexts` for testing a compiler without an
engine. See [Testing with the contract kit](languages/custom.md#-testing-with-the-contract-kit).

### Copies at load

The [compiled copies](#compiled-copy) `load()` makes itself, before it swaps the new rules in, on an engine built with
`copiesAtLoad(n)`; by default it makes none. Each copy's sessions are warmed up with `ExpressionCompiler.warmUp`, and
runs borrow the copies ready instead of making them. See
[Making copies at load](compiled-copies.md#making-copies-at-load).

### Copy limit

The most [compiled copies](#compiled-copy) an engine keeps, and so the most runs that make progress at once. By default
an engine limits runs on virtual threads only, to one copy for every two processors and at least one; `maxCopies(n)`
limits every thread and `unlimitedCopies()` leaves only [build slots](#build-slot). The limit belongs to the engine,
so every rule list it loads shares it and a reload never raises it. [Copies made at load](#copies-at-load) are kept
too, and under the default limit can be more than it. See
[Limiting the copies](compiled-copies.md#-limiting-the-copies).

### Deadline

The instant a run must stop, taken from its [timeout](#timeout) when `run()` is called. A [nested run](#nested-run)
stops at whichever comes first, its own deadline or the outer run's. See
[Nested runs](stopping-runs.md#-nested-runs).

### Declared fact

A fact named on the builder with `fact(name, type)` or `facts(map)`. A run fails with `IllegalArgumentException` when
the fact is present and its value isn't of that type. A `null` value passes. A missing fact passes too, unless
`requireDeclaredFacts()` is set, which rejects a missing declared fact and any fact nobody declared. See
[Declaring facts](facts.md#-declaring-facts).

### Default language

The language of a rule whose `language` is `null`: the one named with `defaultLanguage(name)`, or else the engine's only
language. `build()` fails when an engine has several languages and no default. See
[How the engine picks a language](languages/README.md#-how-the-engine-picks-a-language).

### Evaluation order

The order a run evaluates rules in: highest [priority](#priority) first, equal priorities in their list order, and a
`null` priority after every number. `rules().rules()` lists the loaded rules in this order. See
[Rule order](engines-and-runs.md#-rule-order).

### Expression language

A plug-in, `ExpressionLanguage`, that compiles and runs conditions and actions. MVEL, from the `unruly-engine` artifact,
is one; an engine finds languages with `ServiceLoader` unless you give it some with `language(...)`. See
[Expression languages](languages/README.md).

### Extra copy

A [compiled copy](#compiled-copy) made above the [copy limit](#copy-limit) for a run that doesn't wait: a
[nested run](#nested-run), or a run that waited five seconds without any copy being given back. It isn't kept: its
sessions are closed when the run ends. See [Runs that don't wait](compiled-copies.md#runs-that-dont-wait).

### Fact

A named input value that rules refer to by its name, such as `applicant`. `Fact` is the built-in `FactReference`. A fact
can't be named `null` or `output`, or have a name the rules' languages can't refer to. See
[Facts](facts.md#-the-fact-types).

### Fact store

The facts for a run: a `FactStore`, of which `FactMap` is the built-in implementation. A run copies its entries through
`asMap()` once, when it starts, and never changes it, so a store can serve one run after another; a new one for each
request is the simplest. See [Reusing and sharing a store](facts.md#-reusing-and-sharing-a-store).

### Fatal error

A `VirtualMachineError` other than `StackOverflowError`, such as `OutOfMemoryError`, found anywhere in the cause chain.
The engine logs it and rethrows it unchanged from `load()` or `run()`; every other `Error` from a rule is reported as
that rule's failure. See [Exceptions by method](error-handling.md#-exceptions-by-method).

### First-match engine

An engine built with `RulesEngineBuilder.firstMatch(...)`, which evaluates conditions in order and fires only the first
match. See [match policy](#match-policy).

### Flight Recorder event

What the engine records for JDK Flight Recorder: one `io.github.brantunger.unruly.Run` event for each `run()` call,
on by default for runs over 10 ms, and one `io.github.brantunger.unruly.Rule` event for each condition and action,
off by default. See [Flight Recorder events](listeners-and-logging.md#-flight-recorder-events).

### Issue

An `InvalidExpressionException.Issue`: one problem a language found in an expression, with a severity, a line and a
column counting from 1 (0 when unknown), and a message. A `RuleCompilationException` carries the issues of the
expression that failed. See [Errors when rules load](languages/custom.md#-errors-when-rules-load).

### Listener

A `RuleListener` added with `listener(...)` or `listeners(...)` on the builder, told about each run, condition, action
and failure. Every `beforeRun` is followed by exactly one `afterRun` or `onRunError`, and the same listener is called
from every thread. See [Callbacks](listeners-and-logging.md#-callbacks).

### Load

`load(rules)` compiles a whole [rule list](#rule-list), then swaps it in with one atomic write. If it fails, `load()`
throws a `RuleCompilationException` and the old rules stay in place; runs already going finish with the rules they
started with. See [Reloading rules](engines-and-runs.md#-reloading-rules).

### Loaded rules

The rules an engine runs now, reported by `rules()` as a `RuleSetInfo`: `rules()` in
[evaluation order](#evaluation-order), `checksum()` and `loadedAt()`. Before the first `load()` it has no rules, the
checksum of an empty list and a `null` load time. See [The loaded rules](engines-and-runs.md#the-loaded-rules).

### Match and fire

A rule *matches* when its condition evaluates to `true`, and *fires* when its action runs. Every rule that fires has
matched, but on a first-match engine only the first match fires. See
[First match or all matches](engines-and-runs.md#-first-match-or-all-matches).

### Match policy

Which matching rules fire, fixed when the engine is built: `firstMatch` fires the first match in evaluation order,
`allMatches` evaluates every condition, then fires every match in priority order, and `uniqueMatch` evaluates every
condition, fires the one match, and fails the run when there are more. Each considers only the rules the run uses; see
[Skipped rule](#skipped-rule). `RunContext.matchPolicy()` returns
`"firstMatch"`, `"allMatches"` or `"uniqueMatch"`. 1.x called the first two *stateless* and *stateful*. See
[First match or all matches](engines-and-runs.md#-first-match-or-all-matches).

### Missing fact

A name that isn't in the [fact store](#fact-store) at all, unlike a [null reference](#null-reference). In MVEL,
referring to it fails the rule with `unresolvable property or identifier`, or `unable to resolve variable` when the
run's [compiled copy](#compiled-copy) already ran the rule with it, so test for it with `isdef`; another language
decides for itself. See [Null and missing facts](facts.md#-null-and-missing-facts) and
[in MVEL](languages/mvel.md#null-and-missing-facts).

### Nested run

A run started on the same thread from inside another run, such as from an action or a listener, on any engine. It never
waits for a compiled copy or a [build slot](#build-slot), and it stops at the outer run's deadline if that comes first.
On the same engine, `RunContext.parent()` names the outer run. See [Nested runs](stopping-runs.md#-nested-runs) and
[Runs that don't wait](compiled-copies.md#runs-that-dont-wait).

### Null reference

A fact whose value is `null`, or a `null` `FactReference` in the store. The name is bound to `null`, so in MVEL a
condition such as `coapplicant == null` is `true`, unlike for a [missing fact](#missing-fact). See
[Null and missing facts](facts.md#-null-and-missing-facts) and [in MVEL](languages/mvel.md#null-and-missing-facts).

### Output object

The object a run's actions change, which rules see as `output`. The [output supplier](#output-supplier) creates it once
per run that matches a rule, and `run()` returns it, or `null` when no rule fired. See
[The output object](engines-and-runs.md#-the-output-object).

### Output supplier

The `Supplier` given to `firstMatch(...)`, `allMatches(...)` or `uniqueMatch(...)` that creates the
[output object](#output-object). It must return a new, non-`null` object on every call. Javadoc calls it the output
factory. See
[The output object](engines-and-runs.md#-the-output-object).

### Priority

An optional `Integer` on a rule. Higher numbers are evaluated and fire first, equal priorities keep their list order,
and `null` sorts after every number. See [Rule order](engines-and-runs.md#-rule-order).

### Read-only view

A map of facts that throws `UnsupportedOperationException` on a write. Conditions, actions, the listener callbacks
that receive facts, and `RunContext.facts()` all get one, and `FactStore.asMap()` returns an unmodifiable map of the
store. A fact object's own methods can still change it. See
[Who sees facts](facts.md#-who-sees-facts).

### Resolved language

The language a rule is compiled in: its `language`, or the [default language](#default-language) when that is `null`.
The [checksum](#checksum) uses it, so the same rules on engines with different defaults have different checksums. See
[Choosing a language per rule](languages/README.md#-choosing-a-language-per-rule).

### Rule

An immutable `Rule`, created with `Rule.builder()`: a unique name, a condition, an action, and an optional priority,
description and language. It's enabled, with no [validity window](#validity-window) and no [tags](#tag), unless
those are set. See [Writing rules](writing-rules.md#-anatomy-of-a-rule).

### Rule evaluation

What a run found out about one rule, in `RunResult.evaluations()`: the rule and its outcome, `MATCHED`, `NOT_MATCHED`,
`NOT_EVALUATED` or `SKIPPED`, and any detail its language gave about the condition. A first-match engine reports the
rules after the match as not evaluated; the other policies evaluate every rule the run uses. A
[skipped rule](#skipped-rule) is `SKIPPED` on every policy, wherever it is. A rule whose condition failed has none,
because the run throws instead. See [What a run reports](engines-and-runs.md#-what-a-run-reports).

### Rule list

The `List<Rule>` you pass to `load()`. Its order only matters between rules with equal priorities; the engine keeps the
rules sorted into [evaluation order](#evaluation-order). See [Rule order](engines-and-runs.md#-rule-order).

### Run

One call of `run(facts)` or `runWithResult(...)`: check the facts, evaluate conditions, create the output object if a
rule matched, fire actions and return. `run()` throws `IllegalStateException` before the first `load()`. See
[Engines and runs](engines-and-runs.md).

### Run context

The `RunContext` that identifies one run to listeners: `runId()`, `parent()`, `matchPolicy()`, `ruleSetChecksum()`,
`facts()`, `tags()` and `startedAt()`. The last two are the [tags](#tag) the run was given, and the instant it judged
every [validity window](#validity-window) at. It's sealed: its one implementation is internal, and the engine creates
the contexts it passes. See [Callbacks](listeners-and-logging.md#-callbacks).

### Run options

A `RunOptions` passed to `runWithResult(facts, options)` for one run. It can hold a timeout, from
`RunOptions.withTimeoutOf(duration)` or `withTimeout(duration)`, that replaces the engine's, and [tags](#tag), from
`withTags(tags)`, that choose the rules the run uses. Each `with` method returns a copy that keeps the other
setting; `RunOptions.defaults()` changes nothing. See [Stopping a run](stopping-runs.md#-quick-start) and
[Tags](engines-and-runs.md#tags).

### Run result

The `RunResult` that `runWithResult(...)` returns: `output()`, `firedRules()` in firing order, `evaluations()` with
every rule's outcome, `ruleSetChecksum()`, and the run's `tags()` and `startedAt()`, as on the
[run context](#run-context). Its output is `null` exactly when no rule fired; a failed run throws instead of returning
one. See [What a run reports](engines-and-runs.md#-what-a-run-reports).

### Session

A language's `Session`: its state for one [compiled copy](#compiled-copy), used by one run at a time. A language whose
expressions keep no state returns `Session.none()`; when every language of a rule list does, all its runs share one
set of sessions. See [Thread safety](languages/custom.md#-thread-safety).

### Skipped rule

A loaded rule a run doesn't use: it's disabled, outside its [validity window](#validity-window) when the run starts,
or carries none of the [tags](#tag) the run was given. Its condition isn't evaluated, no listener hears about it, and
its [rule evaluation](#rule-evaluation) is `SKIPPED`. `load()` still compiles it. See
[Choosing which rules a run uses](engines-and-runs.md#-choosing-which-rules-a-run-uses).

### Stop

A run ended because its thread was interrupted or it passed its [deadline](#deadline). Unlike a rule failure, it throws
a `RuleExecutionException` whose `getRuleName()` is `null`, with an `InterruptedException` or `TimeoutException` cause,
and it's logged at WARN rather than ERROR. See [What stops a run](stopping-runs.md#-what-stops-a-run).

### Tag

A name that groups rules, such as a market or a product, set with `tags(...)` on a rule's builder. A run given tags
with `RunOptions.defaults().withTags(...)` uses only the rules that carry at least one of them, compared exactly,
case included, and [skips](#skipped-rule) the rest, including rules with no tags. See
[Tags](engines-and-runs.md#tags).

### Timeout

How long a run may take: `runTimeout(duration)` on the builder, or `RunOptions.withTimeoutOf(duration)` for one run. The
engine never interrupts the thread: it checks before and after each condition and action, and while a run waits for a
copy, so an expression that is already running isn't stopped unless its language checks `isCancelled()`. See
[What a timeout doesn't do](stopping-runs.md#-what-a-timeout-doesnt-do).

### Unique-match engine

An engine built with `RulesEngineBuilder.uniqueMatch(...)`, which evaluates every condition, fires the one match, and
fails the run, naming every matched rule, when more than one is true. See
[match policy](#match-policy) and [Unique match: one rule or none](engines-and-runs.md#unique-match-one-rule-or-none).

### Validity window

When runs use a rule: from its `validFrom`, inclusive, until its `validTo`, exclusive; a `null` start or end is
open. A run reads the engine's clock, `clock(...)` on the builder or else `Clock.systemUTC()`, once when `run()` is
called, and [skips](#skipped-rule) a rule outside its window at that instant, which `startedAt()` on the
[run context](#run-context) and the [run result](#run-result) returns. See
[The validity window and the engine's clock](engines-and-runs.md#the-validity-window-and-the-engines-clock).
