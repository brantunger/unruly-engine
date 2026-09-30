# 🧫 The contract test kit

How to add `unruly-engine-test` to a language's tests, and what each of the contract kit's checks promises.

**Who it's for:** language authors.
**You'll be able to:** add the kit to a Gradle or Maven build, extend `ExpressionLanguageContractTest` for your
language, and read what a failing check means.
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
> Without the BOM, when a POM doesn't name `unruly-engine-core` itself, the first dependency it declares that
> brings in core sets core's version: `unruly-engine`, `unruly-engine-test` or a third-party language. An older one
> declared first downgrades core, for the application too, so a newer engine runs on an older core. Import the BOM
> whenever anything in the build brings in `unruly-engine-core`, a third-party language included.

A `<version>` written on a dependency beats the BOM, so leave it off. The engine's own POMs don't import the BOM:
your POM must. A build that can't import it can instead declare `unruly-engine` before the kit and any third-party
language, keep the versions equal, or pin `unruly-engine-core` in `<dependencyManagement>`.

Gradle takes the highest version of core, whatever the order, so the BOM is optional there. To keep the modules at
one version, add `implementation platform('io.github.brantunger:unruly-engine-bom:<version>')` and declare them
without versions. Unlike Maven, Gradle raises a lower version written on one of them to the BOM's.

The kit is built with JUnit Jupiter 6, and brings `unruly-engine-core` and `junit-jupiter-api`. A Gradle build still
needs the rest: a JUnit test engine to run the checks, the JUnit Platform launcher to start it, and
`useJUnitPlatform()`, because a Gradle `Test` task runs JUnit 4 unless it is told otherwise, and without that setting
the checks never run. With Maven and Surefire 3.5.4, Surefire supplies the test engine, so the block above is enough,
unless your main code is a named module: then see
[A named module with Maven](beyond-the-contract-kit.md#a-named-module-with-maven).

`ExpressionLanguageContractTest` checks the promises [Writing an expression language](custom.md) describes for any
language. Extend it and supply expressions in your language, one method for each hook. Its twenty-six checks:

| Check | Hooks | Skippable? | Passes when |
| --- | --- | --- | --- |
| `conditionReadsFacts` | `factEquals`, `putFact` | No | Fires for `x` = 1, not for 2 |
| `conditionReadsWholeNumbers` | `factEquals`, `putFact` | `comparesWholeNumbersByValue()` returns `false` | Fires for `x` = 1 given a `Long`, a `Short` or a `BigDecimal` fact, not for `2L` |
| `conditionMustBeBoolean` | `factValue`, `putFact` | No | `true` fires; `null`, `"true"` and `1` fail the rule |
| `conditionAssignmentRejected` | `assignment`, `putFact` | `assignment()` returns `null` | `load()` or two `run()`s throw, naming the rule and `CONDITION` |
| `conditionWritesRejected` | `propertyAssignment`, `conditionDeclaration`, `putFact` | Both return `null`; each part is skipped by its own `null` | A condition that sets a property of `applicant`, a map and a `WritableApplicant`, and one that declares `z`, each make `load()` or two `run()`s throw, naming the rule and `CONDITION`, and `applicant` is unchanged |
| `outputNotReplaceable` | `alwaysTrue`, `reassignOutput` | `reassignOutput()` returns `null` | `load()` or two `run()`s throw, the second naming the rule and `ACTION` |
| `actionVariablesStayLocal` | `alwaysTrue`, `factEquals`, `declareVariable`, `putFact`, `putVariable`, `variableEquals` | `declareVariable()` returns `null` | A later rule still sees the fact's value, the run that declares `y` while `y` is a fact doesn't throw, and no later rule, nor a later run's condition or action, sees the declared variable |
| `failedActionVariablesStayLocal` | `alwaysTrue`, `factEquals`, `declareVariableThenFail`, `putVariable` | `declareVariableThenFail()` returns `null` | `load()` refuses the name the later rule reads, or the run whose action declares a variable and then fails throws, and a later run doesn't see the variable |
| `syntaxErrorAtLoad` | `syntaxError`, `putFact` | No | `load()` throws, naming the rule and `CONDITION` |
| `syntaxErrorInActionAtLoad` | `alwaysTrue`, `actionSyntaxError` | No | `load()` throws, naming the rule and `ACTION` |
| `unusableFactNameRejected` | `alwaysTrue`, `putFact`, `unusableFactName` | `unusableFactName()` returns `null` | `run()` throws `IllegalArgumentException`. The name mustn't be blank or `output`, which the engine rejects before your language sees it, or `x`, which the check's rule reads |
| `usableFactNamesAccepted` | `usableFactNames`, `factEquals`, `putFact` | `usableFactNames()` returns an empty collection, the default | Each name works in a condition and an action |
| `conditionReadsProperties` | `factProperty`, `putFact` | No | `applicant.creditScore == 750` matches a record, a bean and a map |
| `missingPropertyFailsTheRun` | `missingFactProperty`, `putFact` | `missingFactProperty()` returns `null` | `creditScor` on a record fails `load()` or two `run()`s, the second naming the rule and `CONDITION` |
| `copiesAtLoad` | `factEquals`, `putFact` | No | With `copiesAtLoad(2)`, two runs on two threads each see their own facts, and `x` = 2 fires nothing |
| `compilerClosed` | `factEquals`, `putFact` | No | With `copiesAtLoad(1)`, each compiler is closed exactly once, after a reload and after `close()`, and its `close()` throws nothing |
| `sessionsClosed` | `factEquals`, `putFact` | No | With `copiesAtLoad(2)`, `newSession()` never returns one instance twice, unless it's `Session.none()`, and no session's `close()` throws anything |
| `sessionClosedWhileAnotherRuns` | `factEquals`, `putFact` | No | A nested run's extra copy is closed during the outer run, both give the right output, and no session's `close()` throws anything |
| `sessionClosedOnAnotherThread` | `factEquals`, `putFact` | No | A session a worker thread's run made closes without throwing when the test thread closes the engine |
| `conditionDetail` | `factEquals`, `putFact` | No | For a rule that matches and one that doesn't, the detail isn't a session `newSession()` returned, and its `toString()` gives the same text after another run and after `close()` |
| `evaluateAgreesWithDetail` | `factEquals` | No | For `x` = 1, 2, `1L`, `2L`, `(short) 1` and `BigDecimal.ONE`, a compiled condition's `evaluate` returns the value `evaluateWithDetail` reports, or both throw |
| `concurrentRuns` | `factEquals`, `putFact`, `copyThroughVariable` | No; a `null` from `copyThroughVariable()` leaves the action variables out | 8 threads, 200 runs each, all see their own facts and action variables, and `newSession()` never returns one session twice, unless it's `Session.none()` |
| `nestedRunInsideAnAction` | `factEquals`, `putFact`, `putFactProperty` | `putFactProperty()` returns `null` | A run that a getter starts inside an action, on the same thread, and the outer run each give their own output |
| `nestedRunInsideACondition` | `factProperty`, `factEquals`, `bothConditions`, `putFact` | `bothConditions()` returns `null` | A run that a getter starts inside a condition, on the same thread, and the outer run each give their own output |
| `nestedRunFailsInsideACondition` | `factProperty`, `factEquals`, `bothConditions`, `putFact` | `bothConditions()` returns `null`, or its nested run neither fails nor reads `nest.value` | The nested run fails with a `RuleExecutionException` carrying what its `nest.value` threw, and the outer run still fires its rule |
| `nestedRunFailsInsideAnAction` | `factEquals`, `putFact`, `putFactProperty` | `putFactProperty()` returns `null`, or its nested run neither fails nor reads `nest.value` | The nested run fails with a `RuleExecutionException` carrying what its `nest.value` threw, and the outer run still gets the action's value |

Only the eleven `@Nullable` hooks may return `null`: `assignment`, `declareVariable`, `reassignOutput`,
`unusableFactName`, `missingFactProperty`, `copyThroughVariable`, `putFactProperty`, `propertyAssignment`,
`conditionDeclaration`, `declareVariableThenFail` and `bothConditions`.

- `comparesWholeNumbersByValue()` skips its check by returning `false`, and `usableFactNames()` by returning an empty
  collection.
- A language with no assignment syntax, such as CEL or JsonLogic, returns `null` from `assignment()`, not a syntax
  error.
- `syntaxError()` and `actionSyntaxError()`, which defaults to `syntaxError()`, can't be skipped.
- `language()` is called for each check and for each engine a check builds, so return a new instance.

`putVariable(key, variable)` and `variableEquals(variable, value)` default to `putFact` and `factEquals`. Override
them if your variables have a namespace of their own, such as SpEL's `#y`: otherwise the variable checks read a fact
named `y`, which isn't there, and pass whatever your language does with its variables.

`bothConditions(condition, other)` must evaluate `condition` first, as MVEL's `condition + " && " + other` does: the
condition checks read `nest.value`, which starts the nested run, then `x`, which finds any state that run left on the
thread. A language that evaluates the right side first isn't checked. The nested-run checks run on a thread of their
own, so per-thread state left there can't reach later checks.

`usableFactNamesAccepted` runs each name `usableFactNames()` returns through `factEquals` and `putFact`. Return the
names your `checkFactName` might wrongly reject, such as `credit_score2`.

Each check but `evaluateAgreesWithDetail` builds an engine with `allMatches(HashMap::new).language(language())` and
[`configure(builder)`](#a-language-that-needs-declared-facts-imports-or-options), which adds nothing by default, and
closes it however the check ends. `copiesAtLoad` and `sessionsClosed` then add `copiesAtLoad(2)`, and
`compilerClosed` adds `copiesAtLoad(1)`.

`sessionClosedOnAnotherThread` adds `copiesAtLoad(0)`. `sessionClosedWhileAnotherRuns` adds `copiesAtLoad(0)` and
`maxCopies(1)`, so a run nested in another gets an extra copy. `conditionDetail`, `failedActionVariablesStayLocal`,
the later-run parts of `actionVariablesStayLocal`, `conditionAssignmentRejected`, `conditionWritesRejected`,
`outputNotReplaceable` and `missingPropertyFailsTheRun` add the same two, so each later run gets the copy, and the
sessions, the run before it used.

`compilerClosed`, `conditionDetail`, `concurrentRuns` and the three session checks, `sessionsClosed`,
`sessionClosedWhileAnotherRuns` and `sessionClosedOnAnotherThread`, wrap your language to watch its compiler or
sessions. The wrappers forward `warmUp`, so copies made at load warm up as they do without the kit.

`factValue(x)` must not coerce `"true"` or `1` to a boolean. Output numbers are compared by value, so `Long` or
`Double` whole numbers pass. When an output differs only in a value's type, such as the `Integer` 1 and the `String`
"1", which print the same, the failure says so. Except in `usableFactNamesAccepted`, a failure that compares outputs
also carries both, for your IDE's diff view.

`compilerClosed`, `concurrentRuns`, the three session checks and `evaluateAgreesWithDetail` show a session or exception
whose `toString()` or `getMessage()` throws as `<class> (message unavailable: <thrown class>)`.

The engine closes each session itself, so `sessionsClosed` doesn't count closes: it checks what only your language
decides. A language whose `newSession()` returns `Session.none()` passes it with nothing to check: the engine then
shares one copy, calls `newSession()` once and warms nothing up. A `null` from `newSession()` isn't watched, so the
engine rejects it as it would without the kit: at `load()` in `sessionsClosed`, and at the first run in
`conditionDetail`, `concurrentRuns`, `sessionClosedWhileAnotherRuns` and `sessionClosedOnAnotherThread`.

`sessionClosedWhileAnotherRuns` and `sessionClosedOnAnotherThread` pass a `Session.none()` language too: it has no
session to close, though both checks still compare output.

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
`true` or `false` without throwing fails the check. A failed run must fail again, with a `RuleExecutionException`
naming the rule and `CONDITION`, or `ACTION`.

`conditionWritesRejected` also fails if the property write changed `applicant`, even when the condition was rejected.
The variable it declares, `z`, isn't a fact: don't declare it in `configure`.

`evaluateAgreesWithDetail` needs no engine: it compiles a condition with your compiler and `compileContext()`, and
evaluates it in a session of its own. The engine calls only `evaluateWithDetail`, so without this check an `evaluate`
that returned the wrong value would pass every other check, and a condition that wraps yours would still see it. A
language that doesn't override `evaluateWithDetail` passes: the default returns what `evaluate` does.

In the table, "both throw" means an exception or a non-fatal `Error`, such as `StackOverflowError`, from each. A
fatal one, a `VirtualMachineError` other than `StackOverflowError`, is thrown on unchanged. Anything else a
`close()` throws passes: `compilerClosed` and the three session checks own that.

`evaluateAgreesWithDetail` tries four number types: an `evaluate` that compares by type and an `evaluateWithDetail`
that compares by value agree only for an `Integer`.

### A language that needs declared facts, imports or options

Override `configure` to add them to the checks' engines, and `compileContext()` to give `evaluateAgreesWithDetail`
the same. The checks' expressions read `x`, `y`, `applicant`, `nest` and the names `usableFactNames()` returns. `x` is
also a `Boolean`, a `String` and `null`, and `applicant` a record, two beans and a map, so declare both as `Object`: a
fact that isn't its declared type fails the run before your language evaluates anything. Declare `nest` as `Object`
too, or as `ExpressionLanguageContractTest.Nesting` if your language resolves properties from the declared type.

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

`configure` must not call `requireDeclaredFacts()`, since each run supplies only its check's facts, or set
`runTimeout(...)`, `maxCopies(...)` below 2, another language, `defaultLanguage(...)` or `outputWriter(...)`: the
checks could fail for reasons unrelated to your language. Nor may it declare the `unusableFactName()` name: `load()`
checks declared names with your language, which rejects it. A `copiesAtLoad` or `maxCopies` it sets doesn't change
the checks that set their own. A `maxCopies(2)` keeps `concurrentRuns` to two sessions, so it can't catch a repeat
after the second.

### Upgrading the kit

A newer kit can fail a language that passed an older one. [Upgrading the contract test kit](contract-kit-upgrading.md)
lists, for each version, the checks that were added or got stricter and the defect each new failure means.

### Beyond the kit

[Testing beyond the contract kit](beyond-the-contract-kit.md) covers what no check exercises, testing a compiler
without an engine with `LanguageTestContexts`, and the setting a named module needs with Maven.

## 🚧 Gotchas

| Gotcha | What happens | Do this instead |
| --- | --- | --- |
| **A named main module, with Maven** | `LanguageTestContexts` and `evaluateAgreesWithDetail` throw `IllegalAccessError`, because the kit is on the class path | Add `--add-exports` to Surefire's `argLine`; see [A named module with Maven](beyond-the-contract-kit.md#a-named-module-with-maven) |
| **An older kit or third-party language declared first, with Maven** | Unless the POM imports the BOM or declares `unruly-engine-core` itself, Maven takes core's version from the first of them, so a newer `unruly-engine` runs on the older core | Import `unruly-engine-bom` whenever anything brings in `unruly-engine-core`, and drop the modules' versions; without a BOM, declare `unruly-engine` first, keep the versions equal, or pin `unruly-engine-core`. See [Testing with the contract kit](#-testing-with-the-contract-kit) |
