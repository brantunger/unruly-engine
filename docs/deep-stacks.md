# 🪜 A first build or load deep in a stack

This page says what the engine prepares before a first build, load or run that may start deep in a stack, and what it
leaves to first use.

**Who it's for:** application developers whose engines may be built, or rules loaded or run, deep in a stack, such as
from a [nested run](nested-runs.md), and language authors.
**You'll be able to:** build the JVM's first engine where it can prepare everything, and know which first uses can
still leave a class unusable.
**Before you start:** [Nested runs](nested-runs.md).

[← Documentation index](README.md)

- [Build the first engine near the top](#-build-the-first-engine-near-the-top)
- [What the first build prepares](#-what-the-first-build-prepares)
- [What a language prepares](#-what-a-language-prepares)
- [What isn't covered](#-what-isnt-covered)

---

## 🚀 Build the first engine near the top

A nested run or load can start deep in a stack; a language's first runs [check more room](exceptions-by-method.md).
A `StackOverflowError` inside a class's static initializer leaves the class unusable for the JVM's life: every later
use throws `NoClassDefFoundError`, in your code too.

```java
// At startup, near the top of a stack, before any build, load or run can be nested deep:
RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
        .defaultLanguage("mvel")   // prepares MVEL at build(), not at a deep first load()
        .build();
engine.load(rules);                // MVEL's first load of each feature your rules use, near the top too
```

## 🧭 What the first build prepares

**The JVM's first `build()` checks the room, then initializes the classes engines use:** the engine's, SLF4J's and
the JDK's, such as the clock, the SHA-256 digest and streams, that an engine's calls use. Without room, `build()`
throws `StackOverflowError` before it touches any of them or checks any setting, even a wrong one. The next `build()`
checks again.

The check takes about 160 KB on x64, so a first build's thread needs about 200 KB on Windows x64, more on macOS,
where 256 KB can be too small.

**The first `build()` also warms up the JDK's reflection, outside a native image.** Over the public methods of
`Object`, `Class`, `Math`, `String`, `Thread`, `System` and `Iterable`, it reads the generic return and parameter
types, the annotations, and the class or method that declares each type variable among those types. Then it calls
`Object.hashCode` reflectively.

So a deep first read of a generic type, as in MVEL's analysis of a method a rule calls or the default output writer's
first write through a generic setter, finds the JDK's `sun.reflect.generics` classes for the shapes those methods have
initialized already. Nor can a deep first reflective call of a JDK method leave the proxy class of an annotation those
methods carry, such as `@IntrinsicCandidate`, unusable. A shape or an annotation type they lack is still left to its
first use; see [What isn't covered](#-what-isnt-covered).

## 🧩 What a language prepares

**A language initializes its own classes in [`prepare()`](languages/custom.md#preparing-the-languages-classes), and
may run its library once,** after the same check, made once per language class:

| The language is | Prepared |
| --- | --- |
| Named with `language(...)`, `defaultLanguage(...)`, `option(...)` or `languageImports(...)` | At every `build()`, after the settings are checked |
| Found by `ServiceLoader`, unnamed, even as the only language: the usual MVEL setup | Once, at the first `load()` or `validate()` that uses it, an empty list using the default |

A first use too deep throws `StackOverflowError` from the check, unlogged, leaving the language untouched and earlier
rules loaded; for what `prepare()` throws, see
[its contract](languages/custom.md#preparing-the-languages-classes).

**MVEL's `prepare()` also initializes the JDK's `BigDecimal` and `BigInteger`,** so a deep first `BigDecimal` literal,
such as `1.5B`, can't leave them unusable, nor, on JDK 25 and 26, `ForkJoinTask` with them.

**On JDK 25 and later, that initializes `ForkJoinPool` too,** as `BigDecimal`'s static initializer uses it. The common
pool reads its system properties, `java.util.concurrent.ForkJoinPool.common.*`, then: one set later is ignored. Set
them on the command line, or before MVEL is prepared, at the `build()`, or first `load()` or `validate()`, the table
above names.

> [!TIP]
> To check the room at `build()`, not at a deep first `load()`, name the language
> (`.defaultLanguage("mvel")`) and build the JVM's first engine near the top of a stack. MVEL's first `load()` can need
> more stack than its check: load your rules once near the top too; see
> [MVEL deep in a stack](languages/mvel-gotchas.md#-a-first-load-or-run-deep-in-a-stack).

## 🚧 What isn't covered

The warm-up narrows the risk of a deep first use; it doesn't close it. Not covered:

- A name `language(...)`, `fact(...)` or `facts(...)` rejects, initializing the small class naming the problem before
  any check.
- Another instance of a prepared language class, such as a wrapper around another language, which gets no check, nor
  at a first use `prepare()`.
- Listeners' or your code's classes and javac-default concatenations: the JVM's first, or on JDK 25 and later one of
  several values, fails for good if linked too deep.
- The JDK's reflection beyond what the warm-up reaches, listed below.

Measured on JDK 21, 25 and 26, the reflection warm-up covers:

- The annotation types `@Deprecated`, `@CallerSensitive` and `@IntrinsicCandidate`, with `@ForceInline` on JDK 21
  and `@Restricted` on JDK 25 and 26.
- Generic signatures with type variables a class or a method declares, wildcards, parameterized types, `void` and
  `boolean`.
- On JDK 21, the first reflective call of a native method.

Still left to a first use deep in a stack:

- A generic signature that names another primitive type: `byte`, `char`, `double`, `float`, `int`, `long` or `short`.
- Type annotations, read with `getAnnotatedReturnType()` and the like.
- Repeatable annotations, read with `getAnnotationsByType()`.
- The proxy class of any other annotation type, yours or the JDK's, such as `@SafeVarargs` on `List.of`.
- The reflective accessor the JDK builds for a method with a signature no call has had yet; see
  [MVEL deep in a stack](languages/mvel-gotchas.md#-a-first-load-or-run-deep-in-a-stack).

Tests in new JVMs cover the engine's and MVEL's first builds, loads and runs: outputs, facts, listeners, failures,
nested runs, `validate()` and `close()`, a `1.5B` literal, a read of a value's class, a reflective call of `getClass()`
and `Class.forName`, and a write through a bridged generic setter.

They fail if a first load or run, or a first build before its check, initializes a class with a static initializer,
other than the application's, the JDK's hidden method-handle classes, and the documented ones below. HotSpot records an
overflow initializing one with an `ExceptionInInitializerError`, which the first build initializes, keeping it usable.

A path they don't take may initialize one, as may a JDK other than 21, 25 and 26, whose classes the engine names,
skipping missing ones; a native image names none. Nothing prepares
[what MVEL leaves to first use](languages/mvel-gotchas.md#-a-first-load-or-run-deep-in-a-stack), or the logging
classes a first logged message initializes: with `slf4j-simple`, SLF4J's `Level` and `FormattingTuple`.
