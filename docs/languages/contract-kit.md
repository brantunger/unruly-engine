# 🧫 The contract test kit

How to add `unruly-engine-test` to a language's tests, and what each of the kit's checks promises.

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
`useJUnitPlatform()`, because without it a Gradle `Test` task runs JUnit 4, and the checks never run. With Maven
and Surefire 3.5.4, Surefire supplies the test engine, so the block above is enough, unless your main code is a named
module: then see [A named module with Maven](beyond-the-contract-kit.md#a-named-module-with-maven).

`ExpressionLanguageContractTest` checks the promises [Writing an expression language](custom.md) describes for any
language. Extend it and supply expressions in your language, one method for each hook.

The checks supply facts named `x`, `y`, `applicant` and `nest`, so your language must not reserve them. If it does,
every check that builds an engine fails before building it, with `reservedFactNames() reserves [<names>], which the
contract kit's checks supply as facts: the kit can't check a language that reserves x, y, applicant or nest`, where
`<names>` are those of the four it reserves, sorted. The kit's thirty checks:

| Check | Hooks | Skippable? | Passes when |
| --- | --- | --- | --- |
| `conditionReadsFacts` | `factEquals`, `putFact` | No | Fires for `x` = 1, not for 2 |
| `conditionReadsWholeNumbers` | `factEquals`, `putFact` | `comparesWholeNumbersByValue()` returns `false` | Fires for `x` = 1 given a `Long`, a `Short` or a `BigDecimal` fact, not for `2L` |
| `conditionMustBeBoolean` | `factValue`, `putFact` | No | `true` fires; `null`, `"true"` and `1` fail the rule |
| `conditionAssignmentRejected` | `alwaysTrue`, `assignment`, `putFact` | `assignment()` returns `null` | `load()` or two `run()`s throw, naming the rule and `CONDITION` |
| `conditionWritesRejected` | `alwaysTrue`, `propertyAssignment`, `conditionDeclaration`, `putFact` | Both return `null`; each part is skipped by its own `null` | A condition that sets a property of `applicant`, a map and a `WritableApplicant`, and one that declares `z`, each make `load()` or two `run()`s throw, naming the rule and `CONDITION`, and `applicant` is unchanged |
| `outputNotReplaceable` | `alwaysTrue`, `putFact`, `reassignOutput` | `reassignOutput()` returns `null` | `load()` or two `run()`s throw, naming the rule, the second `ACTION` |
| `actionVariablesStayLocal` | `alwaysTrue`, `factEquals`, `declareVariable`, `putFact`, `putVariable`, `variableEquals`, `actionVariablesLastTheRun` | `declareVariable()` returns `null` | A later rule still sees the fact's value, the run that declares `y` while `y` is a fact doesn't throw, no later run's condition or action sees the declared variable, and a later rule in the same run doesn't, or must if `actionVariablesLastTheRun()` returns `true` |
| `sharedStateStaysLocal` | `factEquals`, `changeSharedState`, `sharedStateEquals`, `putFact` | `changeSharedState()` returns `null` | `load()` refuses the changing action, or `sharedStateEquals` is false before the change and in a later run, even when the action fails |
| `failedActionVariablesStayLocal` | `alwaysTrue`, `factEquals`, `declareVariableThenFail`, `putVariable` | `declareVariableThenFail()` returns `null` | `load()` refuses the name the later rule reads, or the run whose action declares a variable and then fails throws, and a later run doesn't see the variable |
| `syntaxErrorAtLoad` | `syntaxError`, `putFact` | No | `load()` throws, naming the rule and `CONDITION` |
| `syntaxErrorInActionAtLoad` | `alwaysTrue`, `actionSyntaxError` | No | `load()` throws, naming the rule and `ACTION` |
| `unusableFactNameRejected` | `alwaysTrue`, `putFact`, `unusableFactName` | `unusableFactName()` returns `null` | `run()` throws `IllegalArgumentException` from your `checkFactName`; a rejection for another reason, such as the name declared in `configure` with another type, fails. The name mustn't be blank or one your language reserves, which the engine rejects before your language sees it, or `x`, which the check's rule reads |
| `reservedFactNamesRejected` | `alwaysTrue`, `putFact` | No; an empty set passes with nothing to run | `reservedFactNames()` answers before `prepare()`, isn't `null`, holds no `null`, gives the same set a second time and again once its engine has prepared the language, and `run()` rejects a fact with each name, unless blank, with an `IllegalArgumentException` saying the name is reserved |
| `unreservedOutputReadAsFact` | `alwaysTrue`, `factEquals`, `putFact` | `reservedFactNames()` returns `output` | A run given a fact `output` throws from your `checkFactName`, or a rule reading `output` loads and puts the fact's value, not the output object |
| `usableFactNamesAccepted` | `usableFactNames`, `factEquals`, `putFact` | `usableFactNames()` returns an empty collection, the default | Each name works in a condition and an action |
| `conditionReadsProperties` | `factProperty`, `putFact` | No | `applicant.creditScore == 750` matches a record, a bean and a map |
| `missingPropertyFailsTheRun` | `alwaysTrue`, `missingFactProperty`, `putFact` | `missingFactProperty()` returns `null` | `creditScor` on a record fails `load()` or two `run()`s, naming the rule, the second `CONDITION` |
| `copiesAtLoad` | `factEquals`, `putFact` | No | With `copiesAtLoad(2)`, two runs on two threads each see their own facts, and `x` = 2 fires nothing |
| `compilerClosed` | `factEquals`, `putFact` | No | With `copiesAtLoad(1)`, each compiler is closed exactly once, after a reload and after `close()`, and its `close()` throws nothing |
| `sessionsClosed` | `factEquals`, `putFact` | No | With `copiesAtLoad(2)`, `newSession()` never returns one instance twice, unless it's `Session.none()`, and no session's `close()` throws anything |
| `sessionClosedWhileAnotherRuns` | `factEquals`, `putFact` | No | A nested run's extra copy is closed during the outer run, both give the right output, and no session's `close()` throws anything |
| `sessionClosedOnAnotherThread` | `factEquals`, `putFact` | No | A session a worker thread's run made closes without throwing when the test thread closes the engine |
| `conditionDetail` | `factEquals`, `putFact` | No | For a rule that matches and one that doesn't, the detail isn't a session `newSession()` returned, and its `toString()` gives the same text after another run and after `close()` |
| `evaluateAgreesWithDetail` | `factEquals` | No | For `x` = 1, 2, `1L`, `2L`, `(short) 1` and `BigDecimal.ONE`, a compiled condition's `evaluate` returns the value `evaluateWithDetail` reports, or both throw |
| `concurrentRuns` | `factEquals`, `putFact`, `copyThroughVariable` | No; a `null` from `copyThroughVariable()` leaves the action variables out | 8 threads, 200 runs each, all see their own facts and action variables, and `newSession()` never returns one session twice, unless it's `Session.none()` |
| `concurrentPrepares` | `factEquals`, `putFact` | No | 8 threads, released together, each call `prepare()` on one shared instance, then build an engine with it and load and run one rule, all putting `x`'s value within 30 seconds |
| `nestedRunInsideAnAction` | `factEquals`, `putFact`, `putFactProperty` | `putFactProperty()` returns `null` | A run that a getter starts inside an action, on the same thread, and the outer run each give their own output |
| `nestedRunInsideACondition` | `factProperty`, `factEquals`, `bothConditions`, `putFact` | `bothConditions()` returns `null` | A run that a getter starts inside a condition, on the same thread, and the outer run each give their own output |
| `nestedRunFailsInsideACondition` | `factProperty`, `factEquals`, `bothConditions`, `putFact` | `bothConditions()` returns `null`, or its nested run neither fails nor reads `nest.value` | The nested run fails with a `RuleExecutionException` carrying what its `nest.value` threw, and the outer run still fires its rule |
| `nestedRunFailsInsideAnAction` | `factEquals`, `putFact`, `putFactProperty` | `putFactProperty()` returns `null`, or its nested run neither fails nor reads `nest.value` | The nested run fails with a `RuleExecutionException` carrying what its `nest.value` threw, and the outer run still gets the action's value |

Only the thirteen `@Nullable` hooks may return `null`: `assignment`, `declareVariable`, `reassignOutput`,
`unusableFactName`, `missingFactProperty`, `copyThroughVariable`, `putFactProperty`, `propertyAssignment`,
`conditionDeclaration`, `declareVariableThenFail`, `bothConditions`, `changeSharedState` and `sharedStateEquals`.

- `comparesWholeNumbersByValue()` skips its check by returning `false`, and `usableFactNames()` by returning an empty
  collection.
- `sharedStateEquals()` must be false, not throw, until an action changes the state, and not `null` once
  `changeSharedState()` isn't.
- A language with no assignment syntax, such as CEL or JsonLogic, returns `null` from `assignment()`, not a syntax
  error.
- `syntaxError()` and `actionSyntaxError()`, which defaults to `syntaxError()`, can't be skipped.
- `language()` is called for each check and, except in `unreservedOutputReadAsFact` and `concurrentPrepares`, for
  each engine a check builds, so return a new instance.

`putVariable(key, variable)` and `variableEquals(variable, value)` default to `putFact` and `factEquals`. Override
them if your variables have a namespace of their own, such as SpEL's `#y`: otherwise the variable checks read a fact
named `y`, which isn't there, and pass whatever your language does with its variables.

`bothConditions(condition, other)` must evaluate `condition` first, as MVEL's `condition + " && " + other` does: the
condition checks read `nest.value`, which starts the nested run, then `x`, which finds any state that run left on the
thread. A language that evaluates the right side first isn't checked. The nested-run checks run on a thread of their
own, so per-thread state left there can't reach later checks.

For `usableFactNamesAccepted`, return the names your `checkFactName` might wrongly reject, such as `credit_score2`.

How each check builds its engines, what it wraps, and its limits: see
[How each check runs](beyond-the-contract-kit.md#-how-each-check-runs).

### Upgrading the kit

[Upgrading the contract test kit](contract-kit-upgrading.md) lists, for each version, the checks added or made
stricter, and what each new failure means.

### Beyond the kit

[Testing beyond the contract kit](beyond-the-contract-kit.md) covers what no check exercises, how each check runs, a
language that needs declared facts, imports or options, `LanguageTestContexts`, and a named module with Maven.

## 🚧 Gotchas

| Gotcha | What happens | Do this instead |
| --- | --- | --- |
| **A named main module, with Maven** | `LanguageTestContexts` and `evaluateAgreesWithDetail` throw `IllegalAccessError`, because the kit is on the class path | Add `--add-exports` to Surefire's `argLine`; see [A named module with Maven](beyond-the-contract-kit.md#a-named-module-with-maven) |
| **An older kit or third-party language declared first, with Maven** | Maven may take core's version from it, so a newer `unruly-engine` runs on the older core | Import `unruly-engine-bom`; see [Testing with the contract kit](#-testing-with-the-contract-kit) |
