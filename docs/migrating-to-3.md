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
- [Upgrade every artifact together](#-upgrade-every-artifact-together)
- [The api.exception package is gone](#-the-apiexception-package-is-gone)
- [Upgrade the test kit with the engine](#-upgrade-the-test-kit-with-the-engine)
- [Language implementers](#-language-implementers)
  - [A language compiled against 2.x](#a-language-compiled-against-2x)
- [If you implement RulesEngine](#-if-you-implement-rulesengine)

---

## 📍 Which sections apply to you?

| You... | Read |
| --- | --- |
| Set the versions of `unruly-engine`, `unruly-engine-core` or `unruly-engine-test` separately, or have a dependency that brings one in | [Upgrade every artifact together](#-upgrade-every-artifact-together) |
| Catch `RuleCompilationException` or `RuleExecutionException`, write a `RuleListener`, or import anything else from `api.exception` | [The api.exception package is gone](#-the-apiexception-package-is-gone) |
| Run a jar compiled against 2.x that you don't rebuild, such as a third-party listener or language | [Jars compiled against 2.x](#jars-compiled-against-2x), then [Running 2.x jars on 3.0](migrating-to-3-unrebuilt-jars.md) |
| Store or send the engine's exceptions with Java serialization | [Serialized exceptions](#serialized-exceptions) |
| Match the engine's exception class names in log filters, alerts or tests | [Log output and messages](#log-output-and-messages) |
| Build a GraalVM native image with strict metadata | [Native images](#native-images) |
| Test a language with `unruly-engine-test` | [Upgrade the test kit with the engine](#-upgrade-the-test-kit-with-the-engine) |
| Write an expression language | [The imports](#-the-apiexception-package-is-gone), then [Language implementers](#-language-implementers) |
| Declare facts with `fact(name, X.class)` or `facts(map)` | Nothing: both compile and run as before; see [Language implementers](#-language-implementers) for what's new |
| Implement `RulesEngine` | [The imports](#-the-apiexception-package-is-gone), then [If you implement RulesEngine](#-if-you-implement-rulesengine) |

Nothing in your rule text changes. In code you rebuild, the compiler catches every change on this page. It can't
catch these:

- a [jar compiled against 2.x](#jars-compiled-against-2x) that you run without rebuilding it, which includes a
  [language that reads a declared fact's type as a `Class`](#a-language-compiled-against-2x);
- the artifacts' [versions set separately](#-upgrade-every-artifact-together);
- a [log filter, alert or test](#log-output-and-messages) that matches an exception's class name as text;
- a [native-image metadata list](#native-images);
- a language that compiles and still fails the [stricter test kit](#-upgrade-the-test-kit-with-the-engine);
- a [serialized exception](#serialized-exceptions) read across 2.x and 3.0.

## 📦 Upgrade every artifact together

**What changed:** the MVEL module, `unruly-engine`, and the test kit, `unruly-engine-test`, name the
[moved types](#-the-apiexception-package-is-gone). So each links only against an `unruly-engine-core` of its own
major.

**Who is affected:** any build that sets the version of `unruly-engine-core` separately from `unruly-engine` or
`unruly-engine-test`, or where another dependency raises or lowers one of them, a third-party language for example.

**What to change:** move all three to 3.0.0, or import `unruly-engine-bom`, which pins the three to one version, as
[Testing with the contract kit](languages/contract-kit.md#-testing-with-the-contract-kit) shows.

With the two at different versions, an application builds its engine, and then every `load()` throws, and
`validate()` returns, a `RuleCompilationException`. The first message is 2.x's `unruly-engine` on 3.0's core, the
second 3.0's `unruly-engine` on 2.x's core:

```text
The 'mvel' expression language failed to prepare: io/github/brantunger/unruly/api/exception/InvalidExpressionException
The 'mvel' expression language failed to prepare: io/github/brantunger/unruly/api/language/InvalidExpressionException
```

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

Or delete each old import and let your IDE add the new one. Replace a wildcard import, `api.exception.*`, with
imports of the moved types by name. The recipe turns it into `api.language.*` only, so check such a file by hand.
`api.language.*` and `api.*` bring in 38 types, `Session`, `Fact` and `Rule` among them. A file that also imports a
class of its own by one of those names with a wildcard then fails with `reference to Session is ambiguous`.

A Javadoc link follows the table:
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
against 3.0: your own modules, and third-party listeners and expression languages. Most fail with
`NoClassDefFoundError` or `NoSuchMethodError`, often wrapped in one of the engine's exceptions.

Don't wait for an error to tell you which ones, because three cases give your application none: a listener's
`onError` override is never called, and a read of a moved type inside a listener callback, or inside a language's
`close()`, is only logged at WARN. [Running 2.x jars on 3.0](migrating-to-3-unrebuilt-jars.md) lists what each case
does, by what you see.

Rebuilding against 3.0 finds every case, the silent ones too: the old import doesn't compile.

### Serialized exceptions

The four exception classes and `InvalidExpressionException.Issue` are `Serializable`. Don't read a serialized engine
exception across the 2.x and 3.0 boundary: when you upgrade, drain or drop every stream that holds one. This affects
an application that stores or sends the engine's exceptions with Java serialization, across a cache, a queue or a
session store for example, and above all one that runs 2.x and 3.0 side by side during a rolling upgrade.

A serialized object names its class and each superclass in full, so what happens depends on which of those names
moved:

| The serialized object | Written by 2.x, read on 3.0 | Written by 3.0, read on 2.x |
| --- | --- | --- |
| One whose own class moved: what `load()` throws and `validate()` returns, the plain `RuleExecutionException` a listener's `onError` or `onRunError` receives for a fatal `Error`, or any exception your code creates from one of the four classes | `ClassNotFoundException` | `ClassNotFoundException` |
| What `run()` throws for a failed or stopped run, which the listeners also receive: an internal subclass of `RuleExecutionException` | `InvalidClassException` | `InvalidClassException` |
| Your own subclass of one of the four classes, such as a language's rejection | `InvalidObjectException`, or `InvalidClassException` once you change its `serialVersionUID`; `ClassNotFoundException` if its cause or a suppressed exception is one whose own class moved | **No error: it reads with fields missing** |

The first row fails because the reader has no class of that name. The internal subclass kept its name, so 3.0 gives
it a new `serialVersionUID`, and each version refuses the other's. Your own subclass keeps its name too, and 3.0
refuses a 2.x stream of one because the stream holds no `api.language.UnrulyException`.

**The one silent case:** 3.0 can't make 2.x refuse your own subclass. 2.x reads one that 3.0 wrote with no error,
with its message, cause and stack trace, and without what the engine's classes hold: `getRuleName()`,
`getExpressionKind()` and `issues()` return `null`, and `RuleCompilationException.failures()` throws
`NullPointerException`. Only your application can close this: change the `serialVersionUID` of each such subclass
when you move it to 3.0, declaring one if it has none, and 2.x then fails with `InvalidClassException`.

### Log output and messages

Wherever a class name is printed, a moved type shows its new package:

- **`toString()` and stack traces:** what `load()` throws prints as
  `io.github.brantunger.unruly.api.RuleCompilationException: ...`, where 2.x printed
  `io.github.brantunger.unruly.api.exception.RuleCompilationException: ...`. So does a moved type in a `Caused by:`
  line. Most failures of `run()` print as an
  [internal subclass](error-handling.md#-what-happens-on-each-failure) in `core`, as they did on 2.x.
- **`getMessage()`** is unchanged, except where it names a class, now under the new package: what an output
  supplier threw, a [root-cause note](exception-messages.md#-shortening-and-escaping), and a run-time throw
  that has no message.

At `load()`, what a language threw while compiling with no message, other than an `InvalidExpressionException`, is
reported by its class name too: `Condition for rule 'r' failed to compile:`, then
`io.github.brantunger.unruly.api.language.UnrulyException` for a moved type. An `InvalidExpressionException` with
no message still reads `was rejected by its expression language`.

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

**What to change:** [upgrade every artifact together](#-upgrade-every-artifact-together).

With the kit and the engine at different versions, your test class still compiles. A runner that selects test
classes fails, with `NoClassDefFoundError` as the cause. Gradle's `test` task prints
`JUnit Jupiter > initializationError FAILED` and runs no test of the module; the JUnit ConsoleLauncher's
`--select-class` prints `resolution failed`.

A class-path or package scan, the launcher's `--scan-classpath` or `--select-package`, drops the contract test with
no message and exits 0, so a green run with fewer tests is the symptom.

The 3.0 kit is also stricter: a language that compiles against 3.0 and passed the 2.35 kit can fail it, as
[Upgrading to 3.0](languages/contract-kit-upgrading.md#-upgrading-to-30) lists.

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

Each value is one of two kinds: a `Class`, as before, for a fact declared with a class or a `FactType` of a type
with no type arguments, a primitive still as its wrapper; or a `ParameterizedType` whose raw type is a `Class`,
such as `java.util.List<java.lang.String>`. A `FactType` never reaches you: you get the type it stands for. The
builder rejects every other kind of `Type` when the fact is declared, so you never see one.

- **`CompileContext.rawClass(type)`**, a static method new in 3.0, returns the class of either kind: the class
  itself, or `List` for `List<String>`. A language with no use for type arguments calls it.
- **The engine checks a run's value against that class only.** A value's type arguments are erased, so a fact
  declared as a `List<String>` can hold a list of anything; don't rely on the elements' type without checking it.
- **A parameterized type's arguments are as the application wrote them,** wildcards included.

### A language compiled against 2.x

Rebuild a language jar compiled against 2.x. Most name a moved type, such as `InvalidExpressionException`,
`ExpressionKind` or `source.kind()`, and fail on it whatever the application declares. The application gets the
error wrapped, in a message that depends on where the language names the type:
[A language compiled against 2.x](migrating-to-3-unrebuilt-jars.md#-a-language-compiled-against-2x) lists each.

A language that names no moved type links and runs, until an application declares a fact with type arguments. Then
it fails with `ClassCastException` where it reads that fact's type as a `Class`, as
[the same page](migrating-to-3-unrebuilt-jars.md#a-language-that-reads-a-declared-facts-type-as-a-class) shows.

## 🧰 If you implement RulesEngine

**What changed:** nothing in the interface. `validate(List<Rule>)` still returns `List<RuleCompilationException>`,
now from `api`.

**Who is affected:** decorators, test doubles and any other class that implements `RulesEngine`.

**What to change:** [the imports](#-the-apiexception-package-is-gone), nothing else, and rebuild it, as every jar.

- **Not rebuilt,** an implementation that only delegates links and works on 3.0, because only `validate`'s generic
  signature names a moved type. One that catches or throws one of the exceptions fails with `NoClassDefFoundError`
  the first time the class is used. One that only creates one fails with it when that statement runs.
- **One that stores or sends the exceptions it catches:** see [Serialized exceptions](#serialized-exceptions).

Changes to the `RulesEngine` interface that land in 3.0.0 are added here.
