# 🔨 Writing an expression language

> [!NOTE]
> Describes 2.0.0, which isn't released yet. For 1.8.0, see
> [this page at v1.8.0](https://github.com/brantunger/unruly-engine/blob/v1.8.0/docs/languages/custom.md).

How to implement `ExpressionLanguage` so rules can be written in a language of your own, and what the engine calls,
when, and on which thread.

**Who it's for:** language authors.
**You'll be able to:** implement the four interfaces, report compile errors the way the engine expects, read facts of
any shape, keep run state in sessions, package the language for both paths, and check it with the contract test kit.
**Before you start:** [Expression languages](README.md), [Facts](../facts.md) and
[Compiled copies](../compiled-copies.md).

[← Documentation index](../README.md)

- [Lifecycle at a glance](#-lifecycle-at-a-glance)
- [Implementing the interfaces](#-implementing-the-interfaces)
- [Errors when rules load](#-errors-when-rules-load)
- [Reading facts](#-reading-facts)
- [Actions and results](#-actions-and-results)
- [Fact names](#-fact-names)
- [Stopping a run](#-stopping-a-run)
- [Thread safety](#-thread-safety)
- [Packaging](#-packaging)
- [Testing with the contract kit](#-testing-with-the-contract-kit)
- [Gotchas](#-gotchas)
- [Questions you might not think to ask](#-questions-you-might-not-think-to-ask)

---

## 🛑 Lifecycle at a glance

Nothing is created at `build()`: a [compiler](../glossary.md#compiler) exists once `load()` needs it, and a
[session](../glossary.md#session) once a run does.

```mermaid
sequenceDiagram
    participant App as Application
    participant Engine
    participant Lang as ExpressionLanguage
    participant Comp as ExpressionCompiler
    participant Expr as Compiled condition or action
    participant Sess as Session
    App->>Engine: build()
    Engine->>Lang: name()
    App->>Engine: load(rules)
    Engine->>Lang: newCompiler(context), at the first rule in this language
    Engine->>Comp: compileCondition(), then compileAction(), for each rule in priority order
    App->>Engine: run(facts), on any thread
    Engine->>Comp: newSession(), when no idle copy is free
    Engine->>Comp: checkFactName(name), for each fact
    Engine->>Expr: evaluate() or execute(), with the run's session
    App->>Engine: load(newRules) or close()
    Engine->>Sess: close(), once no run uses the copy
    Engine->>Comp: close(), after its last session
```

| Method | When | Thread | Concurrent with itself? |
| --- | --- | --- | --- |
| `name()` | `language(...)`, `build()`, and when `ServiceLoader` finds the language | The building thread | Keep it constant |
| `newCompiler` | During `load()` or `validate()`, at the first rule in your language; for an empty list, only if you're the default. Never at `build()` | The calling thread | Yes: concurrent `load()` calls, and engines sharing one instance |
| `compileCondition`, `compileAction` | Each rule in priority order, condition first; the action only if the condition compiled | The `load()` or `validate()` thread | No |
| `checkFactName` | Each declared fact, once every rule has been compiled or has failed; then each fact of each run | `load()` or `validate()`, then run threads | Yes |
| `newSession` | A run that finds no idle copy of the rules | The run's thread | Yes |
| `evaluate`, `execute` | Each rule the run reaches | The run's thread | Yes, each with its own session |
| `Session.close()` | Once, when the copy it belongs to is done with (the cases are below) | Depends on the case | Yes, alongside other sessions |
| `ExpressionCompiler.close()` | Once, after its last session has closed; at once when the `load()` fails, or when `validate()` returns | The last thread to finish with the rule list, or the `load()` or `validate()` caller | Never while any method above runs |

Who closes a session depends on its copy. An extra copy, or a kept copy in use when the rules are retired: its run,
when it ends. An idle copy: the `load()` or `close()` caller that retires the rules. A copy shared by every run holds
only `Session.none()`, the session of a language that keeps no state (see [Thread safety](#-thread-safety)), whose
`close()` does nothing.

Two failures close things early. A session made for a copy that another language then fails to make is closed on the
run's thread, at once, before it's used. A `load()` that fails closes the compilers it created at once, on the
`load()` thread, before any session exists.

A run holds its copy from before `checkFactName` until after its last expression, so the compiler's `close()` never
overlaps them, and both compile methods finish before any run can see the compiler. A language the engine has but no
loaded rule uses gets no compiler, no session and no fact-name check.

## 🚀 Implementing the interfaces

Implement these interfaces from `io.github.brantunger.unruly.api.language`:

| Interface | You implement | It returns |
| --- | --- | --- |
| `ExpressionLanguage` | `name()` and `newCompiler(CompileContext)` | A new compiler for each rule list |
| `ExpressionCompiler` | `compileCondition(Expression)`, `compileAction(Expression)`, `newSession()`, and optionally `checkFactName(String)` and `close()` | Compiled expressions that every run shares |
| `CompiledCondition`, `CompiledAction` | `evaluate(EvaluationContext, Session)` and `execute(ActionContext, Session)` | A `Boolean`; an `ActionResult` |
| `Session` | Optionally `close()`, if your expressions keep state while they run | Nothing |

The engine creates the `CompileContext`, `EvaluationContext` and `ActionContext` it passes to your language. They're
sealed, so only the engine implements them; tests create them with
[`LanguageTestContexts`](#-testing-with-the-contract-kit). A method added to an interface you implement is a
`default` method, so a language written against an earlier 2.x release keeps compiling and working.

```java
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.language.*;

public final class MyLanguage implements ExpressionLanguage {

    @Override
    public String name() {
        return "my";
    }

    @Override
    public ExpressionCompiler newCompiler(CompileContext context) {
        // One compiler per rule list: keep per-list caches here. context has the imports and class loader.
        return new ExpressionCompiler() {
            @Override
            public CompiledCondition compileCondition(Expression source) {
                MyExpression parsed = MyParser.parse(source.text());   // see "Errors when rules load" below
                if (parsed.assignsSomething()) {
                    throw new InvalidExpressionException("contains an assignment");
                }
                return (evaluation, session) -> parsed.evaluate(evaluation.facts());   // must return a Boolean
            }

            @Override
            public CompiledAction compileAction(Expression source) {
                MyExpression parsed = MyParser.parse(source.text());
                return (action, session) -> {
                    parsed.execute(action.facts(), action.output());   // changes output in place
                    return ActionResult.done();
                };
            }

            @Override
            public Session newSession() {
                // Nothing changes while these expressions run. Otherwise, return a new session holding that state.
                return Session.none();
            }
        };
    }
}
```

`MyParser` and `MyExpression` stand for your language's own parser and compiled form.

**The expression.** An `Expression` has its `ruleName()`, whether it's the rule's `CONDITION` or its `ACTION`, and its
`text()`, which is never blank.

**Facts and output.** `facts()` is a read-only map of fact values by name, whose values can be `null`; writing to it
throws `UnsupportedOperationException`. Actions see the output object as `output` (`ActionContext.OUTPUT_NAME`), and the
engine already rejects a fact with that name.

**Errors while running.** An exception from `evaluate` or `execute` becomes a `RuleExecutionException` naming the rule;
a [fatal error](../glossary.md#fatal-error) is rethrown unchanged, even wrapped in your own exception. A condition that
returns anything but a `Boolean`, including `null`, fails the rule: the engine coerces nothing.

**The `CompileContext`** carries what the engine was built with, all optional: the packages and classes from
`imports(...)` with `classLoader()`, the context class loader of the `load()` or `validate()` thread; `outputType()`,
or `Object`;
`options()`, from `.option("my", "key", "value")`; and `declaredFacts()`, primitives as wrappers, with
`allFactsDeclared()`. Reject a name nobody declared only when that is `true`: otherwise a run may supply undeclared
facts.

**Warnings.** For a problem that shouldn't stop a rule loading, call `warn(source, issue)` on the `CompileContext`. The
engine logs `Condition for rule 'prime-rate' has a warning at line 2, column 5: deprecated` at WARN on
`io.github.brantunger.unruly.engine`, whatever the issue's severity.

## 🚨 Errors when rules load

What your compiler throws or returns decides what the user sees from `load()`, or gets back from `validate()`, which
compiles the same way but returns the failures and logs nothing, not even your warnings:

| You throw or return | The user sees | Reported |
| --- | --- | --- |
| `InvalidExpressionException(message, issues)` | `Condition for rule 'prime-rate' ` + your message, with your issues; your exception as the cause | Per rule, with every other broken rule |
| Any other exception | `Condition for rule 'prime-rate' failed to compile: ` + its description; no issues; your exception as the cause | Per rule |
| Anything with a `StackOverflowError` as a cause | `... failed to compile: the expression is too long or too deeply nested to compile` | Per rule |
| `null` from `compileCondition` or `compileAction` | `... wasn't compiled: its expression language returned null` | Per rule |
| An exception from `newCompiler`, or `null` | `The 'my' expression language failed to create a compiler: ` + its description, or `returned no compiler`; no rule name | Once, in place of the first rule that needed the language; the rules written in it aren't compiled |
| A [fatal error](../glossary.md#fatal-error), thrown or as a cause | Logged, then rethrown unchanged | At once |
| `IllegalArgumentException` from `checkFactName` for a declared fact | `Declared fact 'empty' can't be used: ` + your message; no rule name | Last, after the rules' failures |

`Action for rule ...` replaces `Condition for rule ...` for an action, a `null` message reads `was rejected by its
expression language`, and every failure is logged at ERROR. The engine copies your message the way
[Exceptions by method](../error-handling.md#-exceptions-by-method) describes: shortened and escaped, with the root
cause's class named when a message is missing. An exception with no message and no cause is described by its class
name alone.

An [issue](../glossary.md#issue) has a severity, a line and a column counting from 1, with 0 for unknown, and a message.
The `RuleCompilationException` carries the same issues; for several rules its message is `2 rules failed to compile:
<first>; <second>`, its name, kind and issues are the first failure's, and `failures()` has each rule's.

- **Order.** Rules compile in priority order, highest first, `null` last and equal priorities in list order, so
  `failures()` is in that order. The condition compiles before the action.
- **What the engine rejects before asking you.** A blank condition or action
  (`Rule 'prime-rate' has a blank condition expression`) and a language the engine doesn't have
  (`Rule 'prime-rate' is written in 'cel', which isn't one of the engine's expression languages: [mvel]`, with no
  expression kind) are one rule's failure, collected with the rest. A `null` rule or a duplicate name fails at once,
  before anything compiles.

> [!WARNING]
> A condition that doesn't compile hides its action's errors: the action isn't compiled, so they appear only after
> the condition is fixed and the rules are loaded again. One load reports every broken *rule*, not every broken
> expression.

When failures other than a rule's are among them, the message reads `2 failures while loading the rules: ...`
instead of `2 rules failed to compile: ...`.

## 📁 Reading facts

Rules everywhere are written `applicant.creditScore`, where the fact may be a record, a JavaBean or a `Map`. The
engine hands you the fact objects as they are, so making all three work is your job, and it's what adapters most
often get wrong: a record's component is a method, so a language that looks only for a getter or a field reads
nothing, and the condition is silently `false`. `FactProperties.read(fact, "creditScore")` does it:

| Fact | What `read` returns |
| --- | --- |
| A record | The component `creditScore()`, or one of the record's own getters when no component matches |
| A JavaBean, or any other class | The public no-argument `getCreditScore()`, or `isCreditScore()` when it returns a boolean. Public fields aren't read |
| A `Map` | The value under the exact `String` key, which may be `null` |

> [!IMPORTANT]
> `read` throws `IllegalArgumentException` when the fact has no such property. Let it reach the engine: a missing
> property is a mistake in the rule, and evaluating it to `false` or to undefined hides it.

A property that exists but whose accessor throws, or can't be called, fails with `IllegalStateException` instead, so
a getter that rejects its own state is never mistaken for a misspelled rule. A fact whose class isn't public is read
through a public supertype that declares the accessor, a superclass or an interface, or else directly where its
package is open to `io.github.brantunger.unruly.core`: always on the class path, and on the module path when the
application `opens` it; see [Packaging](#-packaging).

`FactProperties.toData(fact, depth)` converts a record, a bean or a map into a map of its properties, for a language
that reads only maps. It throws `IllegalArgumentException` for a value it doesn't take apart, such as a number, a
string or a collection, so don't call it on each fact: convert the whole fact map, with one more level.

```java
Map<String, Object> data = FactProperties.toData(evaluation.facts(), depth + 1);
// {applicant={creditScore=750}, score=750}
```

## 📤 Actions and results

An action that changes `output` in place returns `ActionResult.done()`. A language whose expressions compute values
without side effects, such as CEL or JsonLogic, returns `ActionResult.set(Map.of("approved", true, "interestRate",
4.5))` instead. `set` copies the map; a `null` name throws `NullPointerException`, an empty one
`IllegalArgumentException`, and `null` values are allowed.

The engine sets each property in map order after the action returns, and after its cancellation check, with its
`OutputWriter`: by default `put` on a `Map` output, or the output's public setter, such as `setInterestRate`, whose
parameter accepts the value as it is, reached the same way `FactProperties` reaches a getter. The application can set
its own writer with `.outputWriter(...)`, so don't assume how a property is stored. A property the writer can't set,
or a `null` result, fails the rule with a `RuleExecutionException`. In an all-matches run, a later rule's properties
overwrite an earlier one's.

## 🔤 Fact names

Override `checkFactName(String)` to reject a name your rules couldn't refer to, such as a keyword, by throwing an
`IllegalArgumentException`; by default every name is accepted. The engine calls it:

- for every fact of every run, on the run's thread, after `beforeRun`, so a rejection reaches `onRunError`. `run()`
  throws your exception as it is, logged at ERROR. Anything else you throw becomes an `IllegalArgumentException`
  reading `The 'my' expression language failed to check fact name 'x': ...`, except a fatal error, which is rethrown;
- for each [declared fact](../facts.md#-declaring-facts) at `load()`, once every rule has been compiled or has failed,
  by the compilers that were created; when none was, the names wait for the next `load()`. A rejection fails
  the load with `Declared fact 'empty' can't be used: ` followed by your message.

Only the languages the loaded rules use are asked, in the order the rules first used them, or the default language
for an empty rule list. The engine has already rejected `null` and `output`, and caches nothing, so keep the check
cheap and thread-safe.

## ⏳ Stopping a run

A run can be interrupted, or given a [timeout](../stopping-runs.md). A run your language starts from inside an
expression, on the same thread, stops no later than the run around it. The engine checks between rules, so what your
language can do decides whether a rule that is already running can be stopped:

| Language | Can it stop inside an expression? |
| --- | --- |
| MVEL | No. It has no hook inside a loop, so `while (true) {}` runs for ever. |
| JEXL 3 | Yes, with [`JexlBuilder.cancellable(true)`](https://commons.apache.org/proper/commons-jexl/apidocs/org/apache/commons/jexl3/JexlOptions.html), whose interpreter checks for interruption and cancellation. |
| CEL | Bounded by construction: the language isn't Turing-complete, and cel-java supports cost limits. |

Both contexts tell an expression where it stands:

```java
public CompiledAction compileAction(Expression expression) {
    return (context, session) -> {
        while (moreWork()) {
            if (context.isCancelled()) {         // interrupted, or past the deadline
                return ActionResult.done();
            }
            step(context.deadline());            // null when the run has no deadline
        }
        return ActionResult.done();
    };
}
```

`isCancelled()` is `true` while the calling thread is interrupted, or once the deadline has passed. It reads the thread
it's called on, so call it on the run's thread, not from a worker your language hands work to.

Returning when it's `true` is enough: the engine checks again as soon as the expression returns, and stops the run
whatever it returned. Throwing an exception once the run is cancelled stops the run the same way; an `Error` is still
that rule's failure.

`deadline()` is an `Instant`, or `null` when the run has no timeout. Use it to give a call of your own a timeout.

Neither is required. A language that evaluates an expression and returns needn't check anything.

A runtime that clears the thread's interrupt status when it cancels, as JEXL's `cancellable(true)` does before it
throws `JexlException.Cancel`, hides the caller's interrupt from the engine, which sees an interrupt only in that
status or as an `InterruptedException` in the cause chain of what an expression throws. Unless the deadline has passed
too, a throw is then reported as that rule's failure, logged at ERROR, and a return lets the run go on. Call
`Thread.currentThread().interrupt()` before you throw or return, or throw with an `InterruptedException` as the cause.

## 🧵 Thread safety

| What | The rule |
| --- | --- |
| Your `ExpressionLanguage` instance | May serve several engines and concurrent `load()` calls at once: keep it stateless, with a constant `name()` |
| `compileCondition`, `compileAction` | Called on one thread, the `load()` caller, and all finish before any run sees the compiler |
| `checkFactName`, `newSession` | Called from many threads at once |
| Compiled conditions and actions | Shared by every run, on many threads at once, each with its own session |
| A `Session` | Used by one run at a time, possibly on different threads one after another |
| `Session.close()` | May run while other sessions of the same compiler are in use, so don't tear down what they share |
| `isCancelled()` | Reads the calling thread's interrupt status and the deadline, so call it on the run's thread |
| `ExpressionCompiler.close()` | Never runs while any of the above does |

Keep whatever changes while an expression runs in a `Session`: `newSession()` creates one for each
[compiled copy](../glossary.md#compiled-copy) of the rules, and every condition and action of your language in a run
gets that copy's session. Return `Session.none()` when your compiled expressions keep no state while they run, and a
new session when they do, such as a single-threaded interpreter context. A rule list whose languages all return
`Session.none()` needs no copies: every run shares one set of sessions, and no
[copy limit](../compiled-copies.md#-limiting-the-copies) applies to it.

> [!WARNING]
> Only the `Session.none()` instance counts as stateless: the engine checks identity, not `equals`. A stateless
> `new MySession()` silently turns on copies and the copy limit for every rule list that uses your language.

A `newSession()` that throws or returns `null` fails the run that needed the session with a `RuleExecutionException`,
logged at ERROR, and closes the sessions other languages already made for that copy; it happens before `beforeRun`,
so no listener is told. A `close()` that throws is logged at WARN and the rest are still closed; only a fatal error
is rethrown.

## 📦 Packaging

A language in its own jar needs only `unruly-engine-core`, the engine without MVEL. An engine built without
`language(...)` finds a language when its jar declares it as a service, and the class needs a public no-argument
constructor. Declare it both ways, to support both paths:

- **Class path:** a file `META-INF/services/io.github.brantunger.unruly.api.language.ExpressionLanguage` that contains
  the class name, such as `com.example.lang.MyLanguage`.
- **Module path:** a `provides` clause. The engine's module `uses` the service, and `ServiceLoader` runs on every
  `build()`, so a constructor that throws fails every engine built without `language(...)`.

```java
module com.example.lang {
    requires io.github.brantunger.unruly.core;   // transitive, if your public API exposes engine types

    provides io.github.brantunger.unruly.api.language.ExpressionLanguage
            with com.example.lang.MyLanguage;

    // exports com.example.lang;                 // only if applications call language(new MyLanguage())
}
```

The engine, not your module, reads facts with `FactProperties` and writes the output with its default
`OutputWriter`, from the module `io.github.brantunger.unruly.core`. So an application whose rules use your language
exports or opens its fact and output packages to that module, and needn't export them to yours:

```java
module com.example.app {
    requires io.github.brantunger.unruly.core;
    exports com.example.app.model to io.github.brantunger.unruly.core;   // or opens, for classes that aren't public
}
```

A public class needs `exports`; a class that isn't public needs `opens`, because calling its method is deep
reflection. A language that reflects on facts itself needs its own access: in MVEL's case an export with no `to`
clause at all, because the accessor classes MVEL generates live in the unnamed module, as the root README's
[Installation](../../README.md#-installation) block explains. A test that extends the contract kit opens its package
`to org.junit.platform.commons`.

## 🧪 Testing with the contract kit

The `unruly-engine-test` artifact, at the same version as the engine, has two tools for a language's tests. Add it
with test scope, for example `testImplementation 'io.github.brantunger:unruly-engine-test:<version>'`. It's built with
JUnit Jupiter 6, and brings JUnit Jupiter's API.

`ExpressionLanguageContractTest` checks the promises above for any language. Extend it and supply expressions in your
language, one method for each hook. Its eleven checks:

| Check | Hooks | Skippable? | Passes when |
| --- | --- | --- | --- |
| `conditionReadsFacts` | `factEquals`, `putFact` | No | Fires for `x` = 1, not for 2 |
| `conditionMustBeBoolean` | `factValue`, `putFact` | No | `true` fires; `null`, `"true"` and `1` fail the rule |
| `conditionAssignmentRejected` | `assignment`, `putFact` | No | `load()` throws, message starting `Condition for rule 'r' ` |
| `outputNotReplaceable` | `alwaysTrue`, `reassignOutput` | `reassignOutput()` returns `null` | `load()` or `run()` throws an `UnrulyException` |
| `actionVariablesStayLocal` | `alwaysTrue`, `declareVariable`, `putFact` | `declareVariable()` returns `null` | A later rule still sees the fact's value |
| `syntaxErrorAtLoad` | `syntaxError`, `putFact` | No | `load()` throws, naming the rule and `CONDITION` |
| `unusableFactNameRejected` | `alwaysTrue`, `putFact`, `unusableFactName` | `unusableFactName()` returns `null` | `run()` throws `IllegalArgumentException` |
| `conditionReadsProperties` | `factProperty`, `putFact` | No | `applicant.creditScore == 750` matches a record, a bean and a map |
| `missingPropertyFailsTheRun` | `missingFactProperty`, `putFact` | `missingFactProperty()` returns `null` | `creditScor` on a record fails `load()` or `run()` |
| `compilerClosed` | `factEquals`, `putFact` | No | Each compiler is closed exactly once, after a reload and after `close()` |
| `concurrentRuns` | `factEquals`, `putFact` | No | 8 threads, 200 runs each, all see their own facts |

- Only the four `@Nullable` hooks, `declareVariable`, `reassignOutput`, `unusableFactName` and
  `missingFactProperty`, may return `null`. `assignment()` and `syntaxError()` can't be skipped: a language with no
  assignment syntax must still make `load()` reject what `assignment()` returns.
- `language()` is called for each check and for each engine a check builds, so return a new instance.
- Each check builds `allMatches(HashMap::new).language(language())`, with no imports, options or declared facts, so
  the language must work alone. `factValue(x)` must not coerce `"true"` or `1` to a boolean. Output numbers are
  compared by value, so `Long` or `Double` whole numbers pass.

`LanguageTestContexts` creates the contexts the engine passes to a language, to test a compiler or a compiled
expression without an engine. They're the engine's own contexts: writing to their facts fails as in a run, and
`evaluation(facts, deadline)` gives a real `isCancelled()`.

```java
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;
import io.github.brantunger.unruly.test.LanguageTestContexts;

class MyLanguageContractTest extends ExpressionLanguageContractTest {
    @Override
    protected ExpressionLanguage language() {
        return new MyLanguage();
    }

    @Override
    protected String factEquals(String fact, int value) {
        return fact + " == " + value;
    }

    // ... one method for each expression the checks need

    @Test
    void conditionComparesFacts() throws Exception {
        ExpressionCompiler compiler = language().newCompiler(LanguageTestContexts.compile());
        CompiledCondition condition = compiler.compileCondition(
                new Expression("r", ExpressionKind.CONDITION, factEquals("x", 1)));

        assertEquals(true, condition.evaluate(LanguageTestContexts.evaluation(Map.of("x", 1)), compiler.newSession()));
    }
}
```

On the module path, the kit is the module `io.github.brantunger.unruly.test`; see [Packaging](#-packaging).

## 🚧 Gotchas

| Gotcha | What happens | Do this instead |
| --- | --- | --- |
| **A stateless session of your own** | `new MySession()` with no state still gets copies and the copy limit: only `Session.none()` itself counts | Return `Session.none()` |
| **A missing property read as `false`** | The rule never fires, and nothing says why | Use `FactProperties.read`, and let its `IllegalArgumentException` reach the engine |
| **`toData` on each fact** | Throws for a number, a string or a collection | Convert `evaluation.facts()` itself, with `depth + 1` |
| **A condition that doesn't compile** | Its action isn't compiled, so the action's errors appear only after the next `load()` | Expect a second failure after fixing a condition |
| **A runtime that clears the interrupt** | An interrupted rule is reported as the rule's failure, at ERROR, not as a stop | Restore the interrupt status, or throw with an `InterruptedException` cause |
| **`isCancelled()` from a worker thread** | It reads that thread's interrupt status, so the run thread's interrupt is missed | Poll it on the run's thread |

## ❓ Questions you might not think to ask

### When is `newCompiler` called, and can I do expensive setup there?

During `load()`, at the first rule in your language, or for an empty rule list if you're the default; never at
`build()`. Once per `load()`, so per-list setup belongs there. See [Lifecycle at a glance](#-lifecycle-at-a-glance).

### Are sessions created for runs that never reach my rules?

Yes. A copy of the rules has one session for every language the rule list uses, whichever rules a run reaches. See
[Thread safety](#-thread-safety).

### If `newSession()` fails, do listeners hear about it?

No. It happens before `beforeRun`, so the run throws a `RuleExecutionException`, logged at ERROR, and no listener is
called. See [Thread safety](#-thread-safety).

