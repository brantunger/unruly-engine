# 🚧 MVEL gotchas

Where MVEL compares, computes or assigns differently from Java, and what to write instead.

**Who it's for:** rule authors.
**You'll be able to:** spot a condition that matches, or doesn't, only because of how MVEL compares, and write an
action that stores exactly the value you meant.
**Before you start:** [MVEL](mvel.md).

[← Documentation index](../README.md)

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
| 🔒 **Facts whose class isn't public** | `applicant.score` on a package-private record fails with `could not access field`, even on the class path | Make the record public, or have it implement a public interface that declares `score()` |

## 🚧 Assignment gotchas

An assignment such as `output.rate = r` doesn't always do what the call `output.setRate(r)` does. MVEL first looks
for a public field named `rate` that can take the value, and writes it. Only when there is none does it look for a
setter, and it takes the first one-argument `setRate` it finds that can take the value. A call chooses by the value's
type instead: `output.setX(n)` with a `long` calls `setX(long)`.

| Gotcha | Example | Do this instead |
| --- | --- | --- |
| 🧱 **A public field beside its setter** | `output.rate = -5` writes the public field `rate` and never calls `setRate`, so the setter's checks don't run | `output.setRate(-5)`, or make the field private |
| 🎲 **Overloaded setters** | With `setX(int)`, `setX(long)` and `setX(double)`, `output.x = n` calls the first one that can take the value, and which one is first can change each time the JVM starts. So the same rule may store `n`, round it, or fail with `cannot coerce Long to Integer` | `output.setX(n)`, which picks the overload that matches the value's type |
| 0️⃣ **`null` for a primitive** | With `n` null, even when declared `fact("n", int.class)`, `output.x = n` stores `0` in an `int` field or setter. `output.setX(n)` fails the rule: the cause is a `PropertyAccessException` (`unable to resolve method`) or, with strong typing, a `CompileException` whose root cause is a `NullPointerException`, after MVEL logs a JUL `WARNING` with a stack trace | Test it first: `if (n != null) { output.setX(n) }` |
| 💥 **A public primitive field** | Once MVEL's JIT optimizer compiles an assignment such as `output.big = n`, it can fail with a `VerifyError` (`Bad type on operand stack`, and MVEL prints `**** COMPILER BUG!` to standard output), an `IllegalArgumentException` or a `ClassCastException`, for one run or for every later run, depending on the field's type and the values assigned | A setter, or start the JVM with `-Dmvel2.disable.jit=true` |

The last one comes from MVEL's JIT optimizer, which compiles an expression's accessor once it has run about 50 times
in quick succession, and can compile it again later, for example in each compiled copy. See
[The dynamic optimizer and class loaders](mvel.md#the-dynamic-optimizer-and-class-loaders).
