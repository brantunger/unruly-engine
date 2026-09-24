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
the checks never run. With Maven and Surefire 3.5.4, the block above is enough: Surefire supplies the test engine.

`ExpressionLanguageContractTest` checks the promises [Writing an expression language](custom.md) describes for any
language. Extend it and supply expressions in your language, one method for each hook. Its seventeen checks:

| Check | Hooks | Skippable? | Passes when |
| --- | --- | --- | --- |
| `conditionReadsFacts` | `factEquals`, `putFact` | No | Fires for `x` = 1, not for 2 |
| `conditionReadsWholeNumbers` | `factEquals`, `putFact` | `comparesWholeNumbersByValue()` returns `false` | Fires for `x` = 1 given a `Long`, a `Short` or a `BigDecimal` fact, not for `2L` |
| `conditionMustBeBoolean` | `factValue`, `putFact` | No | `true` fires; `null`, `"true"` and `1` fail the rule |
| `conditionAssignmentRejected` | `assignment`, `putFact` | `assignment()` returns `null` | `load()` or `run()` throws an `UnrulyException` naming the rule and `CONDITION` |
| `outputNotReplaceable` | `alwaysTrue`, `reassignOutput` | `reassignOutput()` returns `null` | `load()` or `run()` throws an `UnrulyException` |
| `actionVariablesStayLocal` | `alwaysTrue`, `declareVariable`, `putFact` | `declareVariable()` returns `null` | A later rule still sees the fact's value |
| `syntaxErrorAtLoad` | `syntaxError`, `putFact` | No | `load()` throws, naming the rule and `CONDITION` |
| `syntaxErrorInActionAtLoad` | `alwaysTrue`, `actionSyntaxError` | No | `load()` throws, naming the rule and `ACTION` |
| `unusableFactNameRejected` | `alwaysTrue`, `putFact`, `unusableFactName` | `unusableFactName()` returns `null` | `run()` throws `IllegalArgumentException` |
| `conditionReadsProperties` | `factProperty`, `putFact` | No | `applicant.creditScore == 750` matches a record, a bean and a map |
| `missingPropertyFailsTheRun` | `missingFactProperty`, `putFact` | `missingFactProperty()` returns `null` | `creditScor` on a record fails `load()` or `run()` |
| `copiesAtLoad` | `factEquals`, `putFact` | No | With `copiesAtLoad(2)`, two runs on two threads each see their own facts, and `x` = 2 fires nothing |
| `compilerClosed` | `factEquals`, `putFact` | No | Each compiler is closed exactly once, after a reload and after `close()` |
| `sessionsClosed` | `factEquals`, `putFact` | No | With `copiesAtLoad(2)`, `newSession()` never returns one instance twice, unless it's `Session.none()`, and no session's `close()` throws anything |
| `conditionDetail` | `factEquals`, `putFact` | No | For a rule that matches and one that doesn't, the detail isn't a session `newSession()` returned, and its `toString()` still works after `close()` |
| `evaluateAgreesWithDetail` | `factEquals` | No | For `x` = 1 and for 2, a compiled condition's `evaluate` returns the value `evaluateWithDetail` reports |
| `concurrentRuns` | `factEquals`, `putFact` | No | 8 threads, 200 runs each, all see their own facts |

- Only the five `@Nullable` hooks, `assignment`, `declareVariable`, `reassignOutput`, `unusableFactName` and
  `missingFactProperty`, may return `null`. `comparesWholeNumbersByValue()` is a boolean opt-out rather than one of
  them, and returning `false` skips its check.
- Since 2.3.0, a language with no assignment syntax, such as CEL or JsonLogic, returns `null` from `assignment()`,
  rather than a syntax error standing in for one.
- `syntaxError()` and `actionSyntaxError()`, which defaults to `syntaxError()`, can't be skipped.
- `language()` is called for each check and for each engine a check builds, so return a new instance.

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
there. A language that gives no detail passes it with nothing to check.

`conditionAssignmentRejected` accepts a rejection at either step, as `outputNotReplaceable` does: `load()` may reject
the condition when it compiles, or the condition may fail when it runs, for example by writing to the read-only
`facts()`, or by evaluating to the assigned value, which isn't a boolean. A condition that assigns and evaluates to
`true` or `false` without throwing fails the check.

`evaluateAgreesWithDetail` needs no engine: it compiles a condition with your compiler and evaluates it in a session
of its own. The engine calls only `evaluateWithDetail`, so without this check an `evaluate` that returned the wrong
value would pass every other check, and a condition that wraps yours would still see it. A language that doesn't
override `evaluateWithDetail` passes: the default returns what `evaluate` does.

Two things no check exercises, so passing the kit says nothing about them.

**Cancellation.** No check runs the rules with an interrupt, a deadline or a timeout. A runtime that clears the
thread's interrupt status when it cancels, as JEXL's `cancellable(true)` does, passes the kit and still hides the
caller's interrupt from the engine.

The hole is a narrow one. The engine checks before each condition and each action, and again when each returns, so an
interrupt raised between rules always stops the run, and the deadline path is unaffected. Only an interrupt raised and
swallowed inside one expression escapes; see [Stopping a run](custom.md#-stopping-a-run).

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

## 🚧 Gotchas

| Gotcha | What happens | Do this instead |
| --- | --- | --- |
| **An older kit declared first, with Maven** | Unless the POM imports the BOM or declares `unruly-engine-core` itself, Maven takes core's version from the kit, so a newer `unruly-engine` runs on the older core | Import `unruly-engine-bom` and drop the modules' versions; without a BOM, declare `unruly-engine` first, keep the versions equal, or pin `unruly-engine-core`. See [Testing with the contract kit](#-testing-with-the-contract-kit) |
