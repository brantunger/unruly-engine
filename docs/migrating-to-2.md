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

**What changed:** the engine no longer creates MVEL itself. Each time `setRuleList()` is called, it finds expression
languages with `java.util.ServiceLoader`, from
`META-INF/services/io.github.brantunger.unruly.api.language.ExpressionLanguage` files, and MVEL is one of them. A rule
without a `language` is still written in MVEL.

**Who is affected:**

- **Class paths with another language listed in such a file.** That language can now be used by rules without
  `registerLanguage()`. Two found languages with the same name fail `setRuleList()`.
- **Applications repackaged into one jar** (a shaded or "uber" jar) that keep only one of several `META-INF/services`
  files with the same name. If MVEL's entry is lost, a rule without a `language` fails:
  `Rule 'prime-rate' is written in 'mvel', which isn't a registered expression language. Registered languages: []`.
- **Class paths without `mvel2`.** Creating an engine now succeeds, and `setRuleList()` fails instead.

**What to change:** usually nothing. When you repackage the library, merge service files, for example with the Maven
Shade plugin's `ServicesResourceTransformer`, or register MVEL yourself with
`engine.registerLanguage(new MvelExpressionLanguage())`.

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
  no longer needs them: after `setRuleList()` replaces the rules and their runs finish, and when the engine is closed.
  After `close()`, `run()` and `setRuleList()` throw `IllegalStateException`.

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
- `setRuleList()` compiles every rule, then throws once. `RuleCompilationException.failures()` has each broken rule's
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
| `new StatelessRulesEngine<>(Decision::new)` | `RulesEngineBuilder.stateless(Decision::new)` |
| `new StatefulRulesEngine<>(Decision::new, 64)` | `RulesEngineBuilder.stateful(Decision::new, 64)` |
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
