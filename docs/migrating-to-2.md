# ⬆️ Migrating from 1.x to 2.0

2.0 is a breaking release. This guide lists every change that can affect code written for 1.x. Each section says
what changed, who is affected, and what to change.

## 🤔 Should you upgrade?

- **2.0 requires Java 21.** On Java 17 to 20, stay on 1.x.
- **Upgrade to 1.8.0 first**, the last 1.x release, and fix its deprecation warnings. Each deprecated member names
  its replacement, so most of the work can be done while you're still on 1.x.

## ☕ Java 21 is required

**What changed:** the library is compiled for Java 21 (class file version 65). 1.x was compiled for Java 17.

**Who is affected:** projects built with, or running on, Java 17, 18, 19 or 20.

- A JDK 17 compiler can't read the engine's classes:
  ```
  error: cannot access RulesEngineBuilder
    bad class file: .../io/github/brantunger/unruly/api/RulesEngineBuilder.class
      class file has wrong version 65.0, should be 61.0
  ```
- An application built with a newer JDK but run on Java 17 fails when it first uses the engine:
  ```
  java.lang.UnsupportedClassVersionError: io/github/brantunger/unruly/api/RulesEngineBuilder has been compiled by a
  more recent version of the Java Runtime (class file version 65.0), this version of the Java Runtime only recognizes
  class file versions up to 61.0
  ```

**What to change:** run your application on Java 21 or later, and compile for it.

- Gradle:
  ```groovy
  java {
      toolchain {
          languageVersion = JavaLanguageVersion.of(21)
      }
  }
  ```
- Maven:
  ```xml
  <properties>
      <maven.compiler.release>21</maven.compiler.release>
  </properties>
  ```

Spring Boot 3 and 4 both run on Java 21. The engine is tested on Java 21 and 25.

## 🔎 Expression languages are found with ServiceLoader

**What changed:** the engine no longer creates MVEL itself. An engine built without `language(...)` finds expression
languages with `java.util.ServiceLoader` when it's built, from
`META-INF/services/io.github.brantunger.unruly.api.language.ExpressionLanguage` files, and MVEL is one of them. A rule
without a `language` is written in the engine's default language, which is MVEL when MVEL is the only language found.

**Who is affected:**

- **Class paths with another language listed in such a file.** That language can now be used by rules without being
  given to the engine. With MVEL and another language found, `build()` fails until `defaultLanguage(...)` names the
  language of rules without one. Two found languages with the same name fail `build()`.
- **Applications repackaged into one jar** (a shaded or "uber" jar) that keep only one of several `META-INF/services`
  files with the same name. If MVEL's entry is lost and no other language is found, building an engine fails:
  `The engine has no expression language: add one with language(), or put a language's jar on the class path`.
- **Class paths without `mvel2`.** Building an engine now succeeds, and loading MVEL rules fails instead.

**What to change:** usually nothing. When you repackage the library, merge service files, for example with the Maven
Shade plugin's `ServicesResourceTransformer`, or give the engine MVEL yourself with
`.language(new MvelExpressionLanguage())` on its builder.

## 📦 MVEL is a separate jar

**What changed:** the library is published as two artifacts. `unruly-engine-core` is the engine and its API, and
`unruly-engine` is the MVEL language, which depends on `unruly-engine-core`. Package and class names didn't change.

**Who is affected:** builds that list jars by hand instead of resolving dependencies, or that exclude the library's
transitive dependencies. With only `unruly-engine-<version>.jar`, the API classes are missing.

**What to change:** usually nothing. A Maven or Gradle dependency on `unruly-engine` brings `unruly-engine-core` with
it. An application whose rules all name other languages can depend on `unruly-engine-core` alone; see
[Packaging a language](languages/custom.md#-packaging-a-language).

## 🧭 The jars are named modules

**What changed:** each jar has a module declaration. `unruly-engine` is still the module `io.github.brantunger.unruly`,
and `unruly-engine-core` is the module `io.github.brantunger.unruly.core`. They export only the API packages, not
`io.github.brantunger.unruly.core`, which only the test kit can read, and they require SLF4J and MVEL themselves. The Javadoc site has a directory for
each module.

**Who is affected:**

- **Gradle applications on the module path.** Gradle puts MVEL's jar, which has no module name, on the class path, so
  the application fails to start:
  ```
  java.lang.module.FindException: Module mvel2 not found, required by io.github.brantunger.unruly
  ```
- **Module-path applications that required SLF4J only for the engine.** `requires org.slf4j` and
  `--add-modules org.slf4j` are no longer needed, and are harmless.
- **Links to Javadoc pages.** For example, `latest/io/github/brantunger/unruly/api/RulesEngine.html` is now
  `latest/io.github.brantunger.unruly.core/io/github/brantunger/unruly/api/RulesEngine.html`.

**What to change:**

- Gradle: name MVEL's module with the extra-java-module-info plugin, as the
  [README's installation section](../README.md#-installation) shows.
- Remove `requires org.slf4j` unless your application uses SLF4J itself.
- Update links to Javadoc pages.

## 🧪 A language's contexts are sealed, and its tests use the test kit

**What changed:** `CompileContext`, `EvaluationContext` and `ActionContext` in `io.github.brantunger.unruly.api.language`
are sealed, so only the engine implements them. The new `unruly-engine-test` artifact has the contract test for
languages, `io.github.brantunger.unruly.test.ExpressionLanguageContractTest`, and `LanguageTestContexts`, which
creates the contexts for unit tests.

**Who is affected:** authors of an expression language whose tests implement a context, or who copied
`ExpressionLanguageContractTest` from the repository. A class that implements a context no longer compiles:

```
error: class is not allowed to extend sealed class: EvaluationContext (as it is not listed in its 'permits' clause)
```

**What to change:**

- Add `io.github.brantunger:unruly-engine-test` to your test dependencies, at the engine's version.
- Replace a test's own contexts with `LanguageTestContexts.compile()`, `evaluation(facts)` and
  `action(facts, output)`.
- Delete a copied contract test, and extend `io.github.brantunger.unruly.test.ExpressionLanguageContractTest`
  instead. Its abstract methods didn't change.

See [Testing a language](languages/custom.md#-testing-a-language).

## 🔁 Languages keep run state in sessions, and engines can be closed

**What changed:**

- `CompiledCondition.copy()` and `CompiledAction.copy()` are removed. Every run shares a rule list's compiled
  expressions, and a language keeps what changes while they run in a `Session`. `ExpressionCompiler.newSession()`
  creates one for each copy of the rules, and `evaluate` and `execute` take it as a second parameter. Both may throw
  checked exceptions.
- `ExpressionCompiler`, `Session` and `RulesEngine` are `AutoCloseable`. The engine closes sessions and compilers once it
  no longer needs them: after `load()` replaces the rules and their runs finish, and when the engine is closed.
  After `close()`, `run()` and `load()` throw `IllegalStateException`.

**Who is affected:** authors of expression languages. Code that implements `RulesEngine` keeps compiling, because
`close()` has a default. Rule authors change nothing.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| `copy()` returning `this`, the default | `newSession()` returning `Session.none()` |
| `copy()` returning a new expression with its own state | Keep that state in a `Session` from `newSession()`, and read it from the `session` parameter |
| `evaluate(EvaluationContext context)` | `evaluate(EvaluationContext context, Session session)` |
| `execute(ActionContext context)` | `execute(ActionContext context, Session session)` |
| Resources released by garbage collection | Release them in `Session.close()` or `ExpressionCompiler.close()` |

Close an engine you discard, such as one built for a test or a short task, with `close()` or a try-with-resources
block.

## 📝 Languages compile an Expression, and every broken rule is reported

**What changed:**

- `ExpressionCompiler.compileCondition` and `compileAction` take an `Expression` instead of a `String`: the rule's
  name, `ExpressionKind.CONDITION` or `ACTION`, and the text.
- `InvalidExpressionException` can carry `issues()`, each with a severity, a line, a column and a message.
  `CompileContext.warn(Expression, Issue)` reports a warning, which is logged at WARN and doesn't fail loading.
- `load()` compiles every rule, then throws once. `RuleCompilationException.failures()` has each broken rule's
  exception, and `getExpressionKind()` and `issues()` say what failed and where. `RuleExecutionException` has
  `getExpressionKind()` too.
- Compile error messages name the expression. `Can not compile rule 'r'. Error: ...` is now
  `Condition for rule 'r' failed to compile: ...` or `Action for rule 'r' failed to compile: ...`. An MVEL syntax
  error reads `Condition for rule 'r' failed to compile at line 1, column 6: Malformed expression`, and its cause is
  an `InvalidExpressionException`, whose cause is MVEL's `CompileException`.

**Who is affected:** authors of expression languages, and code that reads compile error messages or causes.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| `compileCondition(String source)` | `compileCondition(Expression source)`, reading `source.text()` |
| Settings passed to a language's own constructor, such as a parser's features | `.option("my-language", "key", "value")` on the engine's builder, read from `CompileContext.options()` |
| Parsing `Can not compile rule 'x'. Error: ...` | `getRuleName()`, `getExpressionKind()` and `issues()`, or the new message |
| Reloading to find the next broken rule | `failures()`, which lists them all |
| `getCause()` is MVEL's `CompileException` | `getCause().getCause()`, or `issues()` |

## ↩️ Actions return a result

**What changed:** `CompiledAction.execute` returns an `ActionResult`. `ActionResult.done()` means the action changed
the output itself, as before. `ActionResult.set(properties)` returns values for the engine to set on the output: with
`put` on a `Map`, or with the output's public setters. This lets languages without side effects, such as CEL or
JsonLogic, write actions. In `ExpressionLanguageContractTest`, `reassignOutput()` and `declareVariable()` may return
`null` to skip their checks.

**Who is affected:** authors of expression languages. Rule authors change nothing.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| `void execute(ActionContext context)` | `ActionResult execute(ActionContext context, Session session)`, returning `ActionResult.done()` |
| Writing a computed value back to the output with reflection | `return ActionResult.set(Map.of("approved", true))` |
| An output the engine's `put`-and-setters writer can't set, such as one with builder-style methods | `.outputWriter((output, property, value) -> ...)` on the engine's builder |
| A language that needs to know the output's type | `CompileContext.outputType()`, which the application sets with `.outputType(LoanDecision.class)` |

## 🧱 Rules are immutable and need a name

**What changed:**

- `Rule` is a final, immutable class. Its no-arg and positional constructors, its setters and `canEqual`, deprecated
  since 1.4.0 and 1.8.0, are removed. `Rule.RuleBuilder` is final too.
- `build()` throws `IllegalStateException` when the name is `null` or blank, or the condition or action is `null`. The
  message names the field, such as `ruleName must not be null`. `load()` still rejects a blank condition or
  action.
- `getRuleName()` is never `null`, so every rule is checked for a duplicate name, and messages and
  `LoggingRuleListener` no longer show `(unnamed)`. An `Expression` needs a rule name too.
- The engine keeps the rules passed to `load()` instead of copying them, and listeners receive those same
  instances.

**Who is affected:** code that builds a rule without a name, creates or changes rules with the constructors or
setters, subclasses `Rule`, or reads rules from JSON or configuration through the no-arg constructor and setters.
Authors of expression languages whose tests create an `Expression` without a rule name.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| `Rule.builder().condition(c).action(a).build()` | Add `.ruleName("...")` |
| `new Rule(name, condition, action, priority, description)` | `Rule.builder().ruleName(name).condition(condition).action(action).priority(priority).description(description).build()` |
| `new Rule()`, then setters | `Rule.builder()`, then the builder's methods and `build()` |
| `rule.setPriority(5)` | `rule = rule.toBuilder().priority(5).build()` |
| JSON read through the no-arg constructor and setters | The two Jackson mix-ins below |
| Spring `@ConfigurationProperties` binding `List<Rule>` | Bind your own record and build the rules; see [Spring Boot](spring-boot.md#-rules-from-configuration) |
| `rule.getRuleName() != null ? name : "(unnamed)"`; in Kotlin, `rule.ruleName ?: "(unnamed)"` | `rule.getRuleName()`; in Kotlin, `rule.ruleName` is a `String` |
| `new Expression(null, kind, text)` | `new Expression("rule-name", kind, text)` |

To read rules with Jackson, register one mix-in for `Rule` and one for its builder. The code is the same for Jackson 2
and Jackson 3; only the imports differ (`com.fasterxml.jackson` or `tools.jackson`):

```java
@JsonDeserialize(builder = Rule.RuleBuilder.class)
abstract class RuleMixIn {
}

@JsonPOJOBuilder(withPrefix = "")
abstract class RuleBuilderMixIn {
}

ObjectMapper mapper = JsonMapper.builder()
        .addMixIn(Rule.class, RuleMixIn.class)
        .addMixIn(Rule.RuleBuilder.class, RuleBuilderMixIn.class)
        .build();
List<Rule> rules = mapper.readValue(json, new TypeReference<List<Rule>>() { });
```

JSON with a rule that has no name, condition or action now fails while it's read, instead of when `load()`
loads it.

## 🗂️ Facts are immutable, and a FactStore isn't a Map

**What changed:**

- `FactStore` no longer extends `Map`. It has `getValue`, `setValue` and `put(FactReference)`, and a new `asMap()`
  that returns a read-only view of the facts by name. `FactMap` still implements `Map`, so a `FactMap` variable keeps
  every `Map` method.
- `Fact` is a final, immutable class. `FactReference.setName` and `setValue`, and their overrides in `Fact`, deprecated
  since 1.8.0, are removed. So is the constructor `Fact(value)`, which named a fact after `value.toString()`.
- A fact needs a name: `new Fact<>(null, value)` throws `NullPointerException`, and `FactReference.getName()` is never
  `null`.
- `RulesEngine.run` takes a `FactStore<?>`, so a `FactMap<Applicant>` is accepted. `FactStore<Object> facts = new
  FactMap<>()` still works.

**Who is affected:** code that calls a `Map` method on a variable declared as `FactStore`; code that changes a `Fact`
or creates one with `new Fact<>(value)`; classes that implement `FactStore`, `FactReference` or `RulesEngine`; Kotlin
code.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| `facts.get(name)`, `facts.keySet()` or another read on a `FactStore` variable | `facts.asMap().get(name)`, `facts.asMap().keySet()` |
| `facts.remove(name)`, `facts.clear()` or another change on a `FactStore` variable | Declare the variable as `FactMap<Object>` |
| `fact.setValue(value)` | `facts.setValue(name, value)`, which stores a new `Fact` |
| `fact.setName(name)` | `new Fact<>(name, fact.getValue())` |
| `new Fact<>(value)` | `new Fact<>("name", value)` |
| A class that implements `FactStore` | Implement `asMap()`. The class may still implement `Map` too. |
| A class that implements `FactReference` | Remove `setName` and `setValue`, and never return a `null` name |
| `public O run(FactStore<Object> facts)` in a class that implements `RulesEngine` | `public O run(FactStore<?> facts)`. The old method no longer compiles: `name clash: run(FactStore<Object>) in MyEngine and run(FactStore<?>) in RulesEngine have the same erasure, yet neither overrides the other` |
| Kotlin: `engine.run(FactMap<Any>())` didn't compile | It compiles. A listener's `facts` parameter is still `Map<String, Any?>`. |
| Kotlin: a fact's `name` is a `String?` | It's a `String` |

## 🏗️ Engines are configured on a builder, and renamed

**What changed:**

- `RulesEngineBuilder.stateless(...)` and `stateful(...)` are renamed `firstMatch(...)` and `allMatches(...)`, after
  the hit policies they implement, and return a builder; `build()` creates the engine. A first-match engine fires the
  action of the highest-priority matching rule, and an all-matches engine fires every match in priority order, as
  before.
- Imports, expression languages, listeners, the limit on compiled copies, the output type, the output writer and each
  language's options are set on the builder, and can't change once the engine is built. `RulesEngine.addImport`, `addImports`, `registerLanguage`, `registerListener` and
  `registerListeners` are removed, and so are the `stateless(supplier, maxCopies)` and `stateful(supplier, maxCopies)`
  overloads.
- `RulesEngine.setRuleList(rules)` is renamed `load(rules)`. It still compiles the rules and swaps them in atomically,
  at any time.
- Imports are resolved by `build()`, so a bad import fails `build()` with `IllegalArgumentException` instead of
  `addImport()`. Expression languages are found once, by `build()`, instead of by every `setRuleList()`. Two found
  languages with the same name, or a found language without a name, fail `build()` with `IllegalStateException`, and
  a language that can't be created fails it with `ServiceConfigurationError`.
- Once `language(...)` is called, the engine has exactly the languages given, and MVEL isn't added for you. A rule
  without a `language` is written in the engine's default language: the one named with `defaultLanguage(...)`, or else
  the only language. `build()` fails when the engine has several languages and no default. A rule list without rules
  checks fact names against the default language instead of MVEL.

**Who is affected:** every application that creates an engine, and classes that implement `RulesEngine`.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| `RulesEngineBuilder.stateless(Decision::new)` | `RulesEngineBuilder.firstMatch(Decision::new).build()` |
| `RulesEngineBuilder.stateful(Decision::new)` | `RulesEngineBuilder.allMatches(Decision::new).build()` |
| `RulesEngineBuilder.stateless(Decision::new, 64)` | `RulesEngineBuilder.firstMatch(Decision::new).maxCopies(64).build()` |
| `engine.setRuleList(rules)` | `engine.load(rules)` |
| `engine.addImport("java.util")`, `engine.addImports(names)` | `.imports("java.util")`, `.imports(names)` on the builder |
| `engine.registerListener(listener)`, `engine.registerListeners(list)` | `.listener(listener)`, `.listeners(list)` on the builder |
| `engine.registerLanguage(language)`, with rules still in MVEL | `.language(new MvelExpressionLanguage()).language(language).defaultLanguage("mvel")` on the builder |
| A rule without a `language` on an engine given only other languages | It's written in the default language: set `language` on the rule, or choose the default with `defaultLanguage(...)` |
| A listener or import added to an engine that is already running | Build a new engine with it, and load the rules into it |
| `RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateful(HashMap::new)` | `RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build()`: in a chained call, Java needs the output type |
| A class that implements `RulesEngine` | Implement `load` and `run`, and remove `setRuleList`, `addImport`, `addImports`, `registerLanguage`, `registerListener` and `registerListeners` |

## 🔗 A missing class is reported like any other failure

**What changed:** the engine used to rethrow every `Error` except `StackOverflowError` and `AssertionError`
unchanged, so a `LinkageError` escaped `run()` and `load()` raw, with no rule name. Now only a
`VirtualMachineError` other than `StackOverflowError` — `OutOfMemoryError`, `InternalError`, `UnknownError` — escapes.

Every other `Error` is reported like an exception, naming the rule and keeping the error as its cause. That covers
every `LinkageError`: `NoClassDefFoundError`, `IllegalAccessError`, `IncompatibleClassChangeError`,
`ExceptionInInitializerError`, `VerifyError`. A missing or unreadable class means one rule is misconfigured, not that
the JVM is failing — for example a fact class exported only to `mvel2` on the module path, or a class directory on a
case-insensitive file system.

**Who is affected:** anyone catching a `LinkageError` around `load()` or `run()`, and listeners that see one thrown by
another listener. This reverses a 1.x decision, and the API compatibility check can't see it.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| `catch (NoClassDefFoundError e)` around `load(rules)` | `catch (RuleCompilationException e)`; `e.getRuleName()` names the rule and `e.getCause()` is the error |
| `catch (LinkageError e)` around `run(facts)` | `catch (RuleExecutionException e)`, with the error as its cause |
| A listener throwing a `LinkageError` to abort a run | It's contained and logged at WARN, like any listener failure; the run continues |
| `catch (OutOfMemoryError e)` | Unchanged: a `VirtualMachineError` still escapes unchanged |

## 🎯 A first-match engine stops at the first match

**What changed:** a first-match engine (`RulesEngineBuilder.firstMatch(...)`, the old `stateless`) evaluates
conditions in priority order and **stops at the first match**. It used to evaluate every condition and then fire the
highest-priority match. An all-matches engine is unchanged: it still evaluates every condition before firing any
action.

Two consequences:

- A broken lower-priority condition no longer fails a run that a higher-priority rule already decided.
- Firing one rule costs one matching condition instead of all of them.

**Who is affected:** anyone whose listeners count condition callbacks, or who relied on every run exercising every
rule. This is a behaviour change that the API compatibility check can't see, so there's no compiler error to catch it.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| A listener counting `beforeEvaluate` / `afterEvaluate` per run | Expect calls only up to the first match on a first-match engine |
| Relying on every run evaluating every rule, as a smoke test | Validate the rules at startup or in a test, as [Writing rules](writing-rules.md) recommends, rather than in production runs |
| Reading rule outcomes for rules below the match | They're neither matched nor unmatched: they weren't evaluated, so don't report them as `false` |
| Needing every condition evaluated, for example to detect more than one match | Use `allMatches(...)` |

## 📊 A run reports what it did, and an engine reports its rules

**What changed:**

- **`runWithResult(facts)`** returns a `RunResult`: the output object, the rules that fired in firing order, and the
  checksum of the rules the run used. `run(facts)` is unchanged, and is now a `default` method returning
  `runWithResult(facts).output()`.
- **`rules()`** returns a `RuleSetInfo`: the loaded rules in evaluation order, their checksum, and when they were
  loaded. Before the first `load()` it reports no rules and no load time.
- **The checksum** is the lowercase hex SHA-256 of the rules, covering each rule's name, priority, resolved language,
  condition and action, but not its description. A rule with no `language` hashes as the engine's default language, so
  the same rules on engines with different defaults have different checksums. A run keeps the checksum of the rules it
  started with, so it can differ from `rules().checksum()` after a reload: that's what an audit needs.
- **Listeners see a run**, with three new `default` callbacks: `beforeRun(RunContext)`,
  `afterRun(RunContext, RunResult)` and `onRunError(RunContext, RuntimeException)`. `onRunError` reports failures that
  belong to no rule too: a rejected fact name, an output supplier that throws, and an interrupt while the run waits for
  a compiled copy of the rules. `RunContext` identifies the run and names its parent, so a run started from an action
  no longer needs a `ThreadLocal` to be told apart.

**Who is affected:** classes that implement `RulesEngine`, such as decorators and test doubles. Listeners and callers
compile unchanged.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| A class that implements `RulesEngine` | Implement `runWithResult` and `rules()`; `run` now has a default that delegates to `runWithResult`. `RunResult.of(...)` and `RuleSetInfo.of(...)` create what they return. |
| A listener with a `ThreadLocal` to group callbacks into a run | `beforeRun` / `afterRun`, and `RunContext.runId()` or the context itself |
| A listener that counts failures in `onError` | `onRunError` also reports failures that belong to no rule |
| Recording which rules produced a decision with a shared listener | `runWithResult(facts).firedRules()` |
| Recording which version of the rules produced a decision | `runWithResult(facts).ruleSetChecksum()`, and `engine.rules().checksum()` for the engine's current rules |

## 🔒 Engines are created only with RulesEngineBuilder

**What changed:** `StatelessRulesEngine`, `StatefulRulesEngine` and `AbstractRulesEngine` in
`io.github.brantunger.unruly.core` are no longer public. Their constructors, deprecated since 1.3.0 and 1.6.0, are
removed, and so is `AbstractRulesEngine.JIT_PROPERTY`, which 1.x already ignored. The `core` package is internal and
no longer in the Javadoc.

**Who is affected:** code that creates an engine with `new`, declares a variable of an engine class, subclasses
`AbstractRulesEngine`, checks `instanceof StatefulRulesEngine`, or refers to `JIT_PROPERTY`.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| `new StatelessRulesEngine<>(Decision::new)` | `RulesEngineBuilder.firstMatch(Decision::new).build()` |
| `new StatefulRulesEngine<>(Decision::new, 64)` | `RulesEngineBuilder.allMatches(Decision::new).maxCopies(64).build()` |
| `StatelessRulesEngine<Decision> engine` | `RulesEngine<Decision> engine` |
| `class MyEngine extends AbstractRulesEngine<Decision>` | Implement `RulesEngine` and delegate to an engine from `RulesEngineBuilder` |
| `AbstractRulesEngine.JIT_PROPERTY` | Delete it; it had no effect |

`core.Engines` is public only so `RulesEngineBuilder` can reach the engines. Don't use it: it may change in any
release.

## 🪵 The engine logs under a fixed name

**What changed:** the engine logs rejected rule lists and facts, rule failures and listener exceptions under the logger
`io.github.brantunger.unruly.engine`, instead of `io.github.brantunger.unruly.core.AbstractRulesEngine`.

**Who is affected:** logging configuration that names the old logger. Configuration for the parent logger
`io.github.brantunger.unruly`, as the 1.x logging guide recommended, keeps working.

**What to change:** replace the old name, for example:

- Logback: `<logger name="io.github.brantunger.unruly.engine" level="OFF"/>`
- Spring Boot: `logging.level.io.github.brantunger.unruly.engine=off`
