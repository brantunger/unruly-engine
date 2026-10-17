# 📤 Converting values for the output

How a language converts a value an action returned, such as a `Double` 3.0 for a setter taking an `int`, so the
default output writer can set it, and which conversions are exact enough to make.

**Who it's for:** language authors.
**You'll be able to:** implement `convertOutput`, decide which values to convert and which to refuse, and know what the
default writer and an application's own writer do with your conversion.
**Before you start:** [Actions and results](custom.md#-actions-and-results) and
[The output object](../engines-and-runs.md#-the-output-object).

[← Documentation index](../README.md)

- [How the writer asks](#-how-the-writer-asks)
- [An example](#-an-example)
- [What to convert](#-what-to-convert)
- [Gotchas](#-gotchas)

---

## 🧭 How the writer asks

Since 2.32.0, `ExpressionCompiler` has `convertOutput(Object value, Class<?> target)`, a `default` method that returns
the value unchanged, so a language that doesn't override it changes nothing, and the write fails as it always did.

The default writer, `OutputWriter.beansAndMaps()`, calls it only when a returned property fits no setter of its name
as is or [widened as Java widens a primitive](../engines-and-runs.md#how-actions-change-it): then for each type a
setter takes, the most specific setter first, and calls the first setter that accepts what you return, as is or
widened. A primitive target such as `int.class` takes its wrapper.

- **`value` is never `null`.** Every setter taking a reference accepts `null`, and the engine's converter returns it
  unchanged without asking you.
- **Return a value `target` accepts**, or the value unchanged to say you have no opinion, as for a type you don't know.
  The writer then tries the next setter. A `null` you return is a value: the first setter taking a reference sets it.
- **It runs on the run's thread**, on many threads at once: keep it stateless and cheap.

To refuse, throw `IllegalArgumentException` naming the value and the target. The writer still tries the property's
other setters; when none takes the value, the first refusal fails the rule, naming it and the property. Anything else
you throw fails the write at once.

A `Map` output never asks: `put` takes any value. MVEL changes the output in place and never reaches the writer. An
application's own `outputWriter(...)` gets your conversion as an `OutputConverter`, and only if it overrides the
four-argument `set`; see [How actions change it](../engines-and-runs.md#how-actions-change-it).

## 📐 An example

A language whose numbers are all `Double` computes 3.0 for `1 + 2`. With a `com.example.Order` bean that has
`setCount(int)`, an action returning `ActionResult.set(Map.of("count", 3.0))` fails, without this method, with
`com.example.Order has no public method setCount that accepts a java.lang.Double (setCount(int) exists, but a value is
only widened as Java widens a primitive, never narrowed or converted)`. This compiler sets `count` to 3, and fails the
rule for 1.5 with its own message:

```java
import org.jspecify.annotations.Nullable;

// In the compiler of a language whose numbers are all Double: short and byte go the same way, with their own ranges.
@Override
public @Nullable Object convertOutput(Object value, Class<?> target) {
    if (!(value instanceof Double d)) {
        return value;                                       // not a Double: no opinion
    }
    if (target == int.class || target == Integer.class) {
        return (int) whole(d, Integer.MIN_VALUE, Integer.MAX_VALUE, target);
    }
    if (target == long.class || target == Long.class) {
        return whole(d, -(1L << 53), 1L << 53, target);    // beyond 2^53 a double can't count by ones
    }
    return value;                                           // a target this language leaves alone
}

private static long whole(double d, long min, long max, Class<?> target) {
    if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d) || d < min || d > max) {
        throw new IllegalArgumentException(d + " isn't a whole number " + target.getName() + " holds");
    }
    return (long) d;                                        // boxed to the target's wrapper by the return above
}
```

With `setCount(long)` beside `setCount(int)`, the writer asks for `int.class` first, then `long.class`: 3.0 reaches
`setCount(int)`, and 3,000,000,000.0 is refused for `int` and set by `setCount(long)`, as the first refusal only
counts when nothing is written.

Once `convertsOutputNumbers()` returns `true`, the [contract kit](contract-kit.md)'s `outputNumbersConverted` feeds an
`int` setter 1.0, which it must set, then 1.5, 3.0E9, both infinities and `NaN`, which must each fail the rule, and a
`long` setter 3.0E9, which it must set, then `Infinity` and 2^53 + 2, which must fail it. A conversion that checks
only that the value is whole and then narrows it with `intValue()` or `longValue()` fails the check.

## 📋 What to convert

Convert only what the target holds exactly: rounding a rule's result silently is a defect, and Java's own widening,
which the writer applies first, is the only lossy step a user expects.

| From | To | Convert when | Otherwise |
| --- | --- | --- | --- |
| `Double` | `int`, `short`, `byte` or a wrapper | Finite, integral and in the type's range | Refuse |
| `Double` | `long` or `Long` | Finite, integral and at most 2^53 in magnitude, where a `double` still counts by ones | Refuse |
| `Long` | `int`, `short`, `byte` or a wrapper | In the type's range | Refuse |
| Anything | A type you don't understand, such as `String` or `BigDecimal` | Never | Return the value unchanged |

Refuse fractions, `NaN`, infinities and overflow. A value the writer takes as is or widened, such as an `Integer` for
`setCount(long)`, never reaches you, so there is nothing to convert from a type the writer already handles.

## 🚧 Gotchas

| Gotcha | What happens | Do this instead |
| --- | --- | --- |
| **Narrowing with `intValue()` alone** | `((Double) 2.5).intValue()` is 2, and a `Double` above `Integer.MAX_VALUE` becomes `Integer.MAX_VALUE`: the rule's result changes and nobody is told | Check finite, integral and in range first, and refuse the rest |
| **Checking `d == Math.floor(d)` alone** | It's `true` for an infinity, so `Double.POSITIVE_INFINITY` reaches `longValue()` and sets `Long.MAX_VALUE` | Check `isInfinite()` and `isNaN()` too |
| **Returning a `Long` for `int.class`** | The writer doesn't narrow what you return: an `int` setter refuses a `Long`, and the write fails as without a conversion | Return the target's wrapper, an `Integer` for `int.class` or `Integer.class` |
