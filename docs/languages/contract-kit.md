# 🧫 The contract test kit

How to add `unruly-engine-test` to a language's tests, what each of the contract kit's checks promises, and how to
test a compiler without an engine.

**Who it's for:** language authors.
**You'll be able to:** add the kit to a Gradle or Maven build, extend `ExpressionLanguageContractTest` for your
language, read what a failing check means, and test a compiled expression with `LanguageTestContexts`.
**Before you start:** [Writing an expression language](custom.md).

[← Documentation index](../README.md)

---

## 🧪 Testing with the contract kit

The `unruly-engine-test` artifact, at the same version as the engine, has two tools for a language's tests. Add it
with test scope, together with what a Gradle build needs to run the checks:

```groovy
dependencies {
    testImplementation 'io.github.brantunger:unruly-engine-test:<version>'

    testImplementation platform('org.junit:junit-bom:6.1.3')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

With Maven, import the [BOM](../glossary.md#artifacts), `unruly-engine-bom` (published from 2.6.0), at the
engine's version (here a property `unruly.version`), and declare the modules without versions. Every module then
gets the BOM's version, whatever order you declare them in:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.brantunger</groupId>
            <artifactId>unruly-engine-bom</artifactId>
            <version>${unruly.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.github.brantunger</groupId>
        <artifactId>unruly-engine-core</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.brantunger</groupId>
        <artifactId>unruly-engine-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>3.5.4</version>
        </plugin>
    </plugins>
</build>
```

> [!WARNING]
> Without the BOM, when a POM doesn't name `unruly-engine-core` itself, the first of `unruly-engine` and
> `unruly-engine-test` it declares sets core's version. An older kit declared first downgrades core, for the
> application too, so a newer engine runs on the kit's older core.

A `<version>` written on a dependency beats the BOM, so leave it off. The engine's own POMs don't import the BOM:
your POM must. A build that can't import it can instead declare `unruly-engine` before the kit, keep the versions
equal, or pin `unruly-engine-core` in `<dependencyManagement>`.

Gradle takes the highest version of core, whatever the order, so the BOM is optional there. To keep the modules at
one version, add `implementation platform('io.github.brantunger:unruly-engine-bom:<version>')` and declare them
without versions. Unlike Maven, Gradle raises a lower version written on one of them to the BOM's.

The kit is built with JUnit Jupiter 6, and brings `unruly-engine-core` and `junit-jupiter-api`. A Gradle build still
needs the rest: a JUnit test engine to run the checks, the JUnit Platform launcher to start it, and
`useJUnitPlatform()`, because a Gradle `Test` task runs JUnit 4 unless it is told otherwise, and without that setting
the checks never run. With Maven and Surefire 3.5.4, Surefire supplies the test engine, so the block above is enough,
unless your main code is a named module: then see [A named module with Maven](#a-named-module-with-maven).

`ExpressionLanguageContractTest` checks the promises [Writing an expression language](custom.md) describes for any
language. Extend it and supply expressions in your language, one method for each hook. Its eighteen checks:

| Check | Hooks | Skippable? | Passes when |
| --- | --- | --- | --- |
| `conditionReadsFacts` | `factEquals`, `putFact` | No | Fires for `x` = 1, not for 2 |
| `conditionReadsWholeNumbers` | `factEquals`, `putFact` | `comparesWholeNumbersByValue()` returns `false` | Fires for `x` = 1 given a `Long`, a `Short` or a `BigDecimal` fact, not for `2L` |
| `conditionMustBeBoolean` | `factValue`, `putFact` | No | `true` fires; `null`, `"true"` and `1` fail the rule |
| `conditionAssignmentRejected` | `assignment`, `putFact` | `assignment()` returns `null` | `load()` or `run()` throws an `UnrulyException` naming the rule and `CONDITION` |
| `outputNotReplaceable` | `alwaysTrue`, `reassignOutput` | `reassignOutput()` returns `null` | `load()` or `run()` throws an `UnrulyException` |
| `actionVariablesStayLocal` | `alwaysTrue`, `factEquals`, `declareVariable`, `putFact` | `declareVariable()` returns `null` | A later rule still sees the fact's value, and neither a later rule nor a later run sees the declared variable |
| `syntaxErrorAtLoad` | `syntaxError`, `putFact` | No | `load()` throws, naming the rule and `CONDITION` |
| `syntaxErrorInActionAtLoad` | `alwaysTrue`, `actionSyntaxError` | No | `load()` throws, naming the rule and `ACTION` |
| `unusableFactNameRejected` | `alwaysTrue`, `putFact`, `unusableFactName` | `unusableFactName()` returns `null` | `run()` throws `IllegalArgumentException`. The name mustn't be `output`, which the engine rejects before your language sees it |
| `usableFactNamesAccepted` | `usableFactNames`, `factEquals`, `putFact` | `usableFactNames()` returns an empty collection, the default | Each name works in a condition and an action |
| `conditionReadsProperties` | `factProperty`, `putFact` | No | `applicant.creditScore == 750` matches a record, a bean and a map |
| `missingPropertyFailsTheRun` | `missingFactProperty`, `putFact` | `missingFactProperty()` returns `null` | `creditScor` on a record fails `load()` or `run()` |
| `copiesAtLoad` | `factEquals`, `putFact` | No | With `copiesAtLoad(2)`, two runs on two threads each see their own facts, and `x` = 2 fires nothing |
| `compilerClosed` | `factEquals`, `putFact` | No | Each compiler is closed exactly once, after a reload and after `close()`, and its `close()` throws nothing |
| `sessionsClosed` | `factEquals`, `putFact` | No | With `copiesAtLoad(2)`, `newSession()` never returns one instance twice, unless it's `Session.none()`, and no session's `close()` throws anything |
| `conditionDetail` | `factEquals`, `putFact` | No | For a rule that matches and one that doesn't, the detail isn't a session `newSession()` returned, and its `toString()` gives the same text after another run and after `close()` |
| `evaluateAgreesWithDetail` | `factEquals` | No | For `x` = 1, 2, `1L`, `2L`, `(short) 1` and `BigDecimal.ONE`, a compiled condition's `evaluate` returns the value `evaluateWithDetail` reports, or both throw |
| `concurrentRuns` | `factEquals`, `putFact` | No | 8 threads, 200 runs each, all see their own facts |

- Only the five `@Nullable` hooks, `assignment`, `declareVariable`, `reassignOutput`, `unusableFactName` and
  `missingFactProperty`, may return `null`. Two hooks skip their check another way:
  `comparesWholeNumbersByValue()` by returning `false`, and `usableFactNames()` by returning an empty collection.
- Since 2.3.0, a language with no assignment syntax, such as CEL or JsonLogic, returns `null` from `assignment()`,
  rather than a syntax error standing in for one.
- `syntaxError()` and `actionSyntaxError()`, which defaults to `syntaxError()`, can't be skipped.
- `language()` is called for each check and for each engine a check builds, so return a new instance.

`usableFactNamesAccepted` runs each name `usableFactNames()` returns through `factEquals` and `putFact`. Return the
names your `checkFactName` might wrongly reject, such as `credit_score2`.

Each check but `evaluateAgreesWithDetail` builds an engine with `allMatches(HashMap::new).language(language())`,
with no imports, options or declared facts, so the language must work alone, and closes the engine when the check
ends, whether it passes or fails. `copiesAtLoad` and `sessionsClosed` add `copiesAtLoad(2)`, and `compilerClosed`,
`sessionsClosed` and `conditionDetail` wrap your language to watch its compiler or sessions. `factValue(x)` must not
coerce `"true"` or `1` to a boolean. Output numbers are compared by value, so `Long` or `Double` whole numbers pass.

The engine closes each session itself, so `sessionsClosed` doesn't count closes: it checks what only your language
decides. A language whose `newSession()` returns `Session.none()` passes it with nothing to check: the engine then
shares one copy, calls `newSession()` once and warms nothing up. A `null` from `newSession()` isn't watched, so the
engine rejects it as it would without the kit: at `load()` in `sessionsClosed`, at the first run in `conditionDetail`.

`conditionDetail` compares each rule's detail with the sessions `newSession()` returned, by identity, so it can't
catch a detail that is `Session.none()`, which holds no state. It doesn't look inside the detail for a session held
there, but a detail whose text changes after another run or after `close()` fails: its `toString()` reads the
session's state. A language that gives no detail passes it with nothing to check.

`conditionAssignmentRejected` accepts a rejection at either step, as `outputNotReplaceable` does: `load()` may reject
the condition when it compiles, or the condition may fail when it runs, for example by writing to the read-only
`facts()`, or by evaluating to the assigned value, which isn't a boolean. A condition that assigns and evaluates to
`true` or `false` without throwing fails the check.

`evaluateAgreesWithDetail` needs no engine: it compiles a condition with your compiler and evaluates it in a session
of its own. The engine calls only `evaluateWithDetail`, so without this check an `evaluate` that returned the wrong
value would pass every other check, and a condition that wraps yours would still see it. A language that doesn't
override `evaluateWithDetail` passes: the default returns what `evaluate` does.

`evaluateAgreesWithDetail` tries whole numbers of four types, because an `evaluate` that compares by type and an
`evaluateWithDetail` that compares by value agree for an `Integer` fact and disagree for a `Long`, `Short` or
`BigDecimal` one.

### Upgrading from 2.6

In 2.7.0 five checks got stricter and `usableFactNamesAccepted` was added, so a language that passed the 2.6 kit may
now fail. Each new failure is a real defect, not a kit change to work around:

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `actionVariablesStayLocal` | Keeps an action's variable in its session | A later rule, or a later run, reads a value that no rule set for it |
| `evaluateAgreesWithDetail` | Compares whole numbers differently in `evaluate` and `evaluateWithDetail`, such as by type in one and by value in the other | A wrapper that calls only `evaluate` matches a `Long`, `Short` or `BigDecimal` fact differently than the engine does |
| `conditionDetail` | Returns a detail that reads the session when it's printed | The run's result reports another run's values; `CompiledCondition` forbids a detail that holds the session |
| `unusableFactNameRejected` | Returns `output` from `unusableFactName()` | The check never reached your `checkFactName`; return a name only your language rejects |
| `compilerClosed` | Has a compiler whose `close()` throws | The engine only logs it at WARN, and the compiler has usually leaked what it holds |

`usableFactNamesAccepted` runs only when `usableFactNames()` returns names, so it can't fail a language that doesn't
override the hook. Skipped, JUnit counts it as aborted, so a launcher that expects every check found to succeed now
sees one more aborted check than with 2.6 (18 found and 17 succeeded, when no other check is skipped): override
`usableFactNames()`, or count aborted checks as passing.

Two things no check exercises, so passing the kit says nothing about them.

**Cancellation.** No check runs the rules with an interrupt, a deadline or a timeout. A runtime that clears the
thread's interrupt status when it cancels, as JEXL's `cancellable(true)` does, passes the kit and still hides the
caller's interrupt from the engine.

The hole is a narrow one. The engine checks before each condition and each action, again when each returns, and once
an action's properties are set, so an interrupt raised between rules always stops the run, and the deadline path is
unaffected. Only an interrupt raised and swallowed inside one expression escapes; see
[Stopping a run](custom.md#-stopping-a-run).

**The `CompileContext`.** Every check compiles with an empty `CompileContext`, through an engine or, in
`evaluateAgreesWithDetail`, through `LanguageTestContexts.compile()`, so a language that ignores imports, options,
declared facts and the output type passes. Test what your language does with each of them yourself;
[Implementing the interfaces](custom.md#-implementing-the-interfaces) says what the context carries.

`LanguageTestContexts` creates the contexts the engine passes to a language, to test a compiler or a compiled
expression without an engine. They're the engine's own contexts: writing to their facts fails as in a run, and
`evaluation(facts, deadline)` gives a real `isCancelled()`.

A `null` argument other than `deadline` throws `NullPointerException` with `<parameter> must not be null`, such as
`facts must not be null`. A `null` import, option name or option value throws `<parameter> must not contain null`,
such as `classImports must not contain null`. A `null` declared fact name or type throws `name must not be null` or
`type must not be null`. A fact's value may be `null`, and these contexts don't check fact names.

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

## 🚧 Gotchas

| Gotcha | What happens | Do this instead |
| --- | --- | --- |
| **A named main module, with Maven** | `LanguageTestContexts` and `evaluateAgreesWithDetail` throw `IllegalAccessError`, because the kit is on the class path | Add `--add-exports` to Surefire's `argLine`; see [A named module with Maven](#a-named-module-with-maven) |
| **An older kit declared first, with Maven** | Unless the POM imports the BOM or declares `unruly-engine-core` itself, Maven takes core's version from the kit, so a newer `unruly-engine` runs on the older core | Import `unruly-engine-bom` and drop the modules' versions; without a BOM, declare `unruly-engine` first, keep the versions equal, or pin `unruly-engine-core`. See [Testing with the contract kit](#-testing-with-the-contract-kit) |
