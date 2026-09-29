# 🔬 Testing beyond the contract kit

What the contract kit's checks leave untested, and how to test a compiler or a compiled expression without an engine.

**Who it's for:** language authors.
**You'll be able to:** tell what passing the kit doesn't prove, test a compiled expression with
`LanguageTestContexts`, and run those tests from a named module with Maven.
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

## 🧪 Testing a compiler without an engine

`LanguageTestContexts` creates the contexts the engine passes to a language, to test a compiler or compiled
expression without an engine. They're the engine's own contexts: writing to their facts fails as in a run, and
`evaluation(facts, deadline)` gives a real `isCancelled()` and `timeLeft()`. They read the system clock once, when
the context is made, then time `deadline` as a run does, so one built from `Instant.now()` passes when you expect.

A `null` argument other than `deadline` throws `NullPointerException` with `<parameter> must not be null`, such as
`facts must not be null`. A `null` import, option name or option value throws `<parameter> must not contain null`,
such as `classImports must not contain null`. A `null` declared fact name or type throws `name must not be null` or
`type must not be null`. A fact's value may be `null`.

The evaluation and action contexts don't check fact names. Like an engine, `compile()` rejects a fact declared with a
blank name or as `output`, and a package import over the [size limits](mvel.md#-classes-and-imports).

As in a run, each evaluation or action context equals only itself, and its `hashCode()` never reads the facts or, for
an action context, the output object. A run passes the same evaluation context to every condition, and a new action
context to each action.

A [session](custom.md#-thread-safety) serves one run at a time and later runs reuse it, and no call marks where a
run starts or ends, so state a run leaves in a session is still there for the next run. State for one run belongs in
[`runScoped`](custom.md#-reading-facts). A map keyed on contexts must not keep them alive, as a `WeakHashMap` doesn't.

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

On the module path, the kit is the module `io.github.brantunger.unruly.test`; see [Packaging](custom.md#-packaging).

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
