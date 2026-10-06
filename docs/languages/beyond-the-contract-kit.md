# 🔬 Testing beyond the contract kit

What the contract kit's checks leave untested, how each runs, how to give them declared facts, imports or options,
and how to test a compiler or a compiled expression without an engine.

**Who it's for:** language authors.
**You'll be able to:** tell what passing the kit doesn't prove, read how each check runs, configure its checks for
your language, test a compiled expression with `LanguageTestContexts`, and run those tests from a named module with
Maven.
**Before you start:** [The contract test kit](contract-kit.md).

[← Documentation index](../README.md)

---

## 🔍 What the kit doesn't check

Two things no check exercises, so passing the kit says nothing about them.

**Cancellation.** No check runs the rules with an interrupt, a deadline or a timeout. A runtime that clears the
thread's interrupt status when it cancels, as JEXL's `cancellable(true)` does, passes the kit and still hides the
caller's interrupt from the engine.

The hole is a narrow one. The engine checks before each condition and each action, again when each returns, and once
an action's properties are set, so an interrupt raised between rules always stops the run, and the deadline path is
unaffected. Only an interrupt raised and swallowed inside one expression escapes; see
[Stopping a run](custom.md#-stopping-a-run).

Putting the interrupt back has its own trap, which the kit doesn't catch either: an adapter that restores it after
cancelling its runtime for the deadline turns each timeout into an interrupt. And JEXL clears the status whatever
cancelled it, so an interrupt that lands as the adapter cancels for the deadline is lost, and the run reports the
timeout.

**The `CompileContext`.** Every check compiles with the context `configure` or `compileContext()` gives, empty by
default, so a language that ignores imports, options, declared facts and the output type passes. Test how your
language handles each;
[Implementing the interfaces](custom.md#-implementing-the-interfaces) says what the context carries.

## 🔧 A language that needs declared facts, imports or options

Override `configure` to add them to the checks' engines, and `compileContext()` to give `evaluateAgreesWithDetail`
the same. For `languageImports(...)`, use the
[`compile(...)` overload](#-testing-a-compiler-without-an-engine) that takes them.

The checks' expressions read `x`, `y`, `applicant`, `nest` and the names `usableFactNames()` returns. `x` is also a
`Boolean`, a `String` and `null`, and `applicant` a record, two beans and a map, so declare both as `Object`: a fact of
another type fails the run before your language sees it. Declare `nest` as `Object` too, or as
`ExpressionLanguageContractTest.Nesting` if your language resolves properties from the declared type. Your language
must not reserve `x`, `y`, `applicant` or `nest`: every check that builds an engine
[then fails](contract-kit.md#-testing-with-the-contract-kit).

```java
@Override
protected void configure(RulesEngineBuilder<Map<String, Object>> builder) {
    builder.fact("x", Object.class).fact("y", Object.class).fact("applicant", Object.class).fact("nest", Object.class);
}

@Override
protected CompileContext compileContext() {
    return LanguageTestContexts.compile(Set.of(), Set.of(), getClass().getClassLoader(), Object.class, Map.of(),
            Map.of("x", Object.class, "y", Object.class, "applicant", Object.class, "nest", Object.class), false);
}
```

If your language compiles only against declared facts and doesn't reserve or reject `output`, declare it as `Object` in
`configure` too: `unreservedOutputReadAsFact` runs a rule that reads a fact by that name. A language that reserves it
mustn't, since `build()` then fails.

`configure` must not call `requireDeclaredFacts()`, since each run supplies only its check's facts, or set
`runTimeout(...)`, `maxCopies(...)` below 2, another language, `defaultLanguage(...)` or `outputWriter(...)`: the
checks could fail for reasons unrelated to your language. Nor may it declare a name your language
[reserves](custom.md#-fact-names), which fails `build()`, or the `unusableFactName()` name: `load()`
checks declared names with your language, which rejects it. A `copiesAtLoad` or `maxCopies` it sets doesn't change
the checks that set their own. A `maxCopies(2)` keeps `concurrentRuns` to two sessions, so it can't catch a repeat
after the second.

## 📋 How each check runs

What each check of the [contract kit](contract-kit.md#-testing-with-the-contract-kit) does beyond its row in the
checks table, and where each falls short.

Each check but `evaluateAgreesWithDetail` builds an engine with `allMatches(HashMap::new).language(language())` and
[`configure(builder)`](#-a-language-that-needs-declared-facts-imports-or-options), which adds nothing by default, and
closes it however the check ends. `copiesAtLoad` and `sessionsClosed` then add `copiesAtLoad(2)`, and
`compilerClosed` adds `copiesAtLoad(1)`.

`concurrentPrepares` builds eight engines with one instance of your language. It makes the eight builders, calling
`configure` for each, on the test's thread, then releases eight threads together. Each calls `prepare()`, builds its
engine, which prepares the instance again, and loads and runs one rule. The engines are closed together once the
threads are stopped, waiting up to 5 seconds. The failure names every thread that failed, or hadn't finished within
30 seconds of the release, and the step it was at, with what each threw attached.

`sessionClosedOnAnotherThread` adds `copiesAtLoad(0)`. `sessionClosedWhileAnotherRuns` adds `copiesAtLoad(0)` and
`maxCopies(1)`. `conditionDetail`, `failedActionVariablesStayLocal`, `sharedStateStaysLocal`, the later-run parts of
`actionVariablesStayLocal`, `conditionAssignmentRejected`, `conditionWritesRejected`, `outputNotReplaceable` and
`missingPropertyFailsTheRun` add the same two, so each later run gets the copy, and the sessions, the run before it
used.

`compilerClosed`, `conditionDetail`, `concurrentRuns` and the three session checks (`sessionsClosed`,
`sessionClosedWhileAnotherRuns` and `sessionClosedOnAnotherThread`) wrap your language to watch its compiler or
sessions, and `unusableFactNameRejected`, `unreservedOutputReadAsFact` and `factNamesReadHoldsTheFactsRead` to watch
its fact names. The wrappers forward `warmUp`, so copies made at load warm up as without the kit.

`factValue(x)` must not coerce `"true"` or `1` to a boolean. Output numbers are compared by value, so `Long` or
`Double` whole numbers pass. When an output differs only in a value's type, such as the `Integer` 1 and the `String`
"1", which print the same, the failure says so. Except in `usableFactNamesAccepted`, a failure that compares outputs
also carries both, for your IDE's diff view.

`compilerClosed`, `concurrentRuns`, `concurrentPrepares`, the three session checks and `evaluateAgreesWithDetail` show
a session or exception whose `toString()` or `getMessage()` throws as `<class> (message unavailable: <thrown class>)`.

The engine closes each session itself, so `sessionsClosed` doesn't count closes: it checks what only your language
decides. A language whose `newSession()` returns `Session.none()` passes it with nothing to check: the engine then
shares one copy, calls `newSession()` once and warms nothing up. A `null` from `newSession()` isn't watched, so the
engine rejects it as it would without the kit: at `load()` in `sessionsClosed`, and at the first run in
`conditionDetail`, `concurrentRuns`, `sessionClosedWhileAnotherRuns` and `sessionClosedOnAnotherThread`.

`sessionClosedWhileAnotherRuns` and `sessionClosedOnAnotherThread` pass a `Session.none()` language too, but still
compare output.

In `sessionClosedWhileAnotherRuns`, a listener starts a nested run. The check's run holds the only kept copy, so the
nested run gets an [extra copy](../compiled-copies.md#runs-that-dont-wait), closed as it ends, while the outer run
still has a rule to run. For any other language, the check fails if no session was closed during its run, if a
`close()` threw, or if either run failed or gave the wrong output.

`conditionDetail` compares each rule's detail with the sessions `newSession()` returned, by identity, so it can't
catch a detail that is `Session.none()`, which holds no state. It doesn't look inside the detail for a session held
there, but a detail whose text changes after another run or after `close()` fails: its `toString()` reads the
session's state. A language that gives no detail passes it with nothing to check.

`conditionAssignmentRejected` and `conditionWritesRejected` accept a rejection at either step, as
`outputNotReplaceable` does: `load()` may reject the condition, or `run()` may fail it, for example by writing to the
read-only `facts()`, or by evaluating to the assigned value, not a boolean. A condition that assigns and evaluates to
`true` or `false` without throwing fails the check.

These three checks and `missingPropertyFailsTheRun` run a valid rule `ok` first. Each failure must name rule `r`, and
a failed run must fail again with a `RuleExecutionException` and `CONDITION`, or `ACTION`: a failure mustn't break the
session.

The variable `conditionWritesRejected` declares, `z`, isn't a fact: don't declare it in `configure`.

`reservedFactNamesRejected` can miss a language that keeps whether it's prepared in a static field: once a check, or
anything else earlier in the JVM, has prepared a language of that class, the check's first answers are already the
prepared ones.

`concurrentPrepares` has the same blind spot: a runtime your language sets up in a static field or a static
initializer is checked only if nothing earlier in the JVM prepared a language of its class. And a race is likely, not
certain, to show: a window a few instructions wide may close before a second thread reaches it. Nor does it check
that `prepare()` is cheap once it has done its work.

`unreservedOutputReadAsFact` checks `output` only. A name your expressions bind to a context or helper object of your
own is yours to [reserve](custom.md#-fact-names), or reject in `checkFactName`: no check finds it.

The check first runs a rule that doesn't name `output`, with facts `x` and `output`. It passes there if your
`checkFactName` rejects `output`, so a language that can't compile a rule naming it needn't, and fails on a rejection
for another reason, such as `output` declared in `configure` with another type. Otherwise a second engine loads a rule
that reads `output`. If it fails to load, the failure says to reserve `output`, reject it in `checkFactName`, or
declare it as `Object` in `configure`.

`evaluateAgreesWithDetail` needs no engine: it compiles a condition with your compiler and `compileContext()`, and
evaluates it in a session of its own, ending each fact's run with `LanguageTestContexts.endRun` before the session
closes. The engine calls only `evaluateWithDetail`, so without this check an `evaluate`
that returned the wrong value would pass every other check, and a condition that wraps yours would still see it. A
language that doesn't override `evaluateWithDetail` passes: the default returns what `evaluate` does.

In the `evaluateAgreesWithDetail` row of [the checks table](contract-kit.md#-testing-with-the-contract-kit), "both
throw" means an exception or a non-fatal `Error` from each. A fatal one, any `VirtualMachineError`
but `StackOverflowError`, is thrown on, even as a cause or suppressed. Anything else a `close()` throws passes:
`compilerClosed` and the three session checks own that.

`evaluateAgreesWithDetail` tries four number types: an `evaluate` that compares by type and an `evaluateWithDetail`
that compares by value agree only for an `Integer`.

## 🧪 Testing a compiler without an engine

`LanguageTestContexts` creates the contexts the engine passes to a language, to test a compiler or compiled
expression without an engine. They're the engine's own contexts: writing to their facts fails as in a run, and
`evaluation(facts, deadline)` gives a real `isCancelled()` and `timeLeft()`. They read the system clock once, when
the context is made, then time `deadline` as a run does, so one built from `Instant.now()` passes when you expect.

For a language that reads [its own imports](custom.md#-implementing-the-interfaces), a `compile(...)` overload added
in 2.19.0 also takes them: the list your compiler gets from `languageImports()`. The other overloads give none.

Another, added in 2.23.0, takes your language first, then the same arguments. A language whose `name()` is `null` or
blank throws `IllegalArgumentException` with `An expression language's name must not be null or blank: <class>`.
It asks the language's `reservedFactNames()` once, as `build()` does, and rejects a fact declared with one of those
names with `build()`'s message, or throws `build()`'s `IllegalStateException` for a `null` set or name. The other
overloads reject `output`, the default, whatever your language reserves. Use the new one when your language reserves
another name, or none.

A `null` argument other than `deadline` throws `NullPointerException` with `<parameter> must not be null`, such as
`facts must not be null`. A `null` import, option name or option value throws `<parameter> must not contain null`,
such as `classImports must not contain null`. A `null` declared fact name or type throws `name must not be null` or
`type must not be null`. A fact's value may be `null`.

As a run does, the evaluation and action contexts reject a `null` or blank fact name with `IllegalArgumentException`
and the run's message, `fact name must not be null` or `fact name must not be blank`. They don't reject a reserved
name: they have no engine, so no languages to ask. Like an engine, `compile()` rejects a fact declared with a
blank or reserved name, a package import over the [size limits](mvel.md#-classes-and-imports), and a language import
over 1,000 characters.

As in a run, each evaluation or action context equals only itself, and its `hashCode()` never reads the facts or, for
an action context, the output object. A run passes the same evaluation context to every condition, and a new action
context to each action.

A [session](custom.md#-thread-safety) serves one run at a time and later runs reuse it, and no call marks where a
run starts or ends, so state a run leaves in a session is still there for the next run. State for one run belongs in
[`runScoped`](custom.md#-reading-facts), or `runScopedClosing` for a resource. A map keyed on contexts must not keep
them alive, as a `WeakHashMap` doesn't.

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

Each context from `evaluation(...)` or `action(...)` is a run of its own, so it shares no `runScoped` values with
another. Since 2.13.0, `actionInRun(sameRun, output)` creates an action context in the run of `sameRun`, a context
this class created: it has that context's facts and deadline and shares its `runScoped` values. So a test can check
that an action finds what a condition kept:

```java
EvaluationContext evaluation = LanguageTestContexts.evaluation(Map.of("x", 1));
ActionContext action = LanguageTestContexts.actionInRun(evaluation, new HashMap<String, Object>());

assertSame(evaluation.runScoped("key", Object::new), action.runScoped("key", Object::new));
```

No engine runs in these tests, so nothing closes a context's `runScopedClosing` values for you. Since 2.20.0,
`endRun(context)` closes them as a run's end would, newest first, but rethrows the first failure, so your test sees
a `close()` that throws. Each other failure is in its `getSuppressed()` once, unless it already carries the first or
the first carries it. If the last keep fails, a `VirtualMachineError` other than `StackOverflowError` replaces the
first; others are dropped.

Like a run's end, `endRun` first waits, with no limit, for a `runScopedClosing` init running on another thread. If the
wait runs out of stack or memory, it waits once more, closes what that hands over, and throws what the first wait
threw, with the rest suppressed on it. A second call does nothing, unless both waits failed: it then closes the values
left open. After the first, `runScopedClosing` throws `IllegalStateException` through any context of that run, as
after a real run.

When a `runScopedClosing` init calls `endRun` itself and returns a value, that `runScopedClosing` call throws the
same exception. The value isn't kept but closed, unless both of that `endRun`'s waits failed, and what its `close()`
throws is suppressed on the exception. A [fatal error](../glossary.md#fatal-error) from that `close()` is thrown
instead, carrying the exception.

```java
EvaluationContext evaluation = LanguageTestContexts.evaluation(Map.of("x", 1));
MyInterpreter interpreter = evaluation.runScopedClosing(MyInterpreter.class, MyInterpreter::new);

LanguageTestContexts.endRun(evaluation);
assertTrue(interpreter.isClosed());   // isClosed() stands for your runtime's own check
```

On the module path, the kit is the module `io.github.brantunger.unruly.test`; see [Packaging a language](packaging.md).

### A named module with Maven

When your main code has a `module-info.java`, Surefire 3.5.4 runs the tests on the module path: it patches the test
classes into your module and leaves the kit and JUnit on the class path. Core exports the package whose contexts
`LanguageTestContexts` creates only to the kit's module, so every `LanguageTestContexts` call, and the
`evaluateAgreesWithDetail` check, throws `IllegalAccessError`:

```text
... does not export io.github.brantunger.unruly.core to unnamed module ...
```

Export that package to the class path in Surefire's `argLine`. The module and the package have the same name, and
your module is still tested on the module path:

```xml
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.5.4</version>
    <configuration>
        <argLine>--add-exports io.github.brantunger.unruly.core/io.github.brantunger.unruly.core=ALL-UNNAMED</argLine>
    </configuration>
</plugin>
```

If your POM already sets an `argLine`, such as JaCoCo's `@{argLine}`, keep it and add the flag after it:
`<argLine>@{argLine} --add-exports ...</argLine>`. Or run the tests on the class path instead, with
`<useModulePath>false</useModulePath>` in the same `<configuration>`, but then your `module-info.java` isn't in force
during the tests.

Gradle isn't affected: it runs these tests on the class path. Surefire gives JUnit access to your test classes, so
this layout needs no `opens` clause; a test module of its own, one that `requires` the kit, does.
