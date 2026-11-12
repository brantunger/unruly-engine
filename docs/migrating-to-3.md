# 🔼 Migrating from 2.x to 3.0

3.0 is a breaking release. This guide lists every change that can affect code written for 2.x, in the order you'd
meet them upgrading. Each section says what changed, who is affected, and what to change.

**Who it's for:** anyone upgrading an application, an expression language or a `RulesEngine` implementation from 2.x.
**You'll be able to:** find the sections that apply to your code, and change it.
**Before you start:** nothing. Still on 1.x? Read [Migrating from 1.x to 2.0](migrating-to-2.md) first: 3.0 keeps
every 2.0 change, and still requires Java 21.

> [!NOTE]
> 3.0.0 isn't released yet. This page describes the unreleased 3.0 code, and has no copy at a released tag; on the
> latest 2.x release, none of it applies.

[← Documentation index](README.md)

- [Which sections apply to you?](#-which-sections-apply-to-you)
- [The api.exception package is gone](#-the-apiexception-package-is-gone)
- [Upgrade the test kit with the engine](#-upgrade-the-test-kit-with-the-engine)
- [Language implementers](#-language-implementers)
  - [A language compiled against 2.x](#a-language-compiled-against-2x)
- [If you implement RulesEngine](#-if-you-implement-rulesengine)

---

## 📍 Which sections apply to you?

| You... | Read |
| --- | --- |
| Catch `RuleCompilationException` or `RuleExecutionException`, write a `RuleListener`, or import anything else from `api.exception` | [The api.exception package is gone](#-the-apiexception-package-is-gone) |
| Run a jar compiled against 2.x that you don't rebuild, such as a third-party listener or language | [Jars compiled against 2.x](#jars-compiled-against-2x) |
| Store or send the engine's exceptions with Java serialization | [Serialized exceptions](#serialized-exceptions) |
| Build a GraalVM native image with strict metadata | [Native images](#native-images) |
| Test a language with `unruly-engine-test` | [Upgrade the test kit with the engine](#-upgrade-the-test-kit-with-the-engine) |
| Write an expression language | [The imports](#-the-apiexception-package-is-gone), then [Language implementers](#-language-implementers) |
| Declare facts with `fact(name, X.class)` or `facts(map)` | Nothing: both compile and run as before; see [Language implementers](#-language-implementers) for what's new |
| Implement `RulesEngine` | [The imports](#-the-apiexception-package-is-gone), then [If you implement RulesEngine](#-if-you-implement-rulesengine) |

Nothing in your rule text changes. In code you rebuild, the compiler catches every change on this page. It can't
catch two: a [jar compiled against 2.x](#jars-compiled-against-2x) that you run without rebuilding it, which
includes a [language that reads a declared fact's type as a `Class`](#a-language-compiled-against-2x), and reading
an exception a 2.x program [serialized](#serialized-exceptions).

## 🚨 The api.exception package is gone

**What changed:** the package `io.github.brantunger.unruly.api.exception` is dissolved. Each of its types moved to
the package that owns it: the two exceptions the engine throws to `api`, next to `RulesEngine`, and the three types
a language sees to `api.language`. Names, members and behaviour are the same; only the package is new.

**Who is affected:** every file that imports a type from `api.exception`: code that catches
`RuleCompilationException` or `RuleExecutionException`, a `RuleListener` that overrides `onError`, every expression
language, and every class that implements `RulesEngine`.

**What to change:** the imports.

| 2.x | 3.0 |
| --- | --- |
| `io.github.brantunger.unruly.api.exception.RuleCompilationException` | `io.github.brantunger.unruly.api.RuleCompilationException` |
| `io.github.brantunger.unruly.api.exception.RuleExecutionException` | `io.github.brantunger.unruly.api.RuleExecutionException` |
| `io.github.brantunger.unruly.api.exception.UnrulyException` | `io.github.brantunger.unruly.api.language.UnrulyException` |
| `io.github.brantunger.unruly.api.exception.InvalidExpressionException` | `io.github.brantunger.unruly.api.language.InvalidExpressionException` |
| `io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue` | `io.github.brantunger.unruly.api.language.InvalidExpressionException.Issue` |
| `io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue.Severity` | `io.github.brantunger.unruly.api.language.InvalidExpressionException.Issue.Severity` |
| `io.github.brantunger.unruly.api.exception.ExpressionKind` | `io.github.brantunger.unruly.api.language.ExpressionKind` |

The two engine exceptions go to `api` and everything else to `api.language`, so a search and replace in that order
does it. With GNU sed, over a source tree:

```bash
find src \( -name '*.java' -o -name '*.kt' \) -exec sed -i \
    -e 's/unruly\.api\.exception\.\(RuleCompilationException\|RuleExecutionException\)/unruly.api.\1/g' \
    -e 's/unruly\.api\.exception\./unruly.api.language./g' {} +
```

Or delete each old import and let your IDE add the new one. A wildcard import, `api.exception.*`, becomes
`api.language.*`, plus `api.*` where the file names an engine exception. A Javadoc link follows the table:
`.../io/github/brantunger/unruly/api/exception/RuleExecutionException.html` becomes
`.../io/github/brantunger/unruly/api/RuleExecutionException.html`.

No method was renamed, and this change alters no signature beyond the package of a type it names. So once the
imports compile, the rest does, except a language's read of [`declaredFacts()`](#-language-implementers):
`RuleListener.onError(Rule, RuleExecutionException)`, `RulesEngine.validate`'s `List<RuleCompilationException>`,
`Expression(String, ExpressionKind, String)` and `CompileContext.warn(Expression, InvalidExpressionException.Issue)`
keep their shapes.

The old package isn't kept as deprecated aliases: an enum in a public signature can't be aliased, so 3.0 ships no
class in `api.exception`. A 2.x source fails to compile until its imports change, and a jar compiled against 2.x
that names a moved type has to be rebuilt, as the next section says.

### Jars compiled against 2.x

A jar compiled against 2.x isn't binary compatible with 3.0 wherever it names a moved type. Rebuild every such jar
against 3.0: your own modules, and third-party listeners and expression languages. Don't wait for an error to tell
you which ones, because one case gives none. What an old class does on 3.0 depends on how it uses the type:

| Code compiled against 2.x that... | On 3.0 |
| --- | --- |
| Catches or throws a moved exception | `NoClassDefFoundError` as soon as the class loads, even if nothing is thrown |
| Reads a moved type, such as `ExpressionKind.CONDITION` or an element of `validate`'s list | `NoClassDefFoundError` when that code runs |
| Calls a method or constructor whose signature names a moved type, such as `listener.onError(rule, error)` or `new Expression(text, kind, ruleName)` | `NoSuchMethodError` at the call |
| Overrides `onError` in a `RuleListener`, or in a subclass of `LoggingRuleListener` | **Can load with no error, and then its `onError` is never called** |

> [!WARNING]
> A listener compiled against 2.x that overrides `onError` can load and run on 3.0 with no error, and never be told
> that a rule failed. `onError` is a default method whose parameter type moved, so the old override no longer
> overrides it, and the engine calls the empty default, or `LoggingRuleListener`'s own, instead. The listener's
> other callbacks, such as `beforeEvaluate` and `onRunError`, are still called, so it looks healthy.

Rebuilding against 3.0 finds every case, the silent one too: the old import doesn't compile.

### Serialized exceptions

The four exception classes and `InvalidExpressionException.Issue` are `Serializable`, and a serialized object names its
class in full. A stream a 2.x program wrote holds `io.github.brantunger.unruly.api.exception.RuleExecutionException`,
which 3.0 doesn't have, so reading it on 3.0 throws `ClassNotFoundException`; a stream 3.0 wrote fails the same way
on 2.x. Each class keeps its `serialVersionUID`, which doesn't help: it's compared only once the class is found.

This affects an application that stores or sends the engine's exceptions with Java serialization, across a cache or
a queue for example.

### Log output and messages

Wherever a class name is printed, a moved type shows its new package:

- **`toString()` and stack traces:** what `load()` throws prints as
  `io.github.brantunger.unruly.api.RuleCompilationException: ...`, where 2.x printed
  `io.github.brantunger.unruly.api.exception.RuleCompilationException: ...`. So does a moved type in a `Caused by:`
  line. Most failures of `run()` print as an
  [internal subclass](error-handling.md#-what-happens-on-each-failure) in `core`, as they did on 2.x.
- **`getMessage()`** is unchanged, except where it names a class, now under the new package: what an output
  supplier threw, a [root-cause note](exception-messages.md#-shortening-and-escaping), and a run-time throw
  that has no message. A `load()` rejection with no message still reads `was rejected by its expression language`.

Update any log filter, alert or test that matches the old names. Catch the type rather than match its name where
you can.

### Native images

With [strict metadata](native-image.md#-strict-metadata), the library's packages to list are `api`, `api.language`
and `core`, and MVEL's `mvel` and `mvel.warmup`. Drop `api.exception`, and `internal` if you listed it.

### The internal package is gone too

The nine classes of `io.github.brantunger.unruly.internal` moved into `core`. `internal` was never exported by the
module, shown in the Javadoc or covered by the compatibility check, and `core` is exported only to the test kit's
module, so nothing changes for you.

## 📦 Upgrade the test kit with the engine

**What changed:** the test kit, `unruly-engine-test`, names the moved types: its `ExpressionLanguageContractTest`
imports `ExpressionKind`, `RuleCompilationException`, `RuleExecutionException` and `UnrulyException`. So a 2.x kit
doesn't link against 3.0's `unruly-engine-core`, and a 3.0 kit doesn't link against a 2.x one.

**Who is affected:** language authors whose build pairs the kit and the engine at different versions: one that lists
versions by hand, or a Gradle build where another dependency asks for a newer `unruly-engine-core`, which Gradle
then picks.

**What to change:** move `unruly-engine-core`, `unruly-engine` and `unruly-engine-test` to 3.0.0 together, or import
`unruly-engine-bom`, which pins the three to one version, as
[Testing with the contract kit](languages/contract-kit.md#-testing-with-the-contract-kit) shows.

## 🔨 Language implementers

**What changed:** `CompileContext.declaredFacts()` returns `Map<String, java.lang.reflect.Type>`, where 2.x returned
`Map<String, Class<?>>`. An application can now declare a fact with its type arguments,
`fact("tags", new FactType<List<String>>() { })`, and a language is told the whole type, so a typed language can
check what a rule does with a list's elements. See [Fact types](fact-types.md).

**Who is affected:** a language that reads `declaredFacts()`. Code that declares facts isn't:
`fact(name, X.class)`, `facts(map)` and the `LanguageTestContexts.compile(...)` overloads take every argument they
took in 2.x, a `Map<String, Class<?>>` included, and need no change.

**What to change:** where your language reads a declared type.

| 2.x | 3.0 |
| --- | --- |
| `Map<String, Class<?>> declared = context.declaredFacts();` | `Map<String, Type> declared = context.declaredFacts();` |
| `Class<?> type = declared.get(name);` | `Type declaredType = declared.get(name);`, then, if it isn't `null`, `Class<?> type = CompileContext.rawClass(declaredType);` |
| `for (Map.Entry<String, Class<?>> fact : declared.entrySet())` | `for (Map.Entry<String, Type> fact : declared.entrySet())` |

Keep the `null` check: `declared.get(name)` is `null` for a name nobody declared, as in 2.x, and `rawClass` throws
`NullPointerException` for `null`.

Each value is one of two kinds: a `Class`, as before, for a fact declared with a class literal, a primitive still as
its wrapper; or a `ParameterizedType` whose raw type is a `Class`, such as `java.util.List<java.lang.String>`. A
`FactType` never reaches you: you get the type it stands for. The builder rejects every other kind of `Type` when
the fact is declared, so you never see one.

- **`CompileContext.rawClass(type)`**, a static method new in 3.0, returns the class of either kind: the class
  itself, or `List` for `List<String>`. A language with no use for type arguments calls it, as MVEL does.
- **The engine checks a run's value against that class only.** A value's type arguments are erased, so a fact
  declared as a `List<String>` can hold a list of anything; don't rely on the elements' type without checking it.
- **A parameterized type's arguments are as the application wrote them,** wildcards included.

### A language compiled against 2.x

Rebuild a language jar compiled against 2.x, as [Jars compiled against 2.x](#jars-compiled-against-2x) says: most
also name a moved type, such as `InvalidExpressionException`, `ExpressionKind` or `source.kind()`, and fail on it
whatever the application declares, with `NoClassDefFoundError` or `NoSuchMethodError`. This change adds one more
failure, which is the only one for a language that names no moved type.

The call to `declaredFacts()` itself still links, because the method's erased signature is unchanged, and it returns
without error. What happens next depends on how the application declared its facts:

| The application declares | A 2.x language that reads the type as a `Class` |
| --- | --- |
| Every fact with a class, `fact("tags", List.class)`, as on 2.x | Reads `interface java.util.List`, as on 2.x |
| A fact with type arguments, `fact("tags", new FactType<List<String>>() { })` | `ClassCastException` where it reads that fact's type, by `get` or while iterating |
| The same, and the language only prints the type or passes it on as an object | No error: it sees the `ParameterizedType`, `java.util.List<java.lang.String>` |

The `ClassCastException` surfaces wherever the language reads the value. `load()` throws, and `validate()` returns,
a `RuleCompilationException` with it as the cause, whose message depends on the place. For a read in `newCompiler`:

```text
The '<name>' expression language failed to create a compiler: class sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl cannot be cast to class java.lang.Class ...
```

For a read in `compileCondition`, it starts `Condition for rule '<rule>' failed to compile: class ...` instead.

Nothing fails silently, but nothing fails until an application declares a fact with type arguments, so rebuild the
language against 3.0 rather than wait for it: the compiler then rejects the read,
`incompatible types: Map<String,Type> cannot be converted to Map<String,Class<?>>`.

An application or a test compiled against 2.x that only declares facts, with `fact(String, Class)`, `facts(Map)` or
`LanguageTestContexts.compile(...)`, links at those calls and runs them unchanged on 3.0.

## 🧰 If you implement RulesEngine

Nothing beyond [the imports](#-the-apiexception-package-is-gone) yet: `validate(List<Rule>)` still returns
`List<RuleCompilationException>`, now from `api`. Changes to the `RulesEngine` interface that land in 3.0.0 are added
here.
