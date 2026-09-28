# 🚧 MVEL gotchas

Where MVEL compares, computes, assigns or calls code differently from Java, what to write instead, what
compiles slowly, and what MVEL logs itself.

**Who it's for:** rule authors, and application developers who configure logging.
**You'll be able to:** spot a condition that matches, or doesn't, only because of how MVEL compares, write an
action that stores exactly the value you meant, and turn off what MVEL logs itself.
**Before you start:** [MVEL](mvel.md).

[← Documentation index](../README.md)

- [Comparison gotchas](#-comparison-gotchas)
- [Assignment gotchas](#-assignment-gotchas)
- [Calling Java code](#-calling-java-code)
- [Compile time](#-compile-time)
- [MVEL's own logging](#-mvels-own-logging)

---

## 🚧 Comparison gotchas

MVEL compares and computes values more loosely than Java, which can make a condition match, or not match,
unexpectedly.

| Gotcha | Example | Do this instead |
| --- | --- | --- |
| 🔤 **Enums vs strings** | `order.status == 'SHIPPED'` is always `false` when `status` is an enum, with no error | `order.status.name() == 'SHIPPED'` |
| 🔢 **Type coercion** | `'1' == 1` is `true`. A `BigDecimal` of `1.00` equals `1`. | Compare values of the same type when the difference matters |
| ➗ **Division** | Without [strong typing](mvel.md#-strong-typing), MVEL divides as doubles, so `total / count` is `Infinity` when `count` is 0, not an error. With it on, MVEL computes in the declared types, so `total / count` on two `Integer` facts is an `Integer` and loses the fraction, and dividing by zero throws `ArithmeticException` | Check the divisor first: `count != 0 && total / count > 100` |
| 🔠 **String ordering** | A String fact `"10"` compared as `s > 9` is `true`, but `'10' > '9'` compares text and is `false` | Convert first: `Integer.parseInt(s) > 9` |
| 🕳️ **`empty`** | `s == empty` is `true` for `""`, and `n == empty` is `true` for `0` | Use `== ''` or `== 0` when you mean exactly that |
| ❓ **Missing facts** | A fact that isn't in the store fails the run, so `x == null` can't test for it. See [Null and missing facts](mvel.md#null-and-missing-facts) | `isdef x && x > 1` |
| 🔑 **A key missing from a `Map` fact** | `order.missing == null` fails the run with `could not access: missing`, rather than being `true` | `order['missing'] == null`, or `order.containsKey('missing')` |

## 🚧 Assignment gotchas

An assignment such as `output.rate = r` doesn't always do what the call `output.setRate(r)` does. MVEL first looks
for a public field named `rate` that can take the value, and writes it. Only when there is none does it look for a
setter, and it takes the first one-argument `setRate` it finds that can take the value. A call chooses by the value's
type instead: `output.setX(n)` with a `long` calls `setX(long)`.

| Gotcha | Example | Do this instead |
| --- | --- | --- |
| 🧱 **A public field beside its setter** | `output.rate = -5` writes the public field `rate` and never calls `setRate`, so the setter's checks don't run | `output.setRate(-5)`, or make the field private |
| 🎲 **Overloaded setters** | With `setX(int)`, `setX(long)` and `setX(double)`, `output.x = n` calls the first one that can take the value, and which one is first can change each time the JVM starts. So the same rule may store `n`, round it, or fail with `cannot coerce Long to Integer` | `output.setX(n)`, which picks the overload that matches the value's type |
| 0️⃣ **`null` for a primitive** | With `n` null, even when declared `fact("n", int.class)`, `output.x = n` stores `0` through an `int` setter. Into a public `int` field it stores `0` on the first run; later runs fail the rule with `unable to access field` (root cause a `NullPointerException` from MVEL's conversion) until MVEL's JIT compiles the assignment, which stores `0` again, or for good with `-Dmvel2.disable.jit=true`. `output.setX(n)` fails the rule: the cause is a `PropertyAccessException` (`unable to resolve method`) or, with strong typing, a `CompileException` whose root cause is a `NullPointerException`. MVEL's JUL `WARNING` for it is dropped while the engine runs the rule; see [MVEL's own logging](#-mvels-own-logging) | Test it first: `if (n != null) { output.setX(n) }`, or give the field a setter |
| 💥 **A public field of another type than the value** | A `long`, `float` or `double` field, even given its own box, or another field given another type, such as `int` given `Long` or `BigDecimal` given `Integer`: once MVEL's JIT optimizer compiles an assignment such as `output.big = n`, it can fail with a `VerifyError` (`Bad type on operand stack`, and MVEL prints `**** COMPILER BUG!` to standard output), an `IllegalArgumentException` or a `ClassCastException`, for one run or for every later run, depending on the field's type and the values assigned. An `Object` field, or another field given its own type, is safe | A setter. `-Dmvel2.disable.jit=true` avoids this row, but makes the `null` row worse for a public primitive field |

The last two depend on MVEL's JIT optimizer, which compiles an expression's accessor once it has run about 50 times
in quick succession, and can compile it again later, for example in each compiled copy. See
[The dynamic optimizer and class loaders](mvel.md#the-dynamic-optimizer-and-class-loaders).

## ☕ Calling Java code

MVEL reaches a fact's methods and getters through reflection, which fails in some cases where a Java call works.

| Gotcha | Example | Do this instead |
| --- | --- | --- |
| 🔒 **Facts whose class isn't public** | `applicant.score` on a package-private record fails with `could not access field`, even on the class path. So does `color.label` on an enum constant with a body that overrides `getLabel()`, such as `RED { ... }`: the constant's class is a non-public subclass | Make the record public, or have it implement a public interface that declares `score()`. For the enum constant, call the getter: `color.getLabel()` |
| 🫥 **An exception whose `getMessage()` throws** | When a method or getter a rule calls throws such an exception, MVEL loses it: the rule fails with what `getMessage()` threw as the cause, such as `Failed to execute action for rule 'r': nope`, and the original exception and its causes are gone. This lasts until one evaluation of the expression succeeds in that [compiled copy](../glossary.md#compiled-copy); later failures keep the original, with a `(message unavailable: ...)` note | Give the exception a `getMessage()` that doesn't throw |

## 🐢 Compile time

MVEL's compile time about doubles with each level of `new` nested inside `new`, or of `in` nested inside `in`. With
MVEL 2.5.4.Final, a condition of 17 nested `new Integer(` took about 400 ms to load, and one of 17 nested `(1 in [`
about 700 ms. Nothing bounds the time: only a stack overflow fails `load()`, as too deeply nested to compile.

Who pays it:

- `load()` and `validate()` compile each expression twice, once to check it and once to run it.
- The first [compiled copy](../glossary.md#compiled-copy) to run an expression takes the form `load()` compiled. Each
  later copy compiles it again, as described in [Compiled copies](mvel.md#-compiled-copies).
- A nested `new` also costs about half its load time on a copy's first run of it; a nested `in` doesn't.

Split a deep construction into local variables in an action, or move it into a Java method the rule calls; a
condition can't assign, so use a method there. An action with 16 nested `new Integer(` took 311 ms to load, and the
same value built in two steps of 8 took 5 ms.

## 🪵 MVEL's own logging

MVEL logs through `java.util.logging` (JUL), not SLF4J, under loggers named `org.mvel2` and below. With the JDK's
default JUL configuration, a record goes to standard error, whatever the application's SLF4J provider does.

One of those loggers can carry a fact value. When a method call or an indexed read fails inside MVEL, most often
because MVEL can't convert a fact to the method's parameter type, its
`org.mvel2.optimizers.impl.refl.ReflectiveAccessorOptimizer` logger logs a `WARNING` with a stack trace. The
exception's message often holds the fact value unescaped, so a line break in it can forge a log line. Examples:

- `items.get(index)` or `s.substring(index)`, with `index` a `String`.
- `m[k]`, where the `Map`'s own `get` throws: the message is that exception's.
- A `null` passed to a primitive setter under strong typing, as in the `null` for a primitive row above: its message
  holds no fact value.

The engine sets a filter on that one logger. It drops the record while one of the engine's MVEL rules is evaluating
or executing on the thread. Nothing is lost: MVEL still throws, and the engine reports the failure, escaped, as a
`RuleExecutionException` with the original exception in its cause chain. If the run was stopped at the same time, the
stop's `RuleExecutionException` keeps MVEL's exception among its suppressed exceptions instead.

A filter you set on that logger before the engine first loads MVEL rules keeps deciding every other record. MVEL
still logs the `WARNING` when:

- **You set a filter on that logger after that.** Yours replaces the engine's.
- **The logging setup refuses the filter**, such as one under a `SecurityManager` or a protected JBoss log context.
  The engine then runs without it.
- **An application deployed between two others is undeployed.** Applications in one JVM that share MVEL's logger,
  as with a JVM-wide JUL setup, are each covered while their rules run. With three or more, undeploying one deployed
  between two others leaves the rules of an earlier one that still runs uncovered.

The filter also drops this `WARNING` for your own Java code that a rule calls and that runs MVEL itself on the same
thread, while the rule runs. That code's failure still throws. MVEL's other JUL loggers aren't filtered. None is known
to log fact values on the engine's path, but they may log rule text.

To turn MVEL's JUL output off entirely, set `org.mvel2.level = OFF` in the JUL configuration, or route JUL through
your logging, for example with jul-to-slf4j or log4j-jul, and turn off `org.mvel2` there. For the engine's own
loggers, see [Logging setup](../listeners-and-logging.md#-logging-setup).
