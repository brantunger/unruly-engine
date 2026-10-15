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
use throws `NoClassDefFoundError`, in your code too. A rule that needs such a class fails to load with
`Could not initialize class ...`, on every JDK: not as an overflow, although JDK 25 and 26 keep the overflow as the
error's cause.

```java
// At startup, near the top of a stack, before any build, load or run can be nested deep:
RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
        .defaultLanguage("mvel")   // prepares MVEL at build(), not at a deep first load()
        .build();
engine.load(rules);                // MVEL's first load of each feature your rules use, near the top too
```

## 🧭 What the first build prepares

**The JVM's first `build()` checks the room, then initializes the classes engines use:** the engine's, SLF4J's and
the JDK's, such as the clock, the SHA-256 digest, streams and locks, that an engine's calls use. A `build()` on another
thread meanwhile waits for it. Without room, `build()` throws `StackOverflowError` before it touches any of them or
checks any setting, even a wrong one. The next `build()` checks again.

It also loads the engine's classes a load or run uses, so a first load or run reads none of them from a file; a
language's own are its `prepare()`'s to load.

**It also runs a `ConcurrentLinkedQueue` of its own,** the JDK class a rule set keeps its idle copies in, so the
JVM links the call sites that change one. Up to 2.30.14, measured with `-Xcomp`, a deep first run could link them
and overflow after the queue changed, losing a copy or closing one left in it.

**On JDK 21 and 22, outside a native image, it also initializes the JDK's
`jdk.internal.misc.Blocker$ForkJoinPools`,** which a virtual thread's first file read would otherwise initialize. Deep
in a stack, that read could leave every later one failing for the life of the JVM. It initializes `ForkJoinPool` too,
with [the common-pool cost](#-what-a-language-prepares).

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
such as `1.5B`, can't leave them unusable, nor, on JDK 25 and 26, `ForkJoinTask` with them. It also loads the classes
that creating a compiler, compiling a rule and a first run of an assignment would otherwise read from a file; a form's
own are left to its first use, [below](#an-mvel-forms-first-compile-or-run).

**On JDK 25 and later, that initializes `ForkJoinPool` too,** as `BigDecimal`'s static initializer uses it. On JDK 21
and 22, the JVM's first `build()` does, with `Blocker$ForkJoinPools`. The common pool reads its system properties,
`java.util.concurrent.ForkJoinPool.common.*`, then: one set later is ignored. Set them on the command line, or before
MVEL is prepared, at the `build()`, or first `load()` or `validate()`, the table above names; on JDK 21 and 22, before
the JVM's first `build()`.

> [!TIP]
> To check the room at `build()`, not at a deep first `load()`, name the language
> (`.defaultLanguage("mvel")`) and build the JVM's first engine near the top of a stack. MVEL's first `load()`
> [can need more stack than its check](#an-mvel-forms-first-compile-or-run): load your rules once near the top too; see
> [MVEL deep in a stack](languages/mvel-gotchas.md#-a-first-load-or-run-deep-in-a-stack). That doesn't help a rule
> whose language looks a dotted name up as a class on every `load()` or `validate()`, as MVEL does: give each such
> call [more room](exceptions-by-method.md).

## 🚧 What isn't covered

The warm-up narrows the risk of a deep first use; it doesn't close it. Not covered:

- A name `language(...)`, `fact(...)` or `facts(...)` rejects, initializing the small class naming the problem before
  any check.
- Another instance of a prepared language class, such as a wrapper around another language, which gets no check, nor
  at a first use `prepare()`.
- Listeners' or your code's classes and javac-default concatenations: the JVM's first, or on JDK 25 and later one of
  several values, fails for good if linked too deep.
- The JDK's reflection beyond what the warm-up reaches, listed below.
- [The JDK classes rules use](#the-jdk-classes-a-rule-names-or-calls).

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
They also fail if a first load or run reads a class from a jar or class directory, other than the application's or a
language of its own.

A path they don't take may initialize or load one, as may a JDK other than 21, 25 and 26, whose classes the engine
names, skipping missing ones; a native image names none. Nothing prepares
[what MVEL leaves to first use](languages/mvel-gotchas.md#-a-first-load-or-run-deep-in-a-stack), or the logging
classes a first logged message initializes: with `slf4j-simple`, SLF4J's `Level` and `FormattingTuple`; with
Logback, some of its own and the `java.time` classes it formats dates with.

**An overflow in the engine's own log call loses that line, not what the call does.** Deep in a stack, the JVM's
first logged message, such as the engine's ERROR line for a rule's failure or its WARN line for a listener's
exception, can overflow while the provider loads those classes. The engine catches that overflow in its ERROR and
WARN log calls, all but a few last-resort ones made only while a fatal error, or a failure it never expects, is
already being handled.

The call then ends as it would have near the top: `load()` throws its `RuleCompilationException`, or succeeds
past a language's warning; a run fails the rule, throws its failure or what stopped it, or goes on past a warning or
a listener's exception; a rejected fact throws its `IllegalArgumentException`; and a fatal error is still thrown.
The lost line is gone: nothing logs it later, not even a `load()` or `run()` around a nested one.

Measured with `slf4j-simple` 2.0.20 and Logback 1.5.38, on JDK 21 and 25, none of those overflows left a class
unusable: each struck while loading a class, not in its static initializer. Other paths and providers aren't
measured. With no provider, `slf4j-api` alone, nothing is logged, and nothing overflowed.

> [!NOTE]
> Up to 2.30.12, or 2.30.14 for the WARN lines on keeping an idle copy or retiring rules after a failed load, the
> engine didn't catch it: the caller got a bare `StackOverflowError`, with no message or line.

### The JDK classes a rule names or calls

**Beyond what the sections above prepare, the engine leaves the JDK classes a rule names or calls to their first
load or run.** It can't know them in advance: they're whatever JDK API the rules use. If the JVM's first use of one
is deep in a stack, its static initializer can overflow, leaving the class unusable as
[above](#-build-the-first-engine-near-the-top).

There are two ways in:

- **A load or `validate()`.** MVEL's analysis looks up a class a rule names by its full name, such as
  `java.time.LocalDate`, with `Class.forName`, which initializes it. So a rule only has to name the class.
- **A run.** A run's first use of a JDK feature initializes the classes behind it, which the rule doesn't name:
  `LocalDate.now()` reaches `java.time.zone.ZoneRulesProvider`.

Some examples, measured on JDK 25, where a deep first load or run left a class unusable:

| Rule | Deep first | Class left unusable |
| --- | --- | --- |
| `java.util.Currency.getInstance('USD').getCurrencyCode() == 'USD'` | `load()` | `java.util.Currency` |
| `java.time.LocalDate.now().getYear() > 2000` | `load()` | `java.time.LocalDate` |
| `java.time.LocalDate.now().getYear() > 2000` | `run()` | `java.time.zone.ZoneRulesProvider` |
| `java.text.NumberFormat.getInstance() != null` | `run()` | `sun.util.locale.provider.LocaleProviderAdapter` |

**The class then stays unusable for the JVM's life,** at any depth and from any engine: every later load or run of a
rule that needs it fails with `Could not initialize class ...`, and so does your code's use of it. `String.format` of
a number needs `LocaleProviderAdapter` too.

Only the JVM's first use of that JDK feature can do this. An application that has already called `LocalDate.now()`,
or formatted a number, isn't exposed for those classes.

**Use those JDK APIs once at startup, near the top of a stack,** before any load or run can be nested deep:

```java
java.time.LocalDate.now();                 // LocalDate and the time-zone rules
java.util.Currency.getInstance("USD");     // Currency
String.format("%d", 1);                    // LocaleProviderAdapter, which NumberFormat needs too
```

Those calls read JDK system properties, such as `java.time.zone.DefaultZoneRulesProvider`, `java.util.currency.data`
and `java.locale.providers`: set any you need before making them, as a value set later is ignored.

Or load and run your rules, or same-shaped ones, near the top first. An application that loads rules from its users
can't know which APIs they'll call: give their deep loads and runs [more room](exceptions-by-method.md).

### An MVEL form's first compile or run

**The first time MVEL compiles or runs a form, it does work a later use skips,** such as loading, verifying and
linking classes. A form is a kind of expression: a `def` function, a projection or filter, `foreach`, a `for` loop, a
unary minus, a ternary, a regex match or a method call.
[MVEL's warm-up](languages/mvel-gotchas.md#-a-first-load-or-run-deep-in-a-stack) runs only a few. A form whose first
use initializes an MVEL class, such as an inline list or map, `new` or `soundslike`, can instead leave that class
unusable; see [MVEL deep in a stack](languages/mvel-gotchas.md#-a-first-load-or-run-deep-in-a-stack).

Deep in a stack, a form's first use can need up to about 16 KB more than the about 8 KB that `load()` and `run()`
[check for](exceptions-by-method.md). The rule then fails just past the check, where the same rule passes once
warmed:

- A load throws a `RuleCompilationException` reading `the stack ran out`.
- A run fails the rule, reading `the stack overflowed`.

Measured on JDK 21 and 25, on Windows x64, a single first use in a new JVM needed this much more than the check
leaves. Hundreds of failed attempts at one, measured for a ternary on JDK 21, raised what a later one needed. A second
use needed about none: at most about 1 KB, for a load.

| Form | Deep first | About |
| --- | --- | --- |
| A `def` function, a projection, a filter or `foreach` | `run()` | 7-12 KB |
| A unary minus in a condition, such as `-x < 0` | `load()` | 14-16 KB |
| A static method call, such as `java.lang.Math.abs(x)` | `run()` | 5-7 KB |
| A `for` loop | `load()` | 9-13 KB |
| A regex match, such as `s ~= 'a.*'` | `load()` | 11-13 KB |
| A ternary | `run()` | 2.5-6 KB |
| A property write, an instance method call or `if`/`else` | `run()` | 0.5-6 KB |

**A language's first-run check doesn't close the gap.** A run checks about 13 KB while a language class its rules use
hasn't [finished a run](exceptions-by-method.md) in the JVM. That's JVM-wide: once it has, in any engine, every
other rule list's first run gets only the 8 KB check. No load gets that larger check. Even while it applies, a first
`def`, projection, filter or `foreach` still failed past it, needing at least about 2-5 KB more.

**Load and run your rules, or rules of the same forms, near the top first.** Measured on JDK 21, a form's deep use
then needed about none, even when the rule used near the top was a different one. Trivial rules aren't enough: a form
warms another only when they share classes, as a projection and a filter do; a unary minus doesn't warm a `for` loop.
For rules from your users, give deep loads and runs [more room](exceptions-by-method.md).
