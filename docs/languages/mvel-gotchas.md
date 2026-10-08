# 🚧 MVEL gotchas

Where MVEL compares, computes, assigns or calls code differently from Java, what to write instead, why a rule can
start failing after about 50 runs, what compiles slowly, what a first load deep in a stack needs, and what MVEL logs
itself.

**Who it's for:** rule authors, and application developers who configure logging or deploy rules.
**You'll be able to:** spot a condition that matches, or doesn't, only because of how MVEL compares, write an
action that stores exactly the value you meant, and turn off what MVEL logs itself.
**Before you start:** [MVEL](mvel.md).

[← Documentation index](../README.md)

- [Comparison gotchas](#-comparison-gotchas)
- [Assignment gotchas](#-assignment-gotchas)
- [Calling Java code](#-calling-java-code)
- [A rule failing for ever after about 50 quick runs](#-a-rule-failing-for-ever-after-about-50-quick-runs)
- [Compile time](#-compile-time)
- [A first load or run deep in a stack](#-a-first-load-or-run-deep-in-a-stack)
- [MVEL's own logging](#-mvels-own-logging)

---

## 🚧 Comparison gotchas

MVEL compares and computes values more loosely than Java, so a condition can match, or not, unexpectedly.

| Gotcha | Example | Do this instead |
| --- | --- | --- |
| 🔤 **Enums vs strings** | `order.status == 'SHIPPED'` is always `false` when `status` is an enum, with no error | `order.status.name() == 'SHIPPED'` |
| 🔢 **Type coercion** | `'1' == 1` is `true`. A `BigDecimal` of `1.00` equals `1`. | Compare values of the same type when the difference matters |
| ➗ **Division** | Without [strong typing](mvel.md#-strong-typing), MVEL divides as doubles, so `total / count` is `Infinity` when `count` is 0, not an error. With it on, MVEL computes in the declared types: on two `Integer` facts, `total / count` is an `Integer`, losing the fraction, and dividing by zero throws `ArithmeticException` | Check the divisor first: `count != 0 && total / count > 100` |
| 🔠 **String ordering** | A String fact `"10"` compared as `s > 9` is `true`, but `'10' > '9'` compares text and is `false` | Convert first: `Integer.parseInt(s) > 9` |
| 🕳️ **`empty`** | `s == empty` is `true` for `""`, and `n == empty` is `true` for `0` | Use `== ''` or `== 0` when you mean exactly that |
| ❓ **Missing facts** | A fact that isn't in the store fails the run, so `x == null` can't test for it. See [Null and missing facts](mvel.md#null-and-missing-facts) | `isdef x && x > 1` |
| 🔑 **A key missing from a `Map` fact** | `order.missing == null` fails the run with `could not access: missing`, not `true` | `order['missing'] == null`, or `order.containsKey('missing')` |

## 🚧 Assignment gotchas

An assignment such as `output.rate = r` doesn't always do what the call `output.setRate(r)` does. MVEL first looks
for a public field named `rate` that can take the value, and writes it. Only without one does it look for a setter,
taking the first one-argument `setRate` it finds that can take the value. A call chooses by the value's
type instead: `output.setX(n)` with a `long` calls `setX(long)`.

| Gotcha | Example | Do this instead |
| --- | --- | --- |
| 🧱 **A public field beside its setter** | `output.rate = -5` writes the public field `rate` and never calls `setRate`, so the setter's checks don't run | `output.setRate(-5)`, or make the field private |
| 🎲 **Overloaded setters** | With `setX(int)`, `setX(long)` and `setX(double)`, `output.x = n` calls the first one that can take the value, and which is first can change each time the JVM starts, so the same rule may store `n`, round it, or fail with `cannot coerce Long to Integer` | `output.setX(n)`, which picks the overload that matches the value's type |
| 0️⃣ **`null` for a primitive** | With `n` null, even when declared `fact("n", int.class)`, `output.x = n` stores `0` through an `int` setter. Into a public `int` field it stores `0` on the first run; later runs fail the rule with `unable to access field` (root cause a `NullPointerException` from MVEL's conversion) until MVEL's JIT compiles the assignment, which stores `0` again, or for good with `-Dmvel2.disable.jit=true`. `output.setX(n)` fails the rule: the cause is a `PropertyAccessException` (`unable to resolve method`) or, with strong typing, a `CompileException` whose root cause is a `NullPointerException`. MVEL's JUL `WARNING` for it is dropped while the engine runs the rule; see [MVEL's own logging](#-mvels-own-logging) | Test it first: `if (n != null) { output.setX(n) }`, or give the field a setter |
| 💥 **A public field of another type than the value** | A `long`, `float` or `double` field, even given its own box, or another field given another type, such as `int` given `Long` or `BigDecimal` given `Integer`: once MVEL's JIT compiles an assignment such as `output.big = n`, it can fail with a `VerifyError` (`Bad type on operand stack`, and MVEL prints `**** COMPILER BUG!` to standard output), an `IllegalArgumentException` or a `ClassCastException`, for one run or every later one, depending on the field's type and the values assigned. An `Object` field, or another field given its own type, is safe | A setter. `-Dmvel2.disable.jit=true` avoids this row, but makes the `null` row worse for a public primitive field |

The last two depend on MVEL's JIT optimizer, which compiles an accessor once it has run about 50 times in quick
succession, and can compile it again later, such as in each compiled copy. See
[The dynamic optimizer and class loaders](mvel.md#the-dynamic-optimizer-and-class-loaders).

## ☕ Calling Java code

MVEL reaches a fact's methods and getters through reflection, which fails in some cases where a Java call works.

| Gotcha | Example | Do this instead |
| --- | --- | --- |
| 🔒 **Facts whose class isn't public** | `applicant.score` on a package-private record fails with `could not access field`, even on the class path. So does `color.label` on an enum constant with a body that overrides `getLabel()`, such as `RED { ... }`: the constant's class is a non-public subclass | Make the record public, or have it implement a public interface that declares `score()`. For the enum constant, call the getter: `color.getLabel()` |
| 🫥 **An exception whose `getMessage()` throws** | When a method or getter a rule calls throws such an exception, MVEL loses it: the rule fails with what `getMessage()` threw as the cause, such as `Failed to execute action for rule 'r': nope`, and the original exception and its causes are gone. This lasts until one evaluation of the expression succeeds in that [compiled copy](../glossary.md#compiled-copy); later failures keep the original, with a `(message unavailable: ...)` note | Give the exception a `getMessage()` that doesn't throw |

## 🔥 A rule failing for ever after about 50 quick runs

A rule works in tests and production's first minutes, then fails on every run, once MVEL's JIT optimizer takes
over its accessors; see
[The dynamic optimizer and class loaders](mvel.md#the-dynamic-optimizer-and-class-loaders). It has two shapes:

- On the class path, a `NoClassDefFoundError` or `ClassNotFoundException` naming a fact or output class: that class
  isn't reachable from the `load()` thread's context class loader; see
  [Class loaders](../thread-safety.md#-class-loaders).
- On the module path, an `IllegalAccessError` naming your package: it needs an export without a `to` clause; see
  [Installation](../../README.md#-installation).

## 🐢 Compile time

MVEL's compile time about doubles with each level of `new` nested in `new`, or `in` in `in`. With
MVEL 2.5.4.Final, a condition of 17 nested `new Integer(` took about 400 ms to load, and one of 17 nested `(1 in [`
about 700 ms. Nothing bounds the time: only a stack overflow fails `load()`.

A stack overflow reads `the expression is too long or too deeply nested to compile`. With under about 48 KB of stack
left where the engine caught it, or 160 KB before the JIT compiles the engine's check, it reads
`the stack ran out: it was compiled too deep in the stack, or on a thread whose stack is too small`
instead. A long expression on a small thread, such as 256 KB on macOS, can get it too.

Who pays it:

- `load()` and `validate()` compile each expression twice, once to check it and once to run it.
- The first [compiled copy](../glossary.md#compiled-copy) to run an expression takes the form `load()` compiled. Each
  later copy compiles it again; see [Compiled copies](mvel.md#-compiled-copies).
- A nested `new` also costs about half its load time on a copy's first run of it; a nested `in` doesn't.

Split a deep construction into local variables in an action, or a Java method the rule calls; a condition can't
assign, so needs the method. An action with 16 nested `new Integer(` took 311 ms to load; the same value in two steps
of 8, 5 ms.

## 🪜 A first load or run deep in a stack

An engine whose builder names MVEL prepares it at `build()`, else at its first use: it initializes the classes with a
static initializer that its MVEL module uses directly, then runs a property read, a method call, one with a `String`
literal, a `null` comparison and an assignment, and compiles a call MVEL can't resolve, so the JVM's first load and
run load fewer classes, needing less stack; see
[A first build or load deep in a stack](../nested-runs.md#-a-first-build-or-load-deep-in-a-stack).
Some first steps still need more stack than the engine checks for.

**A JVM's first `load()` of a feature those expressions lack** can load classes, needing more stack than its check. Too
deep, it throws `StackOverflowError` or a `RuleCompilationException` such as
`The 'mvel' expression language failed to create a compiler: java.lang.StackOverflowError` or
`Condition for rule 'r' failed to compile: the stack ran out: ...`, as in [Compile time](#-compile-time).

A retry at the same depth fails the same way, until one with more room succeeds anywhere, unless a static initializer
overflowed (see below). Load and run your rules, or same-shaped ones, near the top first: trivial ones aren't enough.

**If those expressions overflow**, they're skipped, and the JVM's first load or run loads their classes instead:
deep in a stack, a load at that depth can fail as above, and a run its rule, with a `StackOverflowError` in the cause
chain, until one with more room succeeds. The next `build()` of an engine whose builder names MVEL tries them again.
MVEL found without being named is prepared only once, by the first rule list that uses it, so they aren't tried again.

**MVEL's loop detection** walks the stack from an analysis's or run's 114th call for the class loader, as in a
chain of 57 parts or more, two calls a part. A walk with no room is skipped, and the next comes when due, so the walk
can't fail the rule; a loop stops [after more calls](mvel.md#-errors-when-rules-load).

**The JVM's first call of a method**, a getter a rule reads included, makes the JDK build the method's reflective
accessor, which generates classes for a signature of a shape no call has had yet. So until a run that fired an MVEL
rule's action returns normally, runs of rule lists with an MVEL rule check for
[more room](../exceptions-by-method.md).

**That larger check** removed every failure of a deep first run, for the shapes measured on x64 with JDK 21, 25 and
26 and the check JIT-compiled: a rule calling one method, also with a `new` or one call among its arguments. Deeper
nesting, such as calls three levels deep, can still overflow and fail its rule, with a `StackOverflowError` in
the cause chain: each level needs more.

**A rule's first inline list or map, `new` or `soundslike`** initializes an MVEL class `prepare()` doesn't, so, like
the JIT's first compile below, it can overflow deep in a stack. The engine's tests record which classes
the others initialize rather than cover them.

**MVEL's JIT isn't prepared.** It compiles an accessor with ASM once more than 50 runs have used it within 100 ms, and
its first compile initializes ASM's classes and some of the JDK's. MVEL marks the accessor compiled before it compiles
it, so a compile that overflows deep in a stack fails that run and isn't tried again: the accessor stays on MVEL's
slower reflective path, still correct.

An overflow in a class's static initializer leaves that class unusable for the JVM's life: every later use of that
feature fails, and after the JIT's, every other accessor's first compile fails one run the same way.

## 🪵 MVEL's own logging

The MVEL library logs through `java.util.logging` (JUL), not SLF4J, under loggers named `org.mvel2` and below. With
the JDK's default JUL configuration, a record goes to standard error, whatever the application's SLF4J provider does.
The engine's MVEL module logs through SLF4J.

One of those loggers can carry a fact value. When a method call or an indexed read fails inside MVEL, most often
as MVEL can't convert a fact to the method's parameter type, its
`org.mvel2.optimizers.impl.refl.ReflectiveAccessorOptimizer` logger logs a `WARNING` with a stack trace. The
exception's message often holds the fact value unescaped, so a line break in it can forge a log line. Examples:

- `items.get(index)` or `s.substring(index)`, with `index` a `String`.
- `m[k]`, where the `Map`'s own `get` throws: the message is that exception's.
- A `null` passed to a primitive setter under strong typing, as in the `null` row above: its message
  holds no fact value.

The engine's filter on that one logger drops the record while one of its MVEL rules runs on the thread. Nothing is
lost: MVEL still throws, and the engine reports the failure, escaped, as a `RuleExecutionException` with the original
exception in its cause chain. If the run was stopped at the same time, the stop's `RuleExecutionException` keeps
MVEL's exception among its suppressed ones instead.

A filter you set on that logger before the engine sets its own keeps deciding every other record. The engine sets it
when it builds an engine that names MVEL, such as with `defaultLanguage("mvel")`, or else when it first loads or
validates MVEL rules. MVEL still logs the `WARNING` when:

- **You set a filter on that logger after that.** Yours replaces the engine's.
- **The logging setup refuses the filter**, such as one under a `SecurityManager` or a protected JBoss log context;
  the engine runs without it.
- **An application deployed between two others is undeployed.** Applications in one JVM that share MVEL's logger,
  as with a JVM-wide JUL setup, are each covered while their rules run. With three or more, undeploying one deployed
  between two others leaves the rules of an earlier one that still runs uncovered.

The filter also drops this `WARNING` for Java code a rule calls that runs MVEL itself on the thread,
while the rule runs. That code's failure still throws. MVEL's other JUL loggers aren't filtered. None is known
to log fact values on the engine's path, but they may log rule text.

To turn MVEL's JUL output off, set `org.mvel2.level = OFF` in the JUL configuration, or route JUL through
your logging, as with jul-to-slf4j or log4j-jul, and turn off `org.mvel2` there. For the engine's own
loggers, see [Logging setup](../listeners-and-logging.md#-logging-setup).
