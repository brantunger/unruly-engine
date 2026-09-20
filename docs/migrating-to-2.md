# 🔼 Migrating from 1.x to 2.0

2.0 is a breaking release. This guide lists every change that can affect code written for 1.x, in the order you'd
meet them upgrading. Each section says what changed, who is affected, and what to change.

**Who it's for:** anyone upgrading an application from 1.x.
**You'll be able to:** decide whether to upgrade, find the sections that apply to your code, and change it.
**Before you start:** nothing. If you implement `RulesEngine` or write an expression language, read this page and
then [Migrating a language or an engine](migrating-to-2-implementers.md).

[← Documentation index](README.md)

- [Should you upgrade?](#-should-you-upgrade)
- [The license is now Apache-2.0](#-the-license-is-now-apache-20)
- [Which sections apply to you?](#-which-sections-apply-to-you)
- [Java 21 is required](#-java-21-is-required)
- [What you depend on](#-what-you-depend-on)
- [Expression languages are found with ServiceLoader](#-expression-languages-are-found-with-serviceloader)
- [Engines are configured on a builder](#-engines-are-configured-on-a-builder)
- [Rules are immutable and need a name](#-rules-are-immutable-and-need-a-name)
- [Facts are immutable, and a FactStore isn't a Map](#-facts-are-immutable-and-a-factstore-isnt-a-map)
- [A first-match engine stops early](#-a-first-match-engine-stops-early)
- [A missing class is reported like any other failure](#-a-missing-class-is-reported-like-any-other-failure)
- [Runs stop when interrupted or out of time](#-runs-stop-when-interrupted-or-out-of-time)
- [Compiled copies are limited on virtual threads](#-compiled-copies-are-limited-on-virtual-threads)
- [A run reports what it did](#-a-run-reports-what-it-did)
- [What your logs look like now](#-what-your-logs-look-like-now)
- [New in 2.0, optional](#-new-in-20-optional)

---

## 🤔 Should you upgrade?

**2.0 requires Java 21.** The jars are compiled for it, so Java 17 to 20 can't read them.

> [!IMPORTANT]
> **1.x ends at 2.0.0.** 1.8.0 is the last 1.x release, and only the latest release gets security fixes; see
> [SECURITY.md](../SECURITY.md). Staying on 1.x means staying on a line that receives none, so plan the move to
> Java 21 rather than treating 1.x as a resting place.

**Prepare on 1.8.0 first.** Upgrade to it while you're still on Java 17, and fix the deprecation warnings it gives.
Each deprecated member names its replacement. 1.8.0 warns about:

- `Rule`'s no-arg and positional constructors, its setters and `canEqual`;
- `Fact.setName`, `Fact.setValue` and the same two on `FactReference`;
- the `StatelessRulesEngine` and `StatefulRulesEngine` constructors, and `AbstractRulesEngine.JIT_PROPERTY`.

**What 1.8.0 doesn't warn about.** These are removed or changed in 2.0 with no deprecation warning first, so the
compiler won't point at them until you're on 2.0:

| Removed or changed in 2.0 | Section |
| --- | --- |
| `RulesEngineBuilder.stateless(...)` and `stateful(...)` | [The builder](#-engines-are-configured-on-a-builder) |
| `RulesEngine.setRuleList`, `addImport`, `addImports`, `registerLanguage`, `registerListener`, `registerListeners` | [The builder](#-engines-are-configured-on-a-builder) |
| `FactStore` no longer extends `Map` | [Facts](#-facts-are-immutable-and-a-factstore-isnt-a-map) |
| The `new Fact<>(value)` constructor | [Facts](#-facts-are-immutable-and-a-factstore-isnt-a-map) |
| `extends AbstractRulesEngine`, and `instanceof StatefulRulesEngine` | [The builder](#-engines-are-configured-on-a-builder) |
| The language SPI's `copy()` | [Sessions](migrating-to-2-implementers.md#-languages-keep-run-state-in-sessions) |
| The language SPI's `compileCondition(String)` and `compileAction(String)` | [Compiling](migrating-to-2-implementers.md#-languages-compile-an-expression) |

## 📄 The license is now Apache-2.0

**What changed:** 2.0.0 and later are published under the [Apache License 2.0](../LICENSE). 1.8.0 and every release
before it were published under the GNU General Public License v3.0, and the copies of them on Maven Central carry
that license; the rights it granted can't be withdrawn.

**Who is affected:** anyone whose legal or compliance review covered a GPL-3.0 dependency, and anyone who avoided
the library because of one.

**What to change:** nothing in your code. No class, method, message or behavior changes with the license.

Apache-2.0 is permissive: use, modify and redistribute the engine, including inside closed-source software. Each
contributor also grants a patent license, limited to the claims their contribution necessarily infringes, alone or
combined with the engine, and it ends if you bring patent litigation claiming the engine infringes.

What the upgrade removes is GPL-3.0's copyleft. Under 1.x, distributing software that included the engine
generally meant releasing that software under the GPL too; Apache-2.0 asks for notices instead. Neither license
asks anything of a service you only host — GPL-3.0 has no network clause.

Shipping software that contains the engine — a fat jar, a WAR, a container image or an installer, repackaged or
not — means keeping its copyright notices and including the [LICENSE](../LICENSE) and the [NOTICE](../NOTICE)
alongside your own. If you modify its source files, mark them as changed.

> [!NOTE]
> A license scanner that reads the POM sees the change: 1.x declares `GNU GENERAL PUBLIC LICENSE v3.0` and 2.0.0
> declares `The Apache License, Version 2.0`. Re-run the scan after the upgrade rather than carrying the old result
> forward.

## 📍 Which sections apply to you?

| You... | Read |
| --- | --- |
| Call the engine from an application | [Java 21](#-java-21-is-required), [What you depend on](#-what-you-depend-on), [The builder](#-engines-are-configured-on-a-builder), [Rules](#-rules-are-immutable-and-need-a-name), [Facts](#-facts-are-immutable-and-a-factstore-isnt-a-map) |
| Configure logging, or read the engine's log lines | [What your logs look like now](#-what-your-logs-look-like-now): the logger was renamed, and several messages changed |
| Run rules on virtual threads | [Compiled copies](#-compiled-copies-are-limited-on-virtual-threads), the biggest change no compiler will catch |
| Run on the module path or shade the jar | [What you depend on](#-what-you-depend-on), [ServiceLoader](#-expression-languages-are-found-with-serviceloader) |
| Write a `RuleListener` | [First match stops](#-a-first-match-engine-stops-early), [Interrupts and timeouts](#-runs-stop-when-interrupted-or-out-of-time), [Run callbacks](#-a-run-reports-what-it-did), [A missing class](#-a-missing-class-is-reported-like-any-other-failure) |
| Write rules in MVEL | Nothing in your rule text changes; the messages a broken rule gives do: [What your logs look like now](#-what-your-logs-look-like-now) |
| Use Kotlin | [Facts](#-facts-are-immutable-and-a-factstore-isnt-a-map), then, for code written for 1.4 or earlier, [Upgrading Kotlin code](kotlin.md#-upgrading-kotlin-code-from-14-or-earlier) |
| Implement `RulesEngine`, or write an expression language | This page, then [Migrating a language or an engine](migrating-to-2-implementers.md) |
| Review the licenses of your dependencies, or redistribute the engine's code | [The license is now Apache-2.0](#-the-license-is-now-apache-20): 2.0.0 drops GPL-3.0 for Apache-2.0 |

Four changes your compiler won't catch:

> [!WARNING]
> - [Compiled copies are limited on virtual threads](#-compiled-copies-are-limited-on-virtual-threads): runs may wait.
> - [A first-match engine stops early](#-a-first-match-engine-stops-early): fewer condition callbacks.
> - [A missing class is a rule failure](#-a-missing-class-is-reported-like-any-other-failure): no raw `LinkageError`.
> - [An interrupted run stops](#-runs-stop-when-interrupted-or-out-of-time): pooled threads now fail.

## ☕ Java 21 is required

**What changed:** the library is compiled for Java 21, class file major version 65. 1.x was compiled for Java 17,
version 61.

**Who is affected:** projects built with, or running on, Java 17, 18, 19 or 20. A JDK 17 compiler can't read the
engine's classes:

```text
error: cannot access RulesEngineBuilder
  bad class file: .../io/github/brantunger/unruly/api/RulesEngineBuilder.class
    class file has wrong version 65.0, should be 61.0
```

An application built with a newer JDK but run on Java 17 gets past compiling and fails when it first uses the engine:

```text
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

The engine is built and tested on Java 21, and its tests also run on Java 25.

## 📦 What you depend on

**What changed:** the library is published as three artifacts instead of one, and every jar is a named module.

**Who is affected:** builds that list jars by hand instead of resolving dependencies, builds that exclude the
library's transitive dependencies, and applications on the module path.

From 2.0.0 each published jar also carries a GitHub build provenance attestation, which names the workflow and the
commit it was built from. [Checking a release by hand](../RELEASING.md#-checking-a-release-by-hand) shows how to
verify a downloaded jar; releases before 2.0.0 have none.

### The library is three artifacts

`unruly-engine-core` is the engine and its API, and `unruly-engine` is the MVEL language, which depends on
`unruly-engine-core`. A third, `unruly-engine-test`, is the contract test kit for language authors and is needed only
by them. Package and class names didn't change. With only `unruly-engine-<version>.jar`, the API classes are missing.

Usually nothing to change: a Maven or Gradle dependency on `unruly-engine` brings `unruly-engine-core` with it. An
application whose rules all name other languages can depend on `unruly-engine-core` alone; see
[Packaging](languages/custom.md#-packaging).

### The jars are named modules

`unruly-engine` is still the module `io.github.brantunger.unruly`, and `unruly-engine-core` is the module
`io.github.brantunger.unruly.core`. They export only the API packages, not `io.github.brantunger.unruly.core`, which
only the test kit can read, and they require SLF4J and MVEL themselves. 1.x declared no module at all: it shipped an
`Automatic-Module-Name`, which required nothing of anyone.

So a Gradle application on the module path now fails to start, because Gradle puts MVEL's unnamed jar on the class
path and nothing there can satisfy the engine's `requires mvel2`:

```text
java.lang.module.FindException: Module mvel2 not found, required by io.github.brantunger.unruly
```

| 1.x | 2.0 |
| --- | --- |
| Gradle on the module path, which needed nothing extra | Name MVEL's module with the extra-java-module-info plugin, as [Installation](../README.md#-installation) shows |
| `requires org.slf4j` and `--add-modules org.slf4j`, needed only for the engine | Remove them unless your application uses SLF4J itself; they're harmless if you keep them |
| A Javadoc link such as `latest/io/github/brantunger/unruly/api/RulesEngine.html` | `latest/io.github.brantunger.unruly.core/io/github/brantunger/unruly/api/RulesEngine.html`: the site has a directory for each module |

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
  files with the same name.
- **Class paths without `mvel2`.** Building an engine now succeeds, and loading MVEL rules fails instead.

When MVEL's service entry is lost and no other language is found, building an engine fails:

```text
The engine has no expression language: add one with language(), or put a language on the class path or, with a
provides clause, on the module path
```

**What to change:** usually nothing. When you repackage the library, merge service files, for example with the Maven
Shade plugin's `ServicesResourceTransformer`, or give the engine MVEL yourself with
`.language(new MvelExpressionLanguage())` on its builder.

## 🔧 Engines are configured on a builder

**What changed:**

- `RulesEngineBuilder.stateless(...)` and `stateful(...)` are renamed `firstMatch(...)` and `allMatches(...)`, after
  the match policies they implement, and return a builder; `build()` creates the engine.
- Imports, expression languages, listeners, the copy limit, the output type and writer, and each language's options
  are set on the builder and can't change once the engine is built. `RulesEngine.addImport`, `addImports`,
  `registerLanguage`, `registerListener` and `registerListeners` are removed, and so are the
  `stateless(supplier, maxCopies)` and `stateful(supplier, maxCopies)` overloads.
- `RulesEngine.setRuleList(rules)` is renamed `load(rules)`, and still swaps the rules in atomically at any time.

A third match policy, `uniqueMatch(...)`, is new: it evaluates every condition, fires the one match, and fails the
run when more than one rule matches. See
[Unique match: one rule or none](engines-and-runs.md#unique-match-one-rule-or-none).

Because `build()` now does the work `addImport()` and `setRuleList()` used to, it is where those failures appear: a
bad import fails it with `IllegalArgumentException`; two found languages with the same name, a found language without
a name, or several languages with no default fail it with `IllegalStateException`; and a language that can't be
created fails it with `ServiceConfigurationError`.

Once `language(...)` is called, the engine has exactly the languages given, and MVEL isn't added for you. A rule
without a `language` is written in the default language: the one named with `defaultLanguage(...)`, or else the only
language. A rule list without rules checks fact names against the default language instead of MVEL.

**Who is affected:** every application that creates an engine.

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

### The engine classes are no longer public

`StatelessRulesEngine`, `StatefulRulesEngine` and `AbstractRulesEngine` in `io.github.brantunger.unruly.core` are no
longer public, their constructors are removed, and so is `AbstractRulesEngine.JIT_PROPERTY`, which 1.x already
ignored. The `core` package is internal and no longer in the Javadoc.

| 1.x | 2.0 |
| --- | --- |
| `new StatelessRulesEngine<>(Decision::new)` | `RulesEngineBuilder.firstMatch(Decision::new).build()` |
| `new StatefulRulesEngine<>(Decision::new, 64)` | `RulesEngineBuilder.allMatches(Decision::new).maxCopies(64).build()` |
| `StatelessRulesEngine<Decision> engine`, or `instanceof StatefulRulesEngine` | `RulesEngine<Decision> engine`; the match policy isn't a type you can test for |
| `class MyEngine extends AbstractRulesEngine<Decision>` | Implement `RulesEngine`; see [Migrating a language or an engine](migrating-to-2-implementers.md#-if-you-implement-rulesengine) |
| `AbstractRulesEngine.JIT_PROPERTY` | Delete it; it had no effect |

## 🧱 Rules are immutable and need a name

**What changed:**

- `Rule` is a final, immutable class. Its no-arg and positional constructors, its setters and `canEqual` are removed,
  and `Rule.RuleBuilder` is final too.
- `build()` throws `IllegalStateException` when the name is `null` or blank, or the condition or action is `null`.
  The message names the field, such as `ruleName must not be null`. `load()` still rejects a blank condition or
  action.
- `getRuleName()` is never `null`, so every rule is checked for a duplicate name, and messages and
  `LoggingRuleListener` no longer show `(unnamed)`.

The engine keeps the rules passed to `load()` instead of copying them, and listeners receive those same instances. A
rule also has four new fields, which choose the runs that use it: `enabled`, `validFrom`, `validTo` and `tags`. See
[Choosing which rules a run uses](engines-and-runs.md#-choosing-which-rules-a-run-uses).

`toString()` lists the four new fields after `language`, and `hashCode()` mixes them in, so every rule's text and hash
code differ from 1.x, even a rule that doesn't set them. `equals` compares them too. A rule built with only a name, a
condition and an action now prints:

```text
Rule(ruleName=r, condition=true, action=x, priority=null, description=null, language=null, enabled=true, validFrom=null, validTo=null, tags=[])
```

**Who is affected:** code that builds a rule without a name, creates or changes rules with the constructors or
setters, subclasses `Rule`, or reads rules from JSON or configuration through the no-arg constructor and setters.
Also code that parses `Rule.toString()`, compares it in tests, or stores a rule's `hashCode()`, which the compiler
can't catch.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| `Rule.builder().condition(c).action(a).build()` | Add `.ruleName("...")` |
| `new Rule(name, condition, action, priority, description)` | `Rule.builder().ruleName(name).condition(condition).action(action).priority(priority).description(description).build()` |
| `new Rule()`, then setters | `Rule.builder()`, then the builder's methods and `build()` |
| `rule.setPriority(5)` | `rule = rule.toBuilder().priority(5).build()` |
| JSON read through the no-arg constructor and setters | Two Jackson mix-ins, one for `Rule` and one for its builder; see [Loading rules from data](writing-rules.md#-loading-rules-from-data) |
| Spring `@ConfigurationProperties` binding `List<Rule>` | Bind your own record and build the rules; see [Spring Boot](spring-boot.md#-rules-from-configuration) |
| `rule.getRuleName() != null ? name : "(unnamed)"`; in Kotlin, `rule.ruleName ?: "(unnamed)"` | `rule.getRuleName()`; in Kotlin, `rule.ruleName` is a `String` |
| A test that expects 1.x's `Rule.toString()` or `hashCode()` value | Expect the 2.0 value, which includes the four new fields |
| Filtering the list before `load()` to switch rules off, schedule them, or pick a market | `enabled(false)`, `validFrom(...)`, `validTo(...)` or `tags(...)` on the rules, and `RunOptions.defaults().withTags(...)` for a run |

JSON with a rule that has no name, condition or action now fails while it's read, instead of when `load()` loads it.
[Choosing which rules a run uses](engines-and-runs.md#-choosing-which-rules-a-run-uses) owns the four new fields.

## 📁 Facts are immutable, and a FactStore isn't a Map

**What changed:**

- `FactStore` no longer extends `Map`. It has `getValue`, `setValue` and `put(FactReference)`, and a new `asMap()`
  that returns a read-only view of the facts by name. `FactMap` still implements `Map`, so a `FactMap` variable keeps
  every `Map` method.
- `Fact` is a final, immutable class. `FactReference.setName` and `setValue`, and their overrides in `Fact`, are
  removed. So is the constructor `Fact(value)`, which named a fact after `value.toString()` and which 1.8.0 did
  **not** deprecate.
- A fact needs a name: `new Fact<>(null, value)` throws `NullPointerException`, and `FactReference.getName()` is
  never `null`.

`RulesEngine.run` now takes a `FactStore<?>`, so a `FactMap<Applicant>` is accepted, and
`FactStore<Object> facts = new FactMap<>()` still works.

**Who is affected:** code that calls a `Map` method on a variable declared as `FactStore`; code that changes a `Fact`
or creates one with `new Fact<>(value)`; classes that implement `FactStore` or `FactReference`; Kotlin code.

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
| Kotlin: `engine.run(FactMap<Any>())` didn't compile | It compiles. A listener's `facts` parameter is still `Map<String, Any?>`. |
| Kotlin: a fact's `name` is a `String?` | It's a `String` |

## 🎯 A first-match engine stops early

**What changed:** a first-match engine (`RulesEngineBuilder.firstMatch(...)`, the old `stateless`) evaluates
conditions in priority order and **stops at the first match**. It used to evaluate every condition and then fire the
highest-priority match. An all-matches engine is unchanged: it still evaluates every condition before firing any
action.

Two consequences: a broken lower-priority condition no longer fails a run that a higher-priority rule already
decided, and firing one rule costs one matching condition instead of all of them.

**Who is affected:** anyone whose listeners count condition callbacks, or who relied on every run exercising every
rule. This is a behaviour change that the API compatibility check can't see, so there's no compiler error to catch
it.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| A listener counting `beforeEvaluate` / `afterEvaluate` per run | Expect calls only up to the first match on a first-match engine |
| Relying on every run evaluating every rule, as a smoke test | Validate the rules at startup or in a test, as [Writing rules](writing-rules.md) recommends, rather than in production runs |
| Reading rule outcomes for rules below the match | They're neither matched nor unmatched: `runWithResult(facts).evaluations()` reports them as `NOT_EVALUATED`, so don't report them as `false` |
| Needing every condition evaluated, for example to detect more than one match | Use `allMatches(...)`, or `uniqueMatch(...)`, which fails a run in which more than one rule matches |

## 🔗 A missing class is reported like any other failure

**What changed:** the engine used to rethrow every `Error` except `StackOverflowError` and `AssertionError`
unchanged, so a `LinkageError` escaped `run()` and `load()` raw, with no rule name. Now only a
`VirtualMachineError` other than `StackOverflowError` — `OutOfMemoryError`, `InternalError`, `UnknownError` —
escapes.

Every other `Error` is reported like an exception, naming the rule and keeping the error as its cause. That covers
every `LinkageError`: `NoClassDefFoundError`, `IllegalAccessError`, `IncompatibleClassChangeError`,
`ExceptionInInitializerError`, `VerifyError`. A missing or unreadable class means one rule is misconfigured, not that
the JVM is failing — for example a fact class exported only to `mvel2` on the module path.

**Who is affected:** anyone catching a `LinkageError` around `load()` or `run()`, and listeners that see one thrown
by another listener. This reverses a 1.x decision, and the API compatibility check can't see it.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| `catch (NoClassDefFoundError e)` around `load(rules)` | `catch (RuleCompilationException e)`; `e.getRuleName()` names the rule and `e.getCause()` is the error |
| `catch (LinkageError e)` around `run(facts)` | `catch (RuleExecutionException e)`, with the error as its cause |
| A listener throwing a `LinkageError` to abort a run | It's contained and logged at WARN, like any listener failure; the run continues |
| `catch (OutOfMemoryError e)` | Unchanged: a `VirtualMachineError` still escapes unchanged |

## ⏳ Runs stop when interrupted or out of time

**What changed:** the engine checks before and after each condition and each action whether the run must stop,
because its thread was interrupted or it has passed a deadline. In 1.x nothing in the engine looked at the interrupt
status, so `Future.cancel(true)` and an executor shutting down had no effect until the run finished on its own.

A run whose thread is interrupted, before it starts or while it is going, throws a `RuleExecutionException` caused by
an `InterruptedException` at the next check. `getRuleName()` is `null`: an interrupt isn't that rule's failure, and
the interrupt status stays set. `RulesEngineBuilder.runTimeout(Duration)`, `RunOptions` and
`runWithResult(facts, options)` are new; a run past its deadline fails the same way, with a `TimeoutException` as
the cause, including while it waits for a compiled copy of the rules.
[What stops a run](stopping-runs.md#-what-stops-a-run) owns every place a run stops.

Two consequences worth knowing before you set a timeout: a run whose **last** condition or action returns past the
deadline still fails, although the rule finished, and a run that is waiting for a compiled copy fails there rather
than waiting past its deadline.

> [!WARNING]
> An expression that is already running isn't stopped; the run stops when it returns. MVEL has no hook inside one, so
> a rule that loops for ever still blocks the thread, with or without a timeout.

**Who is affected:** anyone who runs the engine on a pool whose threads get interrupted, and anyone who sets a
timeout.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| Catching the exception and serving the next request on the same thread | Clear the interrupt status first, for example with `Thread.interrupted()`, or every later run on that thread stops at its first rule |
| Matching on `"Interrupted while waiting for a compiled copy of the rules"` | `run() was interrupted while waiting for a compiled copy of the rules: all N were in use`, logged at WARN, not ERROR |
| Your own timer around `run()` | `runTimeout(Duration)` on the builder, or `runWithResult(facts, RunOptions.withTimeoutOf(...))` for one run |
| A `RuleListener` that assumed a run reaching `onRunError` had failed in a rule | A stop can reach `onError` as well, with no rule name and a `TimeoutException` or `InterruptedException` cause |

The copy-wait message now appears only when a run really waited. `RunOptions` replaces the engine's timeout for one run,
longer or shorter, but no option removes it, so keep your own timer if you need a hard limit. And a listener that
counts rule failures has to tell them from stops: a run stopped between rules reaches `onRunError` with no `onError`,
while one stopped during a condition or action closes that rule with `onError` first.

## 🧵 Compiled copies are limited on virtual threads

**What changed:** each run borrows a compiled copy of the rules, and 1.x made a new one whenever every copy was in
use, with no upper bound unless you asked for one. 2.0 limits **runs on virtual threads** to one copy for every two
processors, and at least one. Runs on platform threads aren't limited, so a thread pool keeps the throughput it had.

`maxCopies(int)` still limits runs from every kind of thread, `unlimitedCopies()` turns the default off, and a rule
list whose languages all return `Session.none()` needs no copies, so no limit applies to it.
[Compiled copies](compiled-copies.md) and [Virtual threads](virtual-threads.md) own the rest: which runs wait, which
never do, and the [build slots](virtual-threads.md#-waiting-for-a-build-slot) that pace the copies
`unlimitedCopies()` makes.

**Who is affected:** applications that run rules on virtual threads, and applications with a limit whose rules wait
on I/O. The API compatibility check can't see a behaviour change, so there's no compiler error to catch it.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| Runs from virtual threads, with as many copies as runs | At most one copy for every two processors; the runs above that wait |
| Rules that wait on a database, a service or a file, run from virtual threads | `.maxCopies(n)`, sized for the runs you want waiting at once: the default is sized for rules that compute |
| `.unlimitedCopies()` as the way back to 1.x | It removes the limit, but a virtual thread that has to make a copy first waits for a [build slot](virtual-threads.md#-waiting-for-a-build-slot) |
| An action that runs the same engine on another thread and waits for it | It no longer hangs: the nested run takes an extra copy after five seconds, and the engine warns |
| Sizing memory from the number of copies | The limit, plus one for each platform-thread run at your peak without `maxCopies(...)`, plus an extra for each run that didn't wait |

The limit is per engine and holds across reloads. A run that is waiting for a copy still stops at its deadline or on
an interrupt, as [Runs stop when interrupted or out of time](#-runs-stop-when-interrupted-or-out-of-time) and
[What stops a run](stopping-runs.md#-what-stops-a-run) describe; waiting for a build slot never fails on a deadline,
but an interrupt there fails the run with
`run() was interrupted while waiting to make a compiled copy of the rules: every build slot was in use`.

> [!CAUTION]
> On JDK 21 to 23, MVEL rules with `unlimitedCopies()` can hold every carrier and deadlock. Keep any `maxCopies(n)`
> below the number of carriers there, and read
> [What changes on virtual threads](virtual-threads.md#-what-changes-on-virtual-threads) first.

## 📊 A run reports what it did

**What changed:**

- **`runWithResult(facts)`** returns a `RunResult`: the output object, the rules that fired in firing order, what each
  rule's condition evaluated to, the checksum of the rules the run used, and the run's tags and the instant it judged
  validity windows at. `run(facts)` is unchanged, and is now a `default` method returning
  `runWithResult(facts).output()`.
- **`rules()`** returns a `RuleSetInfo`: the loaded rules in evaluation order, their checksum, and when they were
  loaded. Before the first `load()` it reports no rules and no load time.

**Listeners see a run** too, with three new `default` callbacks: `beforeRun(RunContext)`,
`afterRun(RunContext, RunResult)` and `onRunError(RunContext, RuntimeException)`. `RunContext` identifies the run and
names its parent, so a run started from an action no longer needs a `ThreadLocal` to be told apart.

A run keeps the checksum of the rules it started with, so it can differ from `rules().checksum()` after a reload:
that's what an audit needs. [What a run reports](engines-and-runs.md#-what-a-run-reports) owns the outcomes and
[Auditing a decision](engines-and-runs.md#-auditing-a-decision) the checksum's layout.

**Who is affected:** nobody has to change anything. Listeners and callers compile unchanged; a class that implements
`RulesEngine` has [its own section](migrating-to-2-implementers.md#-if-you-implement-rulesengine).

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| A listener with a `ThreadLocal` to group callbacks into a run | `beforeRun` / `afterRun`, and `RunContext.runId()` or the context itself |
| A listener that counts failures in `onError` | `onRunError` reports failures that belong to no rule, and `onError` can close a [stopped](#-runs-stop-when-interrupted-or-out-of-time) rule |
| Recording which rules produced a decision with a shared listener | `runWithResult(facts).firedRules()` |
| Recording every condition's result with a listener, to explain a decision | `runWithResult(facts).evaluations()`, one entry per rule |
| Recording which version of the rules produced a decision | `runWithResult(facts).ruleSetChecksum()`, and `engine.rules().checksum()` for the engine's current rules |
| Recording what chose the rules for a run | `RunContext.tags()` and `startedAt()`, and the same two on `RunResult` |

## 🪵 What your logs look like now

**What changed:** the engine logs under a different name, and the text of several messages changed.

**Who is affected:** logging configuration that names the old logger, and anything that greps, parses or alerts on
the engine's messages. None of it is a compile error.

**What to change:**

| 1.x | 2.0 |
| --- | --- |
| The logger `io.github.brantunger.unruly.core.AbstractRulesEngine` | `io.github.brantunger.unruly.engine`; configuration for the parent logger `io.github.brantunger.unruly` keeps working |
| Messages that copied what a rule threw at run time, a language's compile error, or what your supplier or listener threw, as it was written | All of it is escaped now, so a line break in it can't forge a log line |
| A listener's exception logged at WARN with its stack trace | WARN with `Listener threw exception in <callback>: <class>: <message>`, escaped; the stack trace at DEBUG |
| `LoggingRuleListener` logging `Failed rule: <name> \| Error: <message>` for every failure | A rule the run stopped is logged as `Stopped rule: <name> \| <message>`, and no rule is ever `(unnamed)`. Both lines are at DEBUG, as in 1.x |
| Parsing `Can not compile rule 'x'. Error: ...` | `Condition for rule 'x' failed to compile: ...`, or `Action for rule 'x' ...`, with the language's position when it gives one |
| Parsing MVEL's `'+=' at position 13` for an assignment or `import_static` in a condition | `at line 1, column 14`, carried as an `InvalidExpressionException.Issue` as well as in the text |

1.x already escaped rule, fact and language *names*, and already shortened names to 200 characters and copied text
to 1,000; [Exceptions by method](error-handling.md#-exceptions-by-method) owns those rules, and
[Logging setup](listeners-and-logging.md#-logging-setup) owns every line and its level. A positioned compile error
reads `Condition for rule 'r' failed to compile at line 1, column 6: Malformed expression`, and
[Errors when rules load](languages/mvel.md#-errors-when-rules-load) covers what MVEL puts in one.

To replace the logger name, for example:

- Logback: `<logger name="io.github.brantunger.unruly.engine" level="OFF"/>`
- Spring Boot: `logging.level.io.github.brantunger.unruly.engine=off`

## 🆕 New in 2.0, optional

Features 1.x didn't have. A caller needs none of them to upgrade.

| New in 2.0 | See |
| --- | --- |
| `runWithResult()`, with `firedRules()`, `evaluations()`, `ruleSetChecksum()`, `tags()` and `startedAt()` | [A run reports what it did](#-a-run-reports-what-it-did) |
| `rules()` | [A run reports what it did](#-a-run-reports-what-it-did) |
| `validate(rules)` | [Checking a list before loading it](engines-and-runs.md#checking-a-list-before-loading-it) |
| `runTimeout(...)` and `RunOptions` | [Stopping a run](stopping-runs.md) |
| A rule's `enabled`, `validFrom`, `validTo` and `tags`, `RunOptions.defaults().withTags(...)`, the builder's `clock(...)`, and the `SKIPPED` outcome | [Choosing which rules a run uses](engines-and-runs.md#-choosing-which-rules-a-run-uses) |
| Declared facts: `fact(...)`, `facts(...)` and `requireDeclaredFacts()` | [Declaring facts](facts.md#-declaring-facts) |
| `outputType(...)` and `outputWriter(...)` | [The output object](engines-and-runs.md#-the-output-object) |
| The run callbacks `beforeRun`, `afterRun` and `onRunError` | [Callbacks](listeners-and-logging.md#-callbacks) |
| `uniqueMatch(...)` | [Unique match: one rule or none](engines-and-runs.md#unique-match-one-rule-or-none) |
| `unlimitedCopies()` and `copiesAtLoad(n)` | [Compiled copies](compiled-copies.md) |
| MVEL's `strongTyping` option, which makes a misspelled property fail `load()` | [Strong typing](languages/mvel.md#-strong-typing) |
| Flight Recorder events for runs and rules | [Flight Recorder events](listeners-and-logging.md#-flight-recorder-events) |
| GraalVM native images: reflection metadata for MVEL in the `unruly-engine` jar | [Native image](native-image.md) |
| `close()` on an engine | [Lifecycle and closing](thread-safety.md#-lifecycle-and-closing) |
| For language authors: `FactProperties`, `ActionResult`, per-language `option(...)`, `warmUp(Session)` and the contract kit | [Migrating a language or an engine](migrating-to-2-implementers.md) |
