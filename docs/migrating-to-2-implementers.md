# 🔼 Migrating a language or an engine to 2.0

The 2.0 changes that affect code **implementing** the engine's interfaces rather than calling them.

**Who it's for:** authors of an expression language, and classes that implement `RulesEngine`, such as decorators and
test doubles.
**You'll be able to:** move a 1.x language to 2.0's compiler, sessions and results, and bring an engine of your own
up to the 2.0 interface.
**Before you start:** [Migrating from 1.x to 2.0](migrating-to-2.md), which covers every change a *caller* sees. Those
changes apply to you too; this page is what you need on top of them.

[← Documentation index](README.md)

- [If you implement RulesEngine](#-if-you-implement-rulesengine)
- [Contexts are sealed, and tests use the test kit](#-contexts-are-sealed-and-tests-use-the-test-kit)
- [Languages keep run state in sessions](#-languages-keep-run-state-in-sessions)
- [Languages compile an Expression](#-languages-compile-an-expression)
- [Actions return a result](#-actions-return-a-result)
- [A run can stop while your language runs](#-a-run-can-stop-while-your-language-runs)
- [Packaging and discovery](#-packaging-and-discovery)

---

## 🧰 If you implement RulesEngine

**What changed:** `RulesEngine` has four methods to implement instead of 1.8.0's six, and they are different ones:
the configuration methods are gone, and `validate`, `runWithResult` and `rules` are new. It also extends
`AutoCloseable`.

**Who is affected:** decorators, test doubles and any other class that implements `RulesEngine`. Callers of an engine
compile unchanged.

**What to implement.** These four are abstract:

| Method | What it does |
| --- | --- |
| `void load(List<Rule>)` | Compiles a rule list and swaps it in. The 1.x `setRuleList` renamed |
| `List<RuleCompilationException> validate(List<Rule>)` | Reports what `load()` would throw, without loading, except a language that fails while `load()` makes the copies of `copiesAtLoad(n)` |
| `RunResult<O> runWithResult(FactStore<?>, RunOptions)` | Runs the rules with per-run settings |
| `RuleSetInfo rules()` | The loaded rules, their checksum and when they were loaded |

`run(FactStore<?>)`, `runWithResult(FactStore<?>)` and `close()` are `default` methods that delegate to those, so you
only override them if you have something to add. `runWithResult(facts)` passes `RunOptions.defaults()`; a decorator
passes the options it was given on to the engine it wraps.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| `public O run(FactStore<Object> facts)` | `public O run(FactStore<?> facts)`, or drop your override and implement `runWithResult` instead |
| `setRuleList(rules)` | `load(rules)` |
| `addImport`, `addImports`, `registerLanguage`, `registerListener`, `registerListeners` | Remove them; they're now [builder](migrating-to-2.md#-engines-are-configured-on-a-builder) settings |
| Returning an output from `run` | `RunResult.of(output, firedRules, checksum)`, or the four-argument overload to report each condition's outcome |
| Reporting the loaded rules | `RuleSetInfo.of(rules, checksum, loadedAt)` |

The old `run(FactStore<Object>)` signature no longer compiles:

```text
name clash: run(FactStore<Object>) in MyEngine and run(FactStore<?>) in RulesEngine have the same erasure, yet
neither overrides the other
```

A result from `RunResult.of(...)` has empty `tags()` and a `null` `startedAt()`. `RunContext` is sealed and its one
implementation is internal to the engine, so you can't create one: call `withRun(run)` with a context an engine gave
you, such as one your listener received, to return a copy that carries that run's tags and start instant.

> [!IMPORTANT]
> `close()` has a default that does nothing, so a decorator that doesn't override it never closes the engine it
> wraps, and that engine's compiled copies and language sessions are never released. See
> [Closing](thread-safety.md#closing).

## 🧪 Contexts are sealed, and tests use the test kit

**What changed:** `CompileContext`, `EvaluationContext` and `ActionContext` in
`io.github.brantunger.unruly.api.language` are sealed, so only the engine implements them. The new
`unruly-engine-test` artifact has the contract test for languages,
`io.github.brantunger.unruly.test.ExpressionLanguageContractTest`, and `LanguageTestContexts`, which creates the
contexts for unit tests.

**Who is affected:** authors of an expression language whose tests implement a context, or who copied
`ExpressionLanguageContractTest` from the repository. A class that implements a context no longer compiles:

```text
error: class is not allowed to extend sealed class: EvaluationContext (as it is not listed in its 'permits' clause)
```

**What to change:**

- Add `io.github.brantunger:unruly-engine-test` to your test dependencies, at the engine's version.
- Replace a test's own contexts with `LanguageTestContexts.compile()`, `evaluation(facts)` and
  `action(facts, output)`.
- Delete a copied contract test, and extend `io.github.brantunger.unruly.test.ExpressionLanguageContractTest`
  instead. It has two abstract methods a 1.x copy didn't: `factProperty(...)` and `missingFactProperty(...)`, which
  run a condition such as `applicant.creditScore == 750` against a record, a JavaBean and a map fact, and against a
  misspelled property. A language that reads facts with `FactProperties.read` passes them.

See [Testing with the contract kit](languages/custom.md#-testing-with-the-contract-kit).

## 📑 Languages keep run state in sessions

**What changed:**

- `CompiledCondition.copy()` and `CompiledAction.copy()` are removed. Every run shares a rule list's compiled
  expressions, and a language keeps what changes while they run in a `Session`. `ExpressionCompiler.newSession()`
  creates one for each copy of the rules, and `evaluate` and `execute` take it as a second parameter.
- `ExpressionCompiler`, `Session` and `RulesEngine` are `AutoCloseable`. The engine closes sessions and compilers once
  it no longer needs them: after `load()` replaces the rules and their runs finish, and when the engine is closed.

**Who is affected:** authors of expression languages. `RulesEngine` extending `AutoCloseable` costs an implementer
nothing, because `close()` has a default, but the interface changed in other ways: see
[If you implement RulesEngine](#-if-you-implement-rulesengine). Rule authors change nothing.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| `copy()` returning `this`, the default | `newSession()` returning `Session.none()` |
| `copy()` returning a new expression with its own state | Keep that state in a `Session` from `newSession()`, and read it from the `session` parameter |
| `evaluate(EvaluationContext context)` | `evaluate(EvaluationContext context, Session session)`, which may throw a checked exception |
| `execute(ActionContext context)` | `execute(ActionContext context, Session session)`, which may throw a checked exception |
| Resources released by garbage collection | Release them in `Session.close()` or `ExpressionCompiler.close()` |
| An engine you discard in a test | `close()` it, or use try-with-resources; `load()`, `validate()`, `run()`, `runWithResult()` and `rules()` then throw `IllegalStateException` |

Closing an engine that is already closed does nothing. A rule list whose languages all return `Session.none()` needs
no copies at all, so no [copy limit](migrating-to-2.md#-compiled-copies-are-limited-on-virtual-threads) applies to it.
`ExpressionCompiler.warmUp(Session)` is new and optional: it lets a language compile when a session is created rather
than on its first run. See [Warming up a session](languages/custom.md#warming-up-a-session).

## 🔩 Languages compile an Expression

**What changed:**

- `ExpressionCompiler.compileCondition` and `compileAction` take an `Expression` instead of a `String`: the rule's
  name, `ExpressionKind.CONDITION` or `ACTION`, and the text.
- `InvalidExpressionException` can carry `issues()`, each with a severity, a line, a column and a message.
  `CompileContext.warn(Expression, Issue)` reports a warning, which is logged at WARN and doesn't fail loading.

`load()` now compiles every rule and then throws once. `RuleCompilationException.failures()` has each broken rule's
exception, a language that couldn't create its compiler, and each rejected declared fact name. `getExpressionKind()`
and `issues()` say what failed and where, on `RuleExecutionException` too.

Compile error messages name the expression, and your message is a fragment the engine completes: it writes
`Condition for rule 'r' ` or `Action for rule 'r' ` and then your `InvalidExpressionException`'s message, adding
`failed to compile: ` only when your compiler throws something else. So 1.x's `Can not compile rule 'r'. Error: ...`
is now `Condition for rule 'r' contains an assignment ('=' at line 1, column 16)`, or
`Condition for rule 'r' failed to compile: ...` for any other exception.

**Who is affected:** authors of expression languages, and code that reads compile error messages or causes.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| `compileCondition(String source)` | `compileCondition(Expression source)`, reading `source.text()` |
| Settings passed to a language's own constructor, such as a parser's features | `.option("my-language", "key", "value")` on the engine's builder, read from `CompileContext.options()` |
| Parsing `Can not compile rule 'x'. Error: ...` | `getRuleName()`, `getExpressionKind()` and `issues()`, or the new message |
| A whole-sentence message from your compiler | A fragment: the engine writes `Condition for rule 'r' ` before it, and adds `failed to compile: ` only for exceptions other than `InvalidExpressionException` |
| Reloading to find the next broken rule | `failures()`, which lists them all |
| A throwaway engine to check rules before loading them | `validate(rules)`, which loads nothing and returns every problem except a language that fails while `load()` makes the copies of `copiesAtLoad(n)` |
| `getCause()` is MVEL's `CompileException` | `getCause().getCause()` when one rule failed, one level deeper when several did and the exception combines them; or `issues()` |
| `new Expression(null, kind, text)` in a test | `new Expression("rule-name", kind, text)`: a rule name is required |

How the engine collects and orders those failures is in
[Errors when rules load](languages/custom.md#-errors-when-rules-load); what MVEL puts in them, including its line and
column, is in [Errors when rules load](languages/mvel.md#-errors-when-rules-load).

## 📤 Actions return a result

**What changed:** `CompiledAction.execute` returns an `ActionResult`. `ActionResult.done()` means the action changed
the output itself, as before. `ActionResult.set(properties)` returns values for the engine to set on the output: with
`put` on a `Map`, or with the output's public setters. This lets languages without side effects, such as CEL or
JsonLogic, write actions. In `ExpressionLanguageContractTest`, four hooks may return `null` to skip their checks:
`reassignOutput()`, `declareVariable()`, `unusableFactName()` and `missingFactProperty()`.

**Who is affected:** authors of expression languages. Rule authors change nothing.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| `void execute(ActionContext context)` | `ActionResult execute(ActionContext context, Session session)`, returning `ActionResult.done()` |
| Writing a computed value back to the output with reflection | `return ActionResult.set(Map.of("approved", true))` |
| An output the engine's `put`-and-setters writer can't set, such as one with builder-style methods | `.outputWriter((output, property, value) -> ...)` on the engine's builder |
| A language that needs to know the output's type | `CompileContext.outputType()`, which the application sets with `.outputType(LoanDecision.class)` |

See [Actions and results](languages/custom.md#-actions-and-results).

## ⏳ A run can stop while your language runs

**What changed:** 1.x never cancelled a run, so a language had nothing to honour. In 2.0 a run stops when its thread
is interrupted or it passes its deadline, and `EvaluationContext` and `ActionContext` gained `isCancelled()` and
`deadline()` so a language that can stop part-way may do so.

**Who is affected:** authors of expression languages. You needn't use either method: a language that evaluates an
expression and returns still works, and the engine stops the run as soon as the expression returns.

**What to change:**

| Your language | What to do |
| --- | --- |
| It can't stop inside an expression, as MVEL can't | Nothing |
| It loops or calls out, and can check | Return when `context.isCancelled()` is `true`, or use `context.deadline()` to bound a call of your own |
| It throws once the run is cancelled | Nothing: that stops the run, unless an `Error` is anywhere in the cause chain of what it threw, even wrapped in your own exception; then it is still that rule's failure |
| Its runtime clears the thread's interrupt when it cancels, as JEXL's `cancellable(true)` does | Either call `Thread.currentThread().interrupt()` before you throw or return, or throw with an `InterruptedException` in the cause chain |

Do neither and, unless the deadline has passed too, the engine sees no interrupt: a throw is reported as that rule's
failure and a return lets the run go on. [Stopping a run](languages/custom.md#-stopping-a-run) owns these rules and
shows the code; [Stopping a run](stopping-runs.md) describes what a caller sees.

## 📦 Packaging and discovery

**What changed:** an engine built without `language(...)` finds its languages with `ServiceLoader` when it is built,
so a language jar declares itself. 1.x created MVEL itself and looked for nothing, so a 1.x language jar needed no
such declaration.

**Who is affected:** anyone publishing a language in its own jar.

**What to add.** Declare the language both ways, as [Packaging](languages/custom.md#-packaging) describes in full:

| Where | What |
| --- | --- |
| Class path | A file `META-INF/services/io.github.brantunger.unruly.api.language.ExpressionLanguage` holding the class name, such as `com.example.lang.MyLanguage` |
| Module path | A `provides io.github.brantunger.unruly.api.language.ExpressionLanguage with com.example.lang.MyLanguage;` clause |

Adding them changes what your users' engines find. A user who never calls `language(...)` and has MVEL on the class
path too now gets `The engine has several expression languages, [mvel, my], so name the language of rules without one
with defaultLanguage()` from `build()`, an `IllegalStateException`, until they call either `defaultLanguage(...)` or
`language(...)`. Giving any language to the builder turns discovery off, so an engine built with
`language(new MyLanguage())` never sees this.

Say both remedies in your release notes. The class needs a public no-argument constructor, and `ServiceLoader` runs
on every `build()`, so a constructor that throws fails every engine built without `language(...)`.
[ServiceLoader](migrating-to-2.md#-expression-languages-are-found-with-serviceloader) is the same change from the
caller's side.
