# 🔼 Running 2.x jars on 3.0

What a class compiled against 2.x does when it runs on 3.0 without being rebuilt, listed by what you see.

**Who it's for:** anyone who runs a jar compiled against 2.x on 3.0: a third-party listener or expression language,
or a module of your own that wasn't rebuilt.
**You'll be able to:** trace an error or a log line to the old jar behind it, and name the failures that give no
error.
**Before you start:** [Migrating from 2.x to 3.0](migrating-to-3.md), which lists what changed. The fix is the same
for every case here: rebuild the jar against 3.0.

> [!NOTE]
> 3.0.0 isn't released yet. This page describes the unreleased 3.0 code, and has no copy at a released tag; on the
> latest 2.x release, none of it applies.

[← Documentation index](README.md)

- [The errors an old class gives](#-the-errors-an-old-class-gives)
- [Three failures that never reach your application](#-three-failures-that-never-reach-your-application)
- [A language compiled against 2.x](#-a-language-compiled-against-2x)

---

## 🚨 The errors an old class gives

A jar compiled against 2.x isn't binary compatible with 3.0 wherever it names a type that
[moved out of `api.exception`](migrating-to-3.md#-the-apiexception-package-is-gone). Rebuild every such jar: your own
modules, and third-party listeners and expression languages. What an old class does on 3.0 depends on how it uses
the type:

| Code compiled against 2.x that... | On 3.0 |
| --- | --- |
| Catches or throws a moved exception | `NoClassDefFoundError` as soon as the class loads, even if nothing is thrown |
| Reads a moved type, such as `ExpressionKind.CONDITION` or an element of `validate`'s list | `NoClassDefFoundError` when that code runs |
| Calls a method or constructor whose signature names a moved type, such as `source.kind()` | `NoSuchMethodError` at the call |
| Reads a moved type inside a `RuleListener` callback or a language's `close()`, or overrides `onError` | [No error reaches your application](#-three-failures-that-never-reach-your-application) |
| Names no moved type | Links and runs, with one exception: a [language that reads a declared fact's type](#a-language-that-reads-a-declared-facts-type-as-a-class) |

A statement gives the first error it meets. `new Expression(text, kind, ruleName)` and
`listener.onError(rule, error)` are calls of the third row, but an argument that reads, casts to or creates a moved
type comes first. Written as `new Expression(text, ExpressionKind.CONDITION, ruleName)`, the call fails with
`NoClassDefFoundError`.

Where the engine calls the old class, it reports these errors as it reports any failure of a language or a listener,
so the application often gets a different exception with the error as its cause.
[A language compiled against 2.x](#-a-language-compiled-against-2x) lists them.

Every error on this page names the old package, in its message or its cause's, as `api/exception/` or
`api.exception.`, so a search of your logs for either finds an old jar. Two cases print neither: the `onError`
override, and a [declared fact's type read as a `Class`](#a-language-that-reads-a-declared-facts-type-as-a-class).

Don't wait for an error to tell you which jars to rebuild, because three cases give your application none.
Rebuilding against 3.0 finds every case, the silent ones too: the old import doesn't compile.

## 🚧 Three failures that never reach your application

| A class compiled against 2.x that... | What you see on 3.0 | What is lost |
| --- | --- | --- |
| Overrides `onError` in a `RuleListener`, or in a subclass of `LoggingRuleListener` | **Nothing: no error and no log line** | Its `onError` is never called |
| Reads a moved type inside a `RuleListener` callback | A WARN each time the callback is called; the run goes on | The rest of that callback, every time |
| Reads a moved type in a language's `Session.close()` or `ExpressionCompiler.close()` | A WARN each time one is closed; the engine goes on | The rest of that `close()`, so what it releases is never released |

> [!WARNING]
> A listener compiled against 2.x that overrides `onError` can load and run on 3.0 with no error, and never be told
> that a rule failed. Its other callbacks, such as `beforeEvaluate` and `onRunError`, are still called, so it looks
> healthy.

**The `onError` override:** `onError` is a default method whose parameter type moved. The old override no longer
overrides it, so the engine calls the empty default, or `LoggingRuleListener`'s own, instead.

**A read inside a callback or a `close()`:** the engine contains what a listener throws, as
[Guarantees](listeners-and-logging.md#-guarantees) says, and what a language's `close()` throws. It logs the error at
WARN on the logger `io.github.brantunger.unruly.engine` and goes on. For a listener that tests
`error instanceof RuleExecutionException` in `onRunError`, and a language named `old` that reads an `ExpressionKind`
constant in both `close()` methods:

```text
Listener threw exception in onRunError: java.lang.NoClassDefFoundError: io/github/brantunger/unruly/api/exception/RuleExecutionException
The 'old' expression language failed to close a session: io/github/brantunger/unruly/api/exception/ExpressionKind
The 'old' expression language failed to close its compiler: io/github/brantunger/unruly/api/exception/ExpressionKind
```

The listener's line comes again on every call, since the class is missing every time.

## 🔨 A language compiled against 2.x

Most 2.x languages name a moved type, such as `InvalidExpressionException`, `ExpressionKind` or `source.kind()`,
and fail on it whatever the application declares. The engine wraps the error as it wraps any failure of a language,
so what the application gets depends on where the language names the type:

| Where the 2.x language names a moved type | On 3.0 |
| --- | --- |
| In the language class itself, where it throws or catches a moved exception | `NoClassDefFoundError` where the application creates the language. If the engine finds the class with `ServiceLoader`, `build()` throws `ServiceConfigurationError`, even when no rule is written in that language |
| In its compiler class, such as a `throw new InvalidExpressionException(...)` | `build()` works. For any rule in that language, `load()` throws, and `validate()` returns, `The '<name>' expression language failed to create a compiler: ...` |
| In a call made while it compiles a rule, such as `source.kind()` | `load()` throws, and `validate()` returns, a `RuleCompilationException` that names the rule: `Condition for rule '<rule>' failed to compile: ...`. The rule is fine |
| In a call made only when a rule runs | `validate()` and `load()` pass. Each `run()` fails: `Failed to evaluate condition for rule '<rule>': ...` |
| In `Session.close()` or `ExpressionCompiler.close()` | A WARN only; see [Three failures that never reach your application](#-three-failures-that-never-reach-your-application) |

The four messages in full, for a language named `old`, whose class is `com.example.OldLanguage`, and a rule named
`r`:

```text
java.util.ServiceConfigurationError: io.github.brantunger.unruly.api.language.ExpressionLanguage: com.example.OldLanguage Unable to get public no-arg constructor
The 'old' expression language failed to create a compiler: io/github/brantunger/unruly/api/exception/InvalidExpressionException
Condition for rule 'r' failed to compile: 'io.github.brantunger.unruly.api.exception.ExpressionKind io.github.brantunger.unruly.api.language.Expression.kind()'
Failed to evaluate condition for rule 'r': 'io.github.brantunger.unruly.api.exception.ExpressionKind io.github.brantunger.unruly.api.language.Expression.kind()'
```

In each, `getCause()` is the `NoClassDefFoundError` or `NoSuchMethodError`. MVEL is such a language too: 2.x's
`unruly-engine` on 3.0's core fails every `load()`, as
[Upgrade every artifact together](migrating-to-3.md#-upgrade-every-artifact-together) shows.

### A language that reads a declared fact's type as a Class

3.0's [`declaredFacts()`](migrating-to-3.md#-language-implementers) can hold a `ParameterizedType` where 2.x held
only a `Class`. This adds one more failure, which is the only one for a language that names no moved type.

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
`incompatible types: Map<String,Type> cannot be converted to Map<String,Class<?>>`. A `get` gives
`Type cannot be converted to Class<?>`, and a loop `Entry<String,Type> cannot be converted to Entry<String,Class<?>>`.

An application or a test compiled against 2.x that only declares facts, with `fact(String, Class)`, `facts(Map)` or
`LanguageTestContexts.compile(...)`, links at those calls and runs them unchanged on 3.0.
